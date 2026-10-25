"""Similar-book questions (ids 49-57, "○○ 같은 책"): candidates of A (now), H (keyword + vector) and two new ways:
S  기준 책 이웃: find the book the question names (the longest search-book title, letters only, found in the question;
   all editions with that title count as the book), take its KURE neighbours (plan-held, not the book itself),
   then fill to 30 with the keyword candidates.
SH 기준 책 이웃 + 섞기: the same neighbours first, then the H list.
Without a named book in the search books (사피엔스), S and SH are A and H.
Writes cands-sim.json and adds the new books to pool.json / pool_books.json."""
import json
import os
import re
import sys

sys.argv = [sys.argv[0]]
HERE = os.environ.get("AISEARCH_EVAL_DIR") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "data", "aisearch-eval")
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "cands.py"), encoding="utf-8").read().split("questions = json.load")[0])

kw = {x["id"]: x["out"] for x in json.load(open(os.path.join(HERE, "kw-base.json"), encoding="utf-8"))}
kw.update({x["id"]: x["out"] for x in json.load(open(os.path.join(HERE, "kw-sim.json"), encoding="utf-8"))})
questions = [q for q in json.load(open(os.path.join(HERE, "eval_questions.json"), encoding="utf-8"))
             if q["type"] == "비슷한책"]


def letters(text):
    return re.sub(r"[^\w]", "", tidy(text))


by_title = {}
for bid, b in books.items():
    key = letters(re.split(r"[(\[.:]", b["title"])[0])
    if len(key) >= 2:
        by_title.setdefault(key, []).append(bid)


def named_book(question):
    q = letters(question)
    found = [k for k in by_title if k in q]
    if not found:
        return None, []
    key = max(found, key=len)
    return key, by_title[key]


out, notes = {}, {}
for q in questions:
    qid = q["id"]
    b = kw[qid]
    kw30 = keyword_match(b["keywords"], b["core"], 30)
    kw50 = keyword_match(b["keywords"], b["core"], 50)
    h = dedupe(rrf([kw50, vector(qid, 30)], 60), 30)
    key, editions = named_book(q["q"])
    notes[qid] = {"named": key, "editions": editions}
    near = []
    for e in editions:
        for n in neighbours.get(e, []):
            if n in books and books[n]["plan"] > 0 and n not in editions and n not in near \
                    and letters(books[n]["title"]).find(key or "\0") < 0:
                near.append(n)
    near = near[:20]
    out.setdefault("A 지금(낱말 30)", {})[str(qid)] = kw30
    out.setdefault("H 낱말+벡터 섞기 30", {})[str(qid)] = h
    s = list(near) + [x for x in kw30 if x not in near and x not in editions]
    out.setdefault("S 기준 책 이웃 + 낱말", {})[str(qid)] = dedupe(s, 30)
    sh = list(near) + [x for x in h if x not in near and x not in editions]
    out.setdefault("SH 기준 책 이웃 + 섞기", {})[str(qid)] = dedupe(sh, 30)
json.dump(out, open(os.path.join(HERE, "cands-sim.json"), "w", encoding="utf-8"), ensure_ascii=False)
for qid, n in notes.items():
    print(qid, n["named"], len(n["editions"]), "neighbours used",
          len([x for x in out["S 기준 책 이웃 + 낱말"][str(qid)] if x not in out["A 지금(낱말 30)"][str(qid)]]))

pool = json.load(open(os.path.join(HERE, "pool.json")))
pb = json.load(open(os.path.join(HERE, "pool_books.json"), encoding="utf-8"))
for per in out.values():
    for qid, ids in per.items():
        pool[qid] = sorted(set(pool.get(qid, [])) | set(ids))
        for x in ids:
            pb[str(x)] = {"title": books[x]["title"], "author": books[x]["author"], "line": books[x]["line"]}
json.dump(pool, open(os.path.join(HERE, "pool.json"), "w"))
json.dump(pb, open(os.path.join(HERE, "pool_books.json"), "w", encoding="utf-8"), ensure_ascii=False)
