"""Two more variants on top of P (cands_more.py), all 57 questions:
Pbm25    a third list: BM25 over title + author + the book's list intro (first 600 characters; intro-books.tsv, PC only),
         Korean split into character bigrams (pg_bigm style), the query = the first-call keywords and core words;
         RRF(k=60) of keyword 50, BM25 60 and vector 60
Pstruct  structured first-call answer (kw-struct.json): its keywords for the keyword list, the vector of
         "target_reader purpose category keywords", difficulty beginner -> books holding 입문/처음/쉽게/초보/기초 first
         within ties of the mix, exclude words pushed to the end
Writes cands-more2.json and adds new books to pool.json / pool_books.json."""
import json
import math
import os
import sys
from collections import Counter, defaultdict

sys.argv = [sys.argv[0]]
HERE = os.environ.get("AISEARCH_EVAL_DIR") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "data", "aisearch-eval")
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "cands_more.py"), encoding="utf-8").read().split("out = {}")[0])

# BM25 over intros
intro = {}
with open(os.path.join(ML, "intro-books.tsv"), encoding="utf-8") as f:
    next(f)
    for line in f:
        p = line.rstrip("\n").split("\t", 3)
        if len(p) == 4:
            intro[int(p[0])] = p[3][:600]


def grams(text):
    t = tidy(text)
    return [t[i:i + 2] for i in range(len(t) - 1)]


docs = {}
df = Counter()
for bid in order_ids:
    if books[bid]["plan"] == 0:
        continue
    g = Counter(grams(books[bid]["title"] + " " + books[bid]["author"] + " " + intro.get(bid, books[bid]["line"])))
    docs[bid] = g
    df.update(g.keys())
N = len(docs)
avg = sum(sum(g.values()) for g in docs.values()) / N
post = defaultdict(list)
for bid, g in docs.items():
    for t, c in g.items():
        post[t].append((bid, c))
lens = {bid: sum(g.values()) for bid, g in docs.items()}


def bm25(words, limit=60, k1=1.2, b=0.75):
    q = Counter(t for w in words for t in grams(w))
    score = defaultdict(float)
    for t in q:
        if t not in df:
            continue
        idf = math.log(1 + (N - df[t] + 0.5) / (df[t] + 0.5))
        for bid, c in post[t]:
            score[bid] += idf * c * (k1 + 1) / (c + k1 * (1 - b + b * lens[bid] / avg))
    return [bid for bid, _ in sorted(score.items(), key=lambda x: (-x[1], x[0]))[:limit]]


struct = {x["id"]: x["out"] for x in json.load(open(os.path.join(HERE, "kw-struct.json"), encoding="utf-8"))}
qv["struct"] = np.load(os.path.join(HERE, "qvec-struct.npy"))
EASY = ["입문", "처음", "쉽게", "초보", "기초"]


def build3(q, mode):
    qid = q["id"]
    chosen, titles = [], set()
    key = named(q["q"])
    if key:
        for n in named_neighbours(key):
            t = title_key(n)
            if len(chosen) < 30 and t not in titles:
                titles.add(t)
                chosen.append(n)
    excl = set(chosen)
    if mode == "bm25":
        b = kw[qid]
        words, exw = b["keywords"], []
        lists = [[x for x in keyword_match(b["keywords"], b["core"], 50 + len(excl)) if x not in excl][:50],
                 [x for x in bm25(b["keywords"] + b["core"], 60 + len(excl)) if x not in excl][:60],
                 vec_list(qv["q"][qid - 1], excl)]
    else:
        s = struct[qid]
        exw = usable(s.get("exclude", []), 4)
        kwl = [x for x in keyword_match(s["keywords"], [], 50 + len(excl), exw) if x not in excl][:50]
        vs = vec_list(qv["struct"][qid - 1], excl)
        if exw:
            vs = [x for x in vs if not holds(x, exw)] + [x for x in vs if holds(x, exw)]
        lists = [kwl, vs]
    mixed = rrf(lists, 200, 60)
    if mode == "struct" and struct[qid].get("difficulty") == "beginner":
        easy = set(x for x in mixed if holds(x, EASY))
        mixed = sorted(mixed, key=lambda x: (0 if x in easy else 1, mixed.index(x) // 5, mixed.index(x)))
    for x in mixed:
        if len(chosen) >= 30:
            break
        t = title_key(x)
        if t in titles:
            continue
        titles.add(t)
        chosen.append(x)
    return chosen


out = {"Pbm25": {}, "Pstruct": {}}
for q in questions:
    out["Pbm25"][str(q["id"])] = build3(q, "bm25")
    out["Pstruct"][str(q["id"])] = build3(q, "struct")
json.dump(out, open(os.path.join(HERE, "cands-more2.json"), "w", encoding="utf-8"), ensure_ascii=False)
pool = json.load(open(os.path.join(HERE, "pool.json")))
pb = json.load(open(os.path.join(HERE, "pool_books.json"), encoding="utf-8"))
for per in out.values():
    for s, ids in per.items():
        pool[s] = sorted(set(pool.get(s, [])) | set(ids))
        for x in ids:
            pb.setdefault(str(x), {"title": books[x]["title"], "author": books[x]["author"], "line": books[x]["line"]})
json.dump(pool, open(os.path.join(HERE, "pool.json"), "w"))
json.dump(pb, open(os.path.join(HERE, "pool_books.json"), "w", encoding="utf-8"), ensure_ascii=False)
print("intros", len(intro), "docs", N)
