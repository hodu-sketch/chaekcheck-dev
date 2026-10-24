"""사이클 24 1단계: Haiku 5.5 프로토타입 (빌드·CI 밖, 손으로 돌림).

질문 20개(plan `docs/research/ai-recommend/llm-test-set.md` 1절)로 1차(낱말)·2차(고르기·글)를
Anthropic Messages API에 직접 보내고(JDK HttpClient와 같은 raw HTTP 모양, 표준 라이브러리만),
시간·토큰·비용·칸 차례·후보 밖 번호를 잽니다.

- 키: 환경 변수 CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY. 어디에도 출력·저장하지 않습니다.
- 질문 글은 결과 파일에 쓰지 않습니다(번호만). 후보 책 제목·고른 번호·글은 씁니다.
- 후보 파일(TSV, 머리줄): book_id\ttitle\tauthor[\tline]  — 한 줄(line)이 없으면 제목·저자만.

사용 예:
  python scripts/aisearch/prototype_haiku.py --candidates books.tsv --out out/ --variant low
  python scripts/aisearch/prototype_haiku.py --candidates books.tsv --out out/ --variant off --only 1,2,20
  python scripts/aisearch/prototype_haiku.py --candidates books.tsv --out out/ --stage keyword   # 1차만
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import time
import unicodedata
import urllib.error
import urllib.request
from dataclasses import dataclass, field

BASE_URL = "https://api.anthropic.com"          # https://platform.claude.com/docs/en/api/versioning (2026-10-24)
ANTHROPIC_VERSION = "2026-10-21"                # 같은 문서, 최신 판
MODEL = "claude-haiku-5-5"                      # [확정]
PRICE_IN_PER_MTOK = 0.10                        # [확정] 10만 토큰 이하 요청
PRICE_OUT_PER_MTOK = 0.50
CANDIDATE_MAX = 30
PICK_MAX = 8
KEYWORD_MAX = 8
KEYWORD_TIMEOUT_S = 5.0
PICK_TIMEOUT_S = 15.0

# plan llm-test-set.md 1절 [안]
QUESTIONS = {
    1: "출근길 지하철에서 가볍게 읽을 과학책",
    2: "잠들기 전에 조금씩 읽을 책",
    3: "마음이 지쳤을 때 위로가 되는 에세이",
    4: "기분 전환이 되는 웃긴 소설",
    5: "초등학교 고학년 아이와 같이 읽을 책",
    6: "부모님께 선물할 건강 관련 책",
    7: "경제를 처음 공부하는 사람을 위한 책",
    8: "철학 입문서인데 어렵지 않은 것",
    9: "조선 시대 역사를 이야기처럼 풀어 쓴 책",
    10: "기후 위기를 다룬 책",
    11: "『데미안』 같은 성장 소설",
    12: "반전이 있는 추리 소설",
    13: "한국 SF 단편집",
    14: "회사에서 글쓰기 실력을 늘리는 책",
    15: "혼자 사는 사람을 위한 요리책",
    16: "하루 만에 다 읽을 수 있는 짧은 소설",
    17: "여행 가서 읽을, 너무 무겁지 않은 인문학 책",
    18: "요즘 재밌는 책",
    19: "양자역학 대학원 교재",
    20: "오늘 점심 뭐 먹지",
    # 인계서 1단계가 더 보라고 한 것: 제목만(titleLike), 앞 대화를 가리킴(refersToShown)
    21: "채식주의자",
    22: "이 중에 소설만",             # history와 함께 보냄 (아래 EXTRA_HISTORY)
    23: "더 짧은 걸로",               # history와 함께
}
# 22·23번에 붙이는 앞 대화: (앞 질문 번호, 그때 보여 준 후보 수). 실제 bookIds는 1차 결과에서 채움.
EXTRA_HISTORY = {22: [2], 23: [16]}

KEYWORD_SYSTEM = """너는 도서관 책 찾기 도우미의 첫 단계다. 사용자가 쓴 글을 읽고 아래를 JSON으로만 답한다.
- notBook: 책을 찾는 요청이 아니면 true (예: 날씨, 식사, 잡담). 책을 찾는 말이면 false.
- titleLike: 글이 책 제목이나 저자 이름 그 자체로 보이면 true (예: "채식주의자", "한강"). 설명이나 조건을 적은 문장이면 false.
- title: titleLike가 true일 때 그 제목(또는 저자)을 사용자가 쓴 글에 있는 글자 그대로 적는다. 아니면 빈 문자열.
- refersToShown: 앞 대화에서 보여 준 책을 가리키는 말이면 true (예: "이 중에 소설만", "더 짧은 걸로", "두 번째 책 비슷한 것"). 아니면 false.
- keywords: 책 제목·저자·소개 한 줄에서 찾을 한국어 낱말 1~8개. 명사나 짧은 명사구로, 조사 없이. 주제(과학, 경제), 장르(소설, 에세이, 추리), 분위기(위로, 웃긴), 읽는 사람(아이, 입문)처럼 책 소개에 적힐 법한 말을 고른다. 같은 뜻의 다른 표현도 넣는다(예: "SF", "과학소설"). notBook이면 빈 배열.
- 앞 대화가 있으면 이번 글을 그 맥락에서 읽되, keywords는 이번 글의 뜻으로 고른다."""

KEYWORD_SCHEMA = {
    "type": "object",
    "properties": {
        "notBook": {"type": "boolean"},
        "titleLike": {"type": "boolean"},
        "title": {"type": "string"},
        "refersToShown": {"type": "boolean"},
        "keywords": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["notBook", "titleLike", "title", "refersToShown", "keywords"],
    "additionalProperties": False,
}

PICK_SYSTEM = """너는 도서 추천 도우미다.
- 반드시 아래 책들의 번호 중에서만 고른다. 아래에 없는 책을 말하지 않는다.
- 질문에 맞는 책을 0~8권 고른다. 맞는 책이 없으면 빈 목록을 돌려준다. 억지로 고르지 않는다.
- 맨 앞 head에 질문에 답하는 머리말을 한국어 한 문장으로 쓴다. 고른 책이 없으면 맞는 책이 적다고 쓴다.
- 고른 책마다 질문에 맞춘 글을 한국어 2~3문장으로 쓴다. 왜 이 질문에 맞는지가 드러나게 쓴다.
- 아래 책에 적힌 내용(제목, 저자, 한 줄)만 근거로 쓴다. 적혀 있지 않은 줄거리·수상·저자 정보를 지어내지 않는다.
- 한 줄의 문장을 그대로 옮기지 않는다. 자기 말로 다시 쓴다.
- head와 글은 사용자에게 그대로 보인다. "후보", "목록", 책 번호를 쓰지 않는다. 맞는 책이 없을 때도 "찾으시는 책"처럼 사용자 쪽에서 말한다.
- 앞 대화가 있으면 이어서 묻는 말로 읽는다. 앞 대화에서 보여 준 책을 가리키는 질문이면 그 책들 가운데서 고른다.
- 정해진 JSON 형식으로만 답한다."""

PICK_SCHEMA = {
    "type": "object",
    "properties": {
        "head": {"type": "string"},
        "picks": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"no": {"type": "integer"}, "text": {"type": "string"}},
                "required": ["no", "text"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["head", "picks"],
    "additionalProperties": False,
}


def normalize(s: str) -> str:
    """5단계 search_text와 같은 정리: NFKC → 소문자 → 빈칸 지움 [안]."""
    return "".join(unicodedata.normalize("NFKC", s).lower().split())


@dataclass
class Book:
    book_id: str
    title: str
    author: str
    line: str = ""

    @property
    def search_text(self) -> str:
        return normalize(self.title + " " + self.author + " " + self.line)


def load_books(path: str) -> list[Book]:
    books: list[Book] = []
    with open(path, encoding="utf-8", newline="") as f:
        r = csv.DictReader(f, delimiter="\t")
        for row in r:
            books.append(Book(row["book_id"].strip(), row["title"].strip(), (row.get("author") or "").strip(),
                              (row.get("line") or "").strip()))
    return books


def match_candidates(books: list[Book], keywords: list[str], exclude: set[str] = frozenset(),
                     include_first: list[Book] | None = None) -> list[Book]:
    """점수(맞은 낱말 수) 내림차순 → book_id 순, 30권. 계획 도서관 수는 프로토타입에 없음."""
    words = []
    for k in keywords[:KEYWORD_MAX]:
        n = normalize(k)
        if len(n) >= 2 and n not in words:
            words.append(n)
    scored = []
    for b in books:
        if b.book_id in exclude:
            continue
        st = b.search_text
        score = sum(1 for w in words if w in st)
        if score > 0:
            scored.append((score, b))
    scored.sort(key=lambda t: (-t[0], t[1].book_id))
    out = list(include_first or [])
    seen = {b.book_id for b in out}
    for _, b in scored:
        if len(out) >= CANDIDATE_MAX:
            break
        if b.book_id not in seen:
            out.append(b)
            seen.add(b.book_id)
    return out[:CANDIDATE_MAX]


# ---------------------------------------------------------------- HTTP

class ApiError(Exception):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}")
        self.status = status
        self.body = body


def _headers(api_key: str) -> dict:
    return {"content-type": "application/json", "x-api-key": api_key, "anthropic-version": ANTHROPIC_VERSION,
            "accept": "application/json, text/event-stream"}


def thinking_params(variant: str) -> dict:
    """variant: default(adaptive, effort medium) | low(adaptive, effort low) | off(thinking disabled, effort low)."""
    if variant == "default":
        return {}
    if variant == "low":
        return {"output_config": {"effort": "low"}}
    if variant == "off":
        return {"thinking": {"type": "disabled"}, "output_config": {"effort": "low"}}
    raise ValueError(variant)


def merged_output_config(body: dict, schema: dict) -> dict:
    oc = dict(body.get("output_config", {}))
    oc["format"] = {"type": "json_schema", "schema": schema}
    body["output_config"] = oc
    return body


def post_json(api_key: str, body: dict, timeout: float) -> tuple[dict, float, float]:
    """보통 요청. (응답 JSON, 첫 바이트까지 초, 끝까지 초)"""
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(f"{BASE_URL}/v1/messages", data=data, headers=_headers(api_key), method="POST")
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            t_first = time.monotonic() - t0
            raw = resp.read()
            t_end = time.monotonic() - t0
            return json.loads(raw), t_first, t_end
    except urllib.error.HTTPError as e:
        raise ApiError(e.code, e.read().decode("utf-8", "replace")) from None


@dataclass
class StreamResult:
    text: str = ""
    input_tokens: int = 0
    output_tokens: int = 0
    stop_reason: str = ""
    t_first_byte: float = 0.0
    t_first_text: float = 0.0
    t_end: float = 0.0
    events: list[str] = field(default_factory=list)
    delta_count: int = 0
    thinking_blocks: int = 0
    usage_event: str = ""           # usage가 온 이벤트 이름들
    key_order: list[str] = field(default_factory=list)  # 스트림에서 JSON 키가 나타난 차례(관찰)
    error: str = ""


def post_stream(api_key: str, body: dict, timeout: float) -> StreamResult:
    """스트림 요청. SSE 줄을 직접 읽음(6단계 AnthropicLlmClient와 같은 방식)."""
    body = dict(body, stream=True)
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(f"{BASE_URL}/v1/messages", data=data, headers=_headers(api_key), method="POST")
    res = StreamResult()
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            res.t_first_byte = time.monotonic() - t0
            event = None
            seen_keys: list[str] = []
            for raw_line in resp:
                if time.monotonic() - t0 > timeout:
                    res.error = "timeout"
                    break
                line = raw_line.decode("utf-8").rstrip("\r\n")
                if line.startswith("event:"):
                    event = line[6:].strip()
                    res.events.append(event)
                    continue
                if not line.startswith("data:"):
                    continue
                payload = json.loads(line[5:].strip())
                typ = payload.get("type")
                if typ == "message_start":
                    u = payload["message"].get("usage", {})
                    res.input_tokens = u.get("input_tokens", 0)
                    res.usage_event += "message_start(input) "
                elif typ == "content_block_start":
                    if payload["content_block"].get("type") == "thinking":
                        res.thinking_blocks += 1
                elif typ == "content_block_delta":
                    d = payload["delta"]
                    if d.get("type") == "text_delta":
                        if not res.text:
                            res.t_first_text = time.monotonic() - t0
                        res.text += d["text"]
                        res.delta_count += 1
                        _track_keys(res.text, seen_keys)
                elif typ == "message_delta":
                    u = payload.get("usage", {})
                    res.output_tokens = u.get("output_tokens", res.output_tokens)
                    res.stop_reason = payload.get("delta", {}).get("stop_reason") or res.stop_reason
                    res.usage_event += "message_delta(output) "
                elif typ == "error":
                    res.error = json.dumps(payload.get("error"), ensure_ascii=False)
            res.key_order = seen_keys
            res.t_end = time.monotonic() - t0
    except urllib.error.HTTPError as e:
        raise ApiError(e.code, e.read().decode("utf-8", "replace")) from None
    except TimeoutError:
        res.error = "timeout"
        res.t_end = time.monotonic() - t0
    return res


def _track_keys(text: str, seen: list[str]) -> None:
    """지금까지 받은 JSON 글자에서 "head" / "no" / "text" 키가 나타난 차례를 적음(관찰용, 완전한 파서 아님)."""
    for key in ('"head"', '"no"', '"text"'):
        count = text.count(key)
        have = sum(1 for s in seen if s == key)
        while have < count:
            seen.append(key)
            have += 1


# ---------------------------------------------------------------- 두 호출

def cost_usd(inp: int, out: int) -> float:
    return inp * PRICE_IN_PER_MTOK / 1e6 + out * PRICE_OUT_PER_MTOK / 1e6


def history_messages(history: list[tuple[str, list[str]]]) -> list[dict]:
    """앞 대화(질문, 보여 준 제목들)를 user/assistant 쌍으로. 제목만 넣음(R11-9)."""
    msgs = []
    for q, titles in history:
        msgs.append({"role": "user", "content": q})
        shown = "\n".join(f"- {t}" for t in titles) if titles else "(보여 준 책 없음)"
        msgs.append({"role": "assistant", "content": f"보여 준 책:\n{shown}"})
    return msgs


def keyword_call(api_key: str, question: str, history, variant: str, max_tokens: int) -> dict:
    body = {"model": MODEL, "max_tokens": max_tokens, "system": KEYWORD_SYSTEM,
            "messages": history_messages(history) + [{"role": "user", "content": question}]}
    body.update(thinking_params(variant))
    merged_output_config(body, KEYWORD_SCHEMA)
    rec = {"stage": "keyword", "variant": variant}
    try:
        resp, t_first, t_end = post_json(api_key, body, KEYWORD_TIMEOUT_S + 10)
    except ApiError as e:
        rec.update(ok=False, http=e.status, error=e.body[:500])
        return rec
    except TimeoutError:
        rec.update(ok=False, error="timeout")
        return rec
    text = "".join(b.get("text", "") for b in resp.get("content", []) if b.get("type") == "text")
    thinking = sum(1 for b in resp.get("content", []) if b.get("type") == "thinking")
    u = resp.get("usage", {})
    rec.update(t_first=round(t_first, 3), t_end=round(t_end, 3), input_tokens=u.get("input_tokens"),
               output_tokens=u.get("output_tokens"), stop_reason=resp.get("stop_reason"), thinking_blocks=thinking,
               cost_usd=round(cost_usd(u.get("input_tokens", 0), u.get("output_tokens", 0)), 6))
    try:
        parsed = json.loads(text)
        rec.update(ok=True, result=parsed, json_ok=True)
    except json.JSONDecodeError:
        rec.update(ok=False, json_ok=False, raw=text[:300])
    return rec


def pick_call(api_key: str, question: str, history, candidates: list[Book], variant: str, max_tokens: int) -> dict:
    lines = []
    for i, b in enumerate(candidates, 1):
        s = f"{i}. 제목: {b.title} / 저자: {b.author or '(모름)'}"
        if b.line:
            s += f" / 한 줄: {b.line}"
        lines.append(s)
    user = f"질문: {question}\n고를 수 있는 책:\n" + "\n".join(lines)
    body = {"model": MODEL, "max_tokens": max_tokens, "system": PICK_SYSTEM,
            "messages": history_messages(history) + [{"role": "user", "content": user}]}
    body.update(thinking_params(variant))
    merged_output_config(body, PICK_SCHEMA)
    rec = {"stage": "pick", "variant": variant, "candidate_count": len(candidates)}
    try:
        sr = post_stream(api_key, body, PICK_TIMEOUT_S + 15)
    except ApiError as e:
        rec.update(ok=False, http=e.status, error=e.body[:500])
        return rec
    rec.update(t_first_byte=round(sr.t_first_byte, 3), t_first_text=round(sr.t_first_text, 3), t_end=round(sr.t_end, 3),
               input_tokens=sr.input_tokens, output_tokens=sr.output_tokens, stop_reason=sr.stop_reason,
               thinking_blocks=sr.thinking_blocks, delta_count=sr.delta_count, usage_event=sr.usage_event.strip(),
               events=sorted(set(sr.events)), key_order=" ".join(k.strip('"') for k in sr.key_order),
               cost_usd=round(cost_usd(sr.input_tokens, sr.output_tokens), 6), stream_error=sr.error)
    # 칸 차례 확인: head 먼저, 그 뒤 (no, text) 반복
    ko = [k.strip('"') for k in sr.key_order]
    order_ok = bool(ko) and ko[0] == "head" and all(
        (ko[i] == "no" and i + 1 < len(ko) and ko[i + 1] == "text") for i in range(1, len(ko), 2))
    rec["order_ok"] = order_ok
    try:
        parsed = json.loads(sr.text)
        rec["json_ok"] = True
    except json.JSONDecodeError:
        rec.update(ok=False, json_ok=False, raw_tail=sr.text[-200:])
        return rec
    picks = parsed.get("picks", [])
    valid = {i for i in range(1, len(candidates) + 1)}
    out_of_range = [p["no"] for p in picks if p["no"] not in valid]
    nos = [p["no"] for p in picks]
    dup = len(nos) - len(set(nos))
    text_lens = [len(p["text"]) for p in picks]
    rec.update(ok=True, head=parsed.get("head"), pick_count=len(picks), out_of_range=out_of_range, duplicates=dup,
               over_8=max(0, len(picks) - PICK_MAX), text_len_max=max(text_lens, default=0),
               picks=[{"no": p["no"], "title": candidates[p["no"] - 1].title if p["no"] in valid else None,
                       "text": p["text"]} for p in picks])
    return rec


# ---------------------------------------------------------------- main

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--candidates", required=True, help="book_id\\ttitle\\tauthor[\\tline] TSV")
    ap.add_argument("--out", required=True, help="결과 폴더(JSONL + 요약 TSV)")
    ap.add_argument("--variant", default="default", choices=["default", "low", "off"])
    ap.add_argument("--stage", default="both", choices=["keyword", "both"])
    ap.add_argument("--only", help="질문 번호 쉼표 목록(기본 전부)")
    ap.add_argument("--keyword-max-tokens", type=int, default=1024)
    ap.add_argument("--pick-max-tokens", type=int, default=4096)
    ap.add_argument("--probe-maxitems", action="store_true", help="2차 스키마에 maxItems 8을 넣어 400인지 한 번 본다")
    args = ap.parse_args()

    api_key = os.environ.get("CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY", "")
    if not api_key:
        print("CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY is not set", file=sys.stderr)
        return 2
    books = load_books(args.candidates)
    print(f"books: {len(books)} (with line: {sum(1 for b in books if b.line)})")
    os.makedirs(args.out, exist_ok=True)
    nums = [int(x) for x in args.only.split(",")] if args.only else sorted(QUESTIONS)
    tag = f"{args.variant}-{time.strftime('%Y%m%d-%H%M%S')}"
    jsonl = open(os.path.join(args.out, f"calls-{tag}.jsonl"), "w", encoding="utf-8")
    summary = open(os.path.join(args.out, f"summary-{tag}.tsv"), "w", encoding="utf-8")
    summary.write("q\tvariant\tk_t_end\tk_in\tk_out\tk_thinking\tnotBook\ttitleLike\trefersToShown\tkeywords\t"
                  "cand\tp_t_first_text\tp_t_end\tp_in\tp_out\tp_thinking\tjson_ok\torder_ok\tpicks\tout_of_range\tdup\t"
                  "text_len_max\tcost_usd\n")

    if args.probe_maxitems:
        schema = json.loads(json.dumps(PICK_SCHEMA))
        schema["properties"]["picks"]["maxItems"] = PICK_MAX
        body = {"model": MODEL, "max_tokens": 64, "messages": [{"role": "user", "content": "질문: 짧은 책\n후보:\n1. 제목: 가 / 저자: 나"}]}
        merged_output_config(body, schema)
        try:
            post_json(api_key, body, 30)
            print("maxItems probe: accepted (200)")
        except ApiError as e:
            print(f"maxItems probe: HTTP {e.status}: {e.body[:300]}")

    shown_by_q: dict[int, list[Book]] = {}
    total_cost = 0.0
    for q in nums:
        question = QUESTIONS[q]
        history = []
        for prev in EXTRA_HISTORY.get(q, []):
            history.append((QUESTIONS[prev], [b.title for b in shown_by_q.get(prev, [])]))
        k = keyword_call(api_key, question, history, args.variant, args.keyword_max_tokens)
        k["q"] = q
        jsonl.write(json.dumps(k, ensure_ascii=False) + "\n")
        total_cost += k.get("cost_usd", 0)
        kr = k.get("result", {})
        print(f"[{q}] 1차 {k.get('t_end')}s in={k.get('input_tokens')} out={k.get('output_tokens')} "
              f"think={k.get('thinking_blocks')} ok={k.get('ok')} -> {json.dumps(kr, ensure_ascii=False)[:160]}")
        p = {}
        cands: list[Book] = []
        if args.stage == "both" and k.get("ok") and not kr.get("notBook"):
            exclude = set()
            include_first: list[Book] = []
            if history:
                last_shown = shown_by_q.get(EXTRA_HISTORY[q][-1], [])
                if kr.get("refersToShown"):
                    include_first = list(last_shown)
                else:
                    exclude = {b.book_id for b in last_shown}
            keywords = kr.get("keywords") or question.split()
            cands = match_candidates(books, keywords, exclude, include_first)
            if cands:
                p = pick_call(api_key, question, history, cands, args.variant, args.pick_max_tokens)
                p["q"] = q
                jsonl.write(json.dumps(p, ensure_ascii=False) + "\n")
                total_cost += p.get("cost_usd", 0)
                print(f"     2차 cand={len(cands)} first_text={p.get('t_first_text')}s end={p.get('t_end')}s "
                      f"in={p.get('input_tokens')} out={p.get('output_tokens')} think={p.get('thinking_blocks')} "
                      f"json={p.get('json_ok')} order={p.get('order_ok')} picks={p.get('pick_count')} "
                      f"oor={p.get('out_of_range')} err={p.get('error') or p.get('stream_error')}")
                shown_by_q[q] = [cands[x["no"] - 1] for x in p.get("picks", []) if x.get("title")]
            else:
                print("     2차 생략: 후보 0권")
        summary.write("\t".join(str(x) for x in [
            q, args.variant, k.get("t_end"), k.get("input_tokens"), k.get("output_tokens"), k.get("thinking_blocks"),
            kr.get("notBook"), kr.get("titleLike"), kr.get("refersToShown"), "|".join(kr.get("keywords", [])),
            len(cands), p.get("t_first_text"), p.get("t_end"), p.get("input_tokens"), p.get("output_tokens"),
            p.get("thinking_blocks"), p.get("json_ok"), p.get("order_ok"), p.get("pick_count"),
            p.get("out_of_range"), p.get("duplicates"), p.get("text_len_max"),
            round(k.get("cost_usd", 0) + p.get("cost_usd", 0), 6)]) + "\n")
        summary.flush(); jsonl.flush()
    print(f"total cost: ${total_cost:.4f} for {len(nums)} questions")
    jsonl.close(); summary.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
