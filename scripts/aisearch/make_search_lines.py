"""AI 검색 찾기용 한 줄 PC 배치 (사이클 24 2단계 방식, 빌드·CI 밖, 손으로 돌림).

보유 도서관 많은 3만 권(사용자 2026-10-25 "보유량 많은 3만 권 … 로컬용 한 줄 배치 하고 운영 번호에 맞춰줘")의
책 소개 원문(PC 데이터 폴더의 TSV, 서버로 보내지 않음)을 Haiku 5.5에 한 권씩 바로 보내(2단계 방식 (가)) 한 줄을 만들고,
`book-texts` 넣기 파일(docs/maintenance/6-aisearch.md 2절)의 `search_line` 줄로 씁니다.

- 키: 환경 변수 CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY (Windows는 사용자 환경 변수에서도 읽음). 출력·저장하지 않음.
- 대상 TSV(머리줄 없음): book_id, title, author, publisher. 소개 TSV(머리줄): book_id, platform, length, intro.
- 소개가 없는 책은 건너뜀(서지만으로 쓰면 지어낼 수 있어서). 소개는 앞 INTRO_MAX_CHARS 자만 보냄.
- 결과는 --out 에 이어 씀. 다시 돌리면 이미 쓴 책은 건너뜀(중단해도 이어짐). 실패한 책은 --failed 에 남음.

사용 예:
  python -I scripts/aisearch/make_search_lines.py --targets T.tsv --intros intro-books.tsv --out lines.tsv --workers 8
  python -I scripts/aisearch/make_search_lines.py ... --limit 20      # 먼저 몇 권만
"""
from __future__ import annotations

import argparse
import concurrent.futures
import csv
import datetime
import json
import os
import random
import sys
import threading
import time
import urllib.error
import urllib.request

BASE_URL = "https://api.anthropic.com"          # https://platform.claude.com/docs/en/api/versioning (2026-10-24)
ANTHROPIC_VERSION = "2026-10-21"
MODEL = "claude-haiku-5-5"
PRICE_IN_PER_MTOK = 0.10
PRICE_OUT_PER_MTOK = 0.50
INTRO_MAX_CHARS = 1200                         # [안] 평균 소개 1,702자, 앞부분에 내용이 몰려 있음
LINE_MAX_CHARS = 300                           # search_book CHECK
MAX_TOKENS = 400
TIMEOUT_S = 30.0

SYSTEM = """너는 전자책 검색 서비스의 색인 작성자다. 책 소개 원문을 읽고, 이 책을 찾는 사람이 검색창에 쓸 만한 말이 들어간 \
찾기용 한 줄을 쓴다.

규칙:
- 한국어 한두 문장, 80~150자.
- 무엇을 다루는 책인지(분야·주제·장르), 어떤 내용인지, 어떤 분위기이거나 누구에게 맞는지를 담는다.
- 사람들이 실제로 쓸 낱말을 쓴다(예: 위로, 성장, 추리, 경제 입문, 육아, 조선 역사, 과학 교양, 연애, 직장 생활).
- 소개에 없는 내용은 지어내지 않는다. 줄거리의 결말은 밝히지 않는다.
- 제목·저자 이름을 되풀이하지 않는다. "이 책은", 광고 문구, 수상·판매량·추천사는 쓰지 않는다.
- 줄바꿈·탭을 쓰지 않는다."""

SCHEMA = {
    "type": "object",
    "properties": {"line": {"type": "string"}},
    "required": ["line"],
    "additionalProperties": False,
}


def api_key() -> str:
    key = os.environ.get("CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY", "")
    if not key and sys.platform == "win32":
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as reg:
                key = winreg.QueryValueEx(reg, "CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY")[0]
        except OSError:
            key = ""
    if not key:
        sys.exit("CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY is not set")
    return key


