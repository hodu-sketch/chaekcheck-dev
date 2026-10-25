"""Scores the shown cards of more/ (cands_more.py variants), all 57 questions and by type: right %, exact %, wrong cards,
all-right questions, no answer, different authors among the cards, second-call input characters."""
import glob
import json
import os
import re
import sys

HERE = os.environ.get("AISEARCH_EVAL_DIR") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "data", "aisearch-eval")
RUN = os.path.join(HERE, sys.argv[1] if len(sys.argv) > 1 else "more")
J = json.load(open(os.path.join(HERE, "judged.json")))
B = json.load(open(os.path.join(HERE, "pool_books.json"), encoding="utf-8"))
QT = {q["id"]: q["type"] for q in json.load(open(os.path.join(HERE, "eval_questions.json"), encoding="utf-8"))}
inp, out = {}, {}
for p in glob.glob(os.path.join(RUN, "pick-in-*.json")):
    for it in json.load(open(p, encoding="utf-8")):
        inp[(it["variant"], it["id"])] = it
for p in glob.glob(os.path.join(RUN, "pick-out-*.json")):
    tag = os.path.basename(p)[len("pick-out-"):].removesuffix(".json").rsplit("-", 1)[0]
    for it in json.load(open(p, encoding="utf-8")):
        out[(tag, it["id"])] = it["picks"]

rows = {}
missing = []
for (tag, q), it in sorted(inp.items()):
    if (tag, q) not in out:
        missing.append((tag, q))
        continue
    shown, seen = [], set()
    for k in out[(tag, q)]:
        n, f = k.get("no"), k.get("fit", 0)
        if not isinstance(n, int) or n < 1 or n > len(it["ids"]) or n in seen or f < 2:
            continue
        seen.add(n)
        shown.append(it["ids"][n - 1])
        if len(shown) == 8:
            break
    grades = [J[str(q)].get(str(b)) for b in shown]
    authors = {re.sub(r"\W", "", (B[str(b)]["author"] or "?").split(",")[0].split(" 저")[0]) for b in shown}
    rows.setdefault(tag, []).append((q, grades, len(authors), len(it["message"])))
if missing:
    print("missing", len(missing), missing[:5])
unjudged = sum(1 for lst in rows.values() for _, g, _, _ in lst for x in g if x is None)
if unjudged:
    print("unjudged cards", unjudged)


def summ(lst):
    n = len(lst)
    cards = sum(len(g) for _, g, _, _ in lst)
    ok = sum(1 for _, g, _, _ in lst for x in g if (x or 0) >= 2)
    good = sum(1 for _, g, _, _ in lst for x in g if (x or 0) >= 3)
    return {"질문": n, "맞음%": round(100 * ok / max(cards, 1)), "아주 맞음%": round(100 * good / max(cards, 1)),
            "틀린 카드": round((cards - ok) / n, 2), "전부 맞음": sum(1 for _, g, _, _ in lst if g and all((x or 0) >= 2 for x in g)),
            "답 없음": sum(1 for _, g, _, _ in lst if not g), "저자 수": round(sum(a for *_, a, _ in lst) / n, 1),
            "2차 글자": round(sum(c for *_, c in lst) / n)}


order = ["P", "P20", "Pk20", "Pauth", "Pneg", "Phyde", "Pqkw", "Pbm25", "Pstruct"]
res = {}
cols = ["맞음%", "아주 맞음%", "틀린 카드", "전부 맞음", "답 없음", "저자 수", "2차 글자"]
print("| 변형 | " + " | ".join(cols) + " |")
for tag in order:
    if tag not in rows:
        continue
    res[tag] = {"전체": summ(rows[tag])}
    for t in sorted(set(QT.values())):
        part = [r for r in rows[tag] if QT[r[0]] == t]
        if part:
            res[tag][t] = summ(part)
    s = res[tag]["전체"]
    print(f"| {tag} | " + " | ".join(str(s[c]) for c in cols) + " |")
types = ["기존", "부정어", "뜻맞추기", "두주제", "조건", "비슷한책"]
print("\n유형별 아주 맞음% / 틀린 카드")
print("| 변형 | " + " | ".join(types) + " |")
for tag in res:
    print(f"| {tag} | " + " | ".join(f"{res[tag][t]['아주 맞음%']}% / {res[tag][t]['틀린 카드']}" for t in types) + " |")
json.dump(res, open(os.path.join(RUN, "scores.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
