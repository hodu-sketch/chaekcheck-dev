"""Candidate sets of each retrieval variant for the 50 eval questions, from the local copy of aisearch.search_book
(same 30,939 books as production) and foryou.book_neighbour. The keyword match mirrors CandidateQuery: NFKC, lower
case, spaces and format characters removed; keywords of 2+ characters, at most 8; core words at most 4 put first;
substring match in search_text; order core hits, hits, plan library count, book id; books no plan library holds left
out. The plan: libraries ELIGIBLE or OPEN for residence 1114 (scope joinable, as the web sends).
Writes cands.json: {variant: {question id: [book ids in order]}}."""
import csv
import json
import os
import re
import sys
import unicodedata

import numpy as np

HERE = os.environ.get("AISEARCH_EVAL_DIR") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "data", "aisearch-eval")
_REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
_PROJECTS = os.path.dirname(_REPO)  # the folder that holds this repo and chaekcheck-data
ML = os.path.join(_PROJECTS, r"chaekcheck-data\ml")
csv.field_size_limit(10**8)

SPACES = re.compile(r"[\s\u00ad\u200b-\u200f\u2028-\u202e\u2060-\u2064\ufeff]+")


def tidy(text):
    return SPACES.sub("", unicodedata.normalize("NFKC", text or "").lower())


def usable(words, limit):
    out = []
    for w in words:
        t = tidy(w)
        if len(t) >= 2 and t not in out:
            out.append(t)
        if len(out) == limit:
            break
    return out


libs = json.load(open(os.path.join(HERE, "libs-1114.json"), encoding="utf-8"))["libraries"]
plan = {lib["id"] for lib in libs if lib["eligibility"] in ("ELIGIBLE", "OPEN")}

books = {}
for row in csv.reader(open(os.path.join(HERE, "sb.csv"), encoding="utf-8")):
    bid = int(row[0])
    held = sum(1 for x in row[5].split(",") if x and int(x) in plan)
    books[bid] = {"title": row[1], "author": row[2], "line": row[3], "text": row[4], "plan": held}
order_ids = sorted(books)

neighbours = {}
for row in csv.reader(open(os.path.join(HERE, "nb.csv"), encoding="utf-8")):
    neighbours[int(row[0])] = [int(x) for x in row[1].split(",") if x]


def keyword_match(kw, core, limit, exclude=()):
    core_w = usable(core, 4)
    words = list(dict.fromkeys(core_w + usable(kw, 8)))
    ex = usable(exclude, 4)
    rows = []
    for bid in order_ids:
        b = books[bid]
        if b["plan"] == 0:
            continue
        t = b["text"]
        hits = [w for w in words if w in t]
        if not hits:
            continue
        core_hits = sum(1 for w in core_w if w in t)
        ex_hit = 1 if any(w in t for w in ex) else 0
        rows.append((ex_hit, -core_hits, -len(hits), -b["plan"], bid))
    rows.sort()
    return [r[-1] for r in rows[:limit]]


def title_key(bid):
    return re.sub(r"[^\w]", "", tidy(books[bid]["title"]))


def dedupe(ids, limit):
    seen, out = set(), []
    for bid in ids:
        k = title_key(bid)
        if k in seen:
            continue
        seen.add(k)
        out.append(bid)
        if len(out) == limit:
            break
    return out


def with_neighbours(ids, extra, top=5):
    """ids, then neighbours of the first `top` ids taken round robin by rank (plan-held, not already in)."""
    out = list(ids)
    lists = [neighbours.get(b, []) for b in ids[:top]]
    added, rank = 0, 0
    while added < extra and rank < 20:
        for lst in lists:
            if rank < len(lst):
                n = lst[rank]
                if n in books and books[n]["plan"] > 0 and n not in out:
                    out.append(n)
                    added += 1
                    if added == extra:
                        break
        rank += 1
    return out


# vectors
vec_ids, vecs = [], []
for name in ("pool-30000-intro", os.path.join("foryou", "vectors")):
    part_ids = json.load(open(os.path.join(ML, name + ".ids.json")))
    part = np.load(os.path.join(ML, name + ".npy"))
    seen = set(vec_ids)
    keep = [i for i, b in enumerate(part_ids) if b in books and books[b]["plan"] > 0 and b not in seen]
    vec_ids += [part_ids[i] for i in keep]
    vecs.append(part[keep])
matrix = np.vstack(vecs)
qvec = np.load(os.path.join(HERE, "qvec.npy"))


def vector(qid, limit):
    scores = matrix @ qvec[qid - 1]
    return [vec_ids[i] for i in np.argsort(-scores)[:limit * 2]]


def rrf(lists, limit, k=60):
    score = {}
    for lst in lists:
        for rank, bid in enumerate(lst):
            score[bid] = score.get(bid, 0) + 1 / (k + rank + 1)
    return [b for b, _ in sorted(score.items(), key=lambda x: (-x[1], x[0]))][:limit]


questions = json.load(open(os.path.join(HERE, "eval_questions.json"), encoding="utf-8"))
kw_base = {x["id"]: x["out"] for x in json.load(open(os.path.join(HERE, "kw-base.json"), encoding="utf-8"))}
kw_ex = {x["id"]: x["out"] for x in json.load(open(os.path.join(HERE, "kw-exclude.json"), encoding="utf-8"))}

variants = {}


def put(name, qid, ids):
    variants.setdefault(name, {})[str(qid)] = ids


for q in questions:
    qid = q["id"]
    b = kw_base[qid]
    e = kw_ex[qid]
    kw30 = keyword_match(b["keywords"], b["core"], 30)
    kw50 = keyword_match(b["keywords"], b["core"], 50)
    put("A 지금(낱말 30)", qid, kw30)
    put("B 낱말 40", qid, kw50[:40])
    put("C 낱말 50", qid, kw50)
    put("D 낱말 30 같은 제목 하나만", qid, dedupe(keyword_match(b["keywords"], b["core"], 60), 30))
    put("E 낱말 30 + 이웃 10", qid, with_neighbours(kw30, 10))
    put("F 부정어 빼기(낱말 30)", qid, keyword_match(e["keywords"], e["core"], 30, e.get("exclude", [])))
    v = vector(qid, 30)
    put("G 벡터만 30", qid, dedupe(v, 30))
    put("H 낱말+벡터 섞기 30", qid, dedupe(rrf([kw50, v], 60), 30))
    put("I 낱말+벡터 섞기 40", qid, dedupe(rrf([kw50, v], 80), 40))

json.dump(variants, open(os.path.join(HERE, "cands.json"), "w", encoding="utf-8"), ensure_ascii=False)
for name, per in variants.items():
    sizes = [len(v) for v in per.values()]
    print(name, "avg", round(sum(sizes) / len(sizes), 1), "empty", sum(1 for s in sizes if s == 0))
pool = {}
for per in variants.values():
    for qid, ids in per.items():
        pool.setdefault(qid, set()).update(ids)
print("pool to judge", sum(len(s) for s in pool.values()))
json.dump({qid: sorted(s) for qid, s in pool.items()}, open(os.path.join(HERE, "pool.json"), "w"))
json.dump({str(b): {"title": books[b]["title"], "author": books[b]["author"], "line": books[b]["line"]}
           for s in pool.values() for b in s}, open(os.path.join(HERE, "pool_books.json"), "w", encoding="utf-8"),
          ensure_ascii=False)
print("plan libraries", len(plan), "books held", sum(1 for b in books.values() if b["plan"] > 0))