def call(key: str, title: str, author: str, publisher: str, intro: str) -> tuple[str, int, int]:
    user = f"제목: {title}\n저자: {author or '-'}\n출판사: {publisher or '-'}\n\n소개:\n{intro}"
    body = {
        "model": MODEL,
        "max_tokens": MAX_TOKENS,
        "system": SYSTEM,
        "messages": [{"role": "user", "content": user}],
        "output_config": {"effort": "low", "format": {"type": "json_schema", "schema": SCHEMA}},
    }
    req = urllib.request.Request(f"{BASE_URL}/v1/messages", data=json.dumps(body).encode("utf-8"), method="POST",
                                 headers={"content-type": "application/json", "x-api-key": key,
                                          "anthropic-version": ANTHROPIC_VERSION})
    with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
        answer = json.loads(resp.read().decode("utf-8"))
    if answer.get("stop_reason") != "end_turn":
        raise ValueError(f"stop_reason {answer.get('stop_reason')}")
    text = "".join(block.get("text", "") for block in answer.get("content", []) if block.get("type") == "text")
    line = " ".join(json.loads(text)["line"].split())
    if not 1 <= len(line) <= LINE_MAX_CHARS:
        raise ValueError(f"line length {len(line)}")
    usage = answer.get("usage", {})
    return line, usage.get("input_tokens", 0), usage.get("output_tokens", 0)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--targets", required=True)
    ap.add_argument("--intros", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--failed", default=None)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()
    key = api_key()
    csv.field_size_limit(10_000_000)

    targets = []
    with open(args.targets, encoding="utf-8", newline="") as f:
        for row in csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
            targets.append((int(row[0]), row[1], row[2] if len(row) > 2 else "", row[3] if len(row) > 3 else ""))
    wanted = {t[0] for t in targets}
    intros = {}
    with open(args.intros, encoding="utf-8", newline="") as f:
        reader = csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE)
        next(reader)
        for row in reader:
            book_id = int(row[0])
            if book_id in wanted and book_id not in intros and row[3].strip():
                intros[book_id] = " ".join(row[3].split())[:INTRO_MAX_CHARS]
    done = set()
    if os.path.exists(args.out):
        with open(args.out, encoding="utf-8") as f:
            for row in f:
                cells = row.rstrip("\r\n").split("\t")
                if cells[0] == "search_line":
                    done.add(int(cells[1]))
    else:
        with open(args.out, "w", encoding="utf-8", newline="") as f:
            f.write("kind\tbook_id\tsource\tmade_on\ttext\n")
    todo = [t for t in targets if t[0] in intros and t[0] not in done]
    if args.limit:
        todo = todo[:args.limit]
    print(f"targets {len(targets)}, with intro {len(intros)}, done {len(done)}, to do {len(todo)}", flush=True)

    made_on = datetime.date.today().isoformat()
    lock = threading.Lock()
    stats = {"ok": 0, "failed": 0, "in": 0, "out": 0}
    failed_path = args.failed or args.out + ".failed.tsv"
    started = time.monotonic()

    def work(target):
        book_id, title, author, publisher = target
        for attempt in range(6):
            try:
                line, tin, tout = call(key, title, author, publisher, intros[book_id])
                with lock:
                    with open(args.out, "a", encoding="utf-8", newline="") as f:
                        f.write(f"search_line\t{book_id}\tintro\t{made_on}\t{line}\n")
                    stats["ok"] += 1
                    stats["in"] += tin
                    stats["out"] += tout
                return
            except urllib.error.HTTPError as e:
                if e.code in (429, 500, 502, 503, 529):
                    time.sleep(min(60, 2 ** attempt + random.random()))
                    continue
                reason = f"http {e.code}"
                break
            except (urllib.error.URLError, TimeoutError, OSError) as e:
                time.sleep(min(60, 2 ** attempt + random.random()))
                reason = type(e).__name__
                continue
            except (ValueError, KeyError, json.JSONDecodeError) as e:
                reason = f"bad output: {e}"
                if attempt >= 1:
                    break
        else:
            reason = "retries"
        with lock:
            stats["failed"] += 1
            with open(failed_path, "a", encoding="utf-8") as f:
                f.write(f"{book_id}\t{reason}\n")

    with concurrent.futures.ThreadPoolExecutor(args.workers) as pool:
        for i, _ in enumerate(pool.map(work, todo), 1):
            if i % 500 == 0 or i == len(todo):
                with lock:
                    cost = stats["in"] * PRICE_IN_PER_MTOK / 1e6 + stats["out"] * PRICE_OUT_PER_MTOK / 1e6
                    print(f"{i}/{len(todo)} ok {stats['ok']} failed {stats['failed']} tokens {stats['in']}/"
                          f"{stats['out']} ${cost:.3f} {time.monotonic() - started:.0f}s", flush=True)


if __name__ == "__main__":
    main()
