"""More variants on top of the production flow (branch claude/aisearch-vector), for all 57 questions:
P      production: named-book neighbours first (NamedBook rule: title right before 같은/비슷/처럼/류의/풍의), then
       RRF(k=60) of keyword 50 and vector 60, one book per title, 30 candidates
P20    the same, 20 candidates (second call -33%)
Pk20   RRF k=20 (top ranks weigh more)
Pauth  at most 2 books by one author among the candidates
Pneg   the exclude words of kw-exclude.json push books holding them to the end, keyword and vector lists both
Phyde  the vector of an imagined ideal book blurb (hyde.json) instead of the question
Pqkw   the vector of "question + first-call keywords"
Writes cands-more.json and adds new books to pool.json / pool_books.json. Needs qvec.npy, qvec-hyde.npy, qvec-qkw.npy."""
import json
import os
import re
import sys

sys.argv = [sys.argv[0]]
HERE = os.environ.get("AISEARCH_EVAL_DIR") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "data", "aisearch-eval")
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "cands.py"), encoding="utf-8").read().split("questions = json.load")[0])

kw = {x["id"]: x["out"] for x in json.load(open(os.path.join(HERE, "kw-base.json"), encoding="utf-8"))}
kw.update({x["id"]: x["out"] for x in json.load(open(os.path.join(HERE, "kw-sim.json"), encoding="utf-8"))})
ex = {x["id"]: x["out"].get("exclude", []) for x in json.load(open(os.path.join(HERE, "kw-exclude.json"), encoding="utf-8"))}
questions = json.load(open(os.path.join(HERE, "eval_questions.json"), encoding="utf-8"))
qv = {"q": np.load(os.path.join(HERE, "qvec.npy")), "hyde": np.load(os.path.join(HERE, "qvec-hyde.npy")),
      "qkw": np.load(os.path.join(HERE, "qvec-qkw.npy"))}


def letters(text):
    return re.sub(r"[^\w]", "", tidy(text))


CUE = re.compile("같은|비슷|처럼|류의|풍의")
PARTICLE = re.compile("(이랑|하고|와|과|랑)$")
by_key = {}
for bid in order_ids:
    key = letters(re.split(r"[(\[.:]", books[bid]["title"], maxsplit=1)[0])
    if len(key) >= 2:
        by_key.setdefault(key, []).append(bid)


def named(question):
    text = letters(question)
    best = None
    for m in CUE.finditer(text):
        before = text[:m.start()]
        for head in (before, PARTICLE.sub("", before)):
            for length in range(min(40, len(head)), 1, -1):
                key = head[len(head) - length:]
                if key in by_key:
                    if best is None or len(key) > len(best):
                        best = key
                    break
    return best


def named_neighbours(key):
    eds = by_key[key]
    near = []
    for e in eds:
        for n in neighbours.get(e, []):
            if len(near) < 20 and n in books and n not in eds and n not in near and key not in letters(
                    re.split(r"[(\[.:]", books[n]["title"], maxsplit=1)[0]):
                near.append(n)
    return [n for n in near if books[n]["plan"] > 0]


def vec_list(v, excluded, limit=60):
    order = np.argsort(-(matrix @ v))
    out = []
    for i in order:
        b = vec_ids[i]
        if b not in excluded:
            out.append(b)
            if len(out) == limit:
                break
    return out


def holds(bid, words):
    return any(w in books[bid]["text"] for w in words)


def build(q, size=30, k=60, vec="q", author_cap=None, neg=False):
    qid = q["id"]
    b = kw[qid]
    chosen, titles = [], set()
    key = named(q["q"])
    if key:
        for n in named_neighbours(key):
            t = title_key(n)
            if len(chosen) < size and t not in titles:
                titles.add(t)
                chosen.append(n)
    excl = set(chosen)
    words = usable(ex.get(qid, []), 4) if neg else []
    kws = [x for x in keyword_match(b["keywords"], b["core"], 50 + len(excl), words) if x not in excl][:50]
    v = qv[vec][qid - 1]
    vs = vec_list(v, excl)
    if neg and words:
        vs = [x for x in vs if not holds(x, words)] + [x for x in vs if holds(x, words)]
    authors = {}
    for x in chosen:
        a = letters(books[x]["author"])
        authors[a] = authors.get(a, 0) + 1
    for x in rrf([kws, vs], 200, k):
        if len(chosen) >= size:
            break
        t = title_key(x)
        a = letters(books[x]["author"])
        if t in titles or (author_cap and a and authors.get(a, 0) >= author_cap):
            continue
        titles.add(t)
        authors[a] = authors.get(a, 0) + 1
        chosen.append(x)
    return chosen


out = {}
for q in questions:
    s = str(q["id"])
    out.setdefault("P", {})[s] = build(q)
    out.setdefault("P20", {})[s] = build(q, size=20)
    out.setdefault("Pk20", {})[s] = build(q, k=20)
    out.setdefault("Pauth", {})[s] = build(q, author_cap=2)
    out.setdefault("Pneg", {})[s] = build(q, neg=True)
    out.setdefault("Phyde", {})[s] = build(q, vec="hyde")
    out.setdefault("Pqkw", {})[s] = build(q, vec="qkw")
json.dump(out, open(os.path.join(HERE, "cands-more.json"), "w", encoding="utf-8"), ensure_ascii=False)

rep = json.load(open(os.path.join(HERE, "replay-on.json"), encoding="utf-8"))
same = [len(set(out["P"][s]) & set(rep[s])) / max(len(set(out["P"][s]) | set(rep[s])), 1) for s in rep]
print("P vs production replay: same books %.2f" % (sum(same) / len(same)))

pool = json.load(open(os.path.join(HERE, "pool.json")))
pb = json.load(open(os.path.join(HERE, "pool_books.json"), encoding="utf-8"))
for per in out.values():
    for s, ids in per.items():
        pool[s] = sorted(set(pool.get(s, [])) | set(ids))
        for x in ids:
            pb.setdefault(str(x), {"title": books[x]["title"], "author": books[x]["author"], "line": books[x]["line"]})
json.dump(pool, open(os.path.join(HERE, "pool.json"), "w"))
json.dump(pb, open(os.path.join(HERE, "pool_books.json"), "w", encoding="utf-8"), ensure_ascii=False)
