"""Shown cards per variant from pick-out-*.json, as AiSearchFlow shows them: number in range, not seen before, fit 2+
(MIN_FIT), at most 8. Scored with judged.json (the judge's 0-3).
- 카드 수: cards per question; 맞음%: cards judged 2+; 아주 맞음%: judged 3; 틀림 카드: cards judged 0-1 per question
- no_match: questions with no card; 전부 맞음: questions whose every card is judged 2+
- 빼달라는 책 보임: negation questions, cards the judge graded 0 (counted in 틀림 too)
- 후보 글자: characters of the second-call message (cost proxy)"""
import glob
import json
import os

import sys

HERE = os.environ.get("AISEARCH_EVAL_DIR") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "data", "aisearch-eval")
RUN = os.path.join(HERE, sys.argv[1]) if len(sys.argv) > 1 else HERE
judged = json.load(open(os.path.join(HERE, "judged.json")))
qtype = {q["id"]: q["type"] for q in json.load(open(os.path.join(HERE, "eval_questions.json"), encoding="utf-8"))}
inputs, outputs = {}, {}
for path in glob.glob(os.path.join(RUN, "pick-in-*.json")):
    for item in json.load(open(path, encoding="utf-8")):
        inputs[(item["variant"], item["id"])] = item
for path in glob.glob(os.path.join(RUN, "pick-out-*.json")):
    tag = os.path.basename(path)[len("pick-out-"):].removesuffix(".json").split("-")[0]
    for item in json.load(open(path, encoding="utf-8")):
        outputs[(tag, item["id"])] = item["picks"]

rows = {}
for (tag, qid), item in sorted(inputs.items()):
    if (tag, qid) not in outputs:
        continue
    shown, seen = [], set()
    for p in outputs[(tag, qid)]:
        no, fit = p.get("no"), p.get("fit", 0)
        if not isinstance(no, int) or no < 1 or no > len(item["ids"]) or no in seen or fit < 2:
            continue
        seen.add(no)
        shown.append(item["ids"][no - 1])
        if len(shown) == 8:
            break
    grades = [judged[str(qid)].get(str(b), 0) for b in shown]
    rows.setdefault(tag, []).append((qid, grades, len(item["message"])))


def summary(lst):
    n = len(lst)
    cards = sum(len(g) for _, g, _ in lst)
    ok = sum(1 for _, g, _ in lst for x in g if x >= 2)
    good = sum(1 for _, g, _ in lst for x in g if x >= 3)
    bad = cards - ok
    return {"질문": n, "카드 수": round(cards / n, 1), "맞음%": round(100 * ok / max(cards, 1)),
            "아주 맞음%": round(100 * good / max(cards, 1)), "맞는 카드": round(ok / n, 1),
            "틀림 카드": round(bad / n, 2), "no_match": sum(1 for _, g, _ in lst if not g),
            "전부 맞음": sum(1 for _, g, _ in lst if g and all(x >= 2 for x in g)),
            "후보 글자": round(sum(c for *_, c in lst) / n)}


out = {}
for tag, lst in sorted(rows.items()):
    out[tag] = {"전체": summary(lst)}
    for t in sorted(set(qtype.values())):
        part = [r for r in lst if qtype[r[0]] == t]
        if part:
            out[tag][t] = summary(part)
    neg = [r for r in lst if qtype[r[0]] == "부정어"]
    out[tag]["빼달라는 책 보임"] = sum(1 for _, g, _ in neg for x in g if x == 0)
json.dump(out, open(os.path.join(RUN, "shown.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
cols = ["질문", "카드 수", "맞는 카드", "맞음%", "아주 맞음%", "틀림 카드", "no_match", "전부 맞음", "후보 글자"]
print("| 변형 | " + " | ".join(cols) + " | 빼달라는 책 보임 |")
for tag in out:
    s = out[tag]["전체"]
    print(f"| {tag} | " + " | ".join(str(s[c]) for c in cols) + f" | {out[tag]['빼달라는 책 보임']} |")
types = ["기존", "부정어", "뜻맞추기", "두주제", "조건", "비슷한책"]
print("\n유형별 맞는 카드 / 맞음%")
print("| 변형 | " + " | ".join(types) + " |")
for tag in out:
    print(f"| {tag} | " + " | ".join(
        f"{out[tag][t]['맞는 카드']} / {out[tag][t]['맞음%']}%" if t in out[tag] else "-" for t in types) + " |")
