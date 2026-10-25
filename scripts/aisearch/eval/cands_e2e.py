"""End to end with three first calls, all 57 questions; candidates as production P plus, where shelves are given,
PFx (shelf bonus + clear filters, cands_facet.py):
P   Haiku keywords/core (kw-base, kw-sim), no shelves             = production
H   Haiku keywords/core + Haiku shelves (qlabel/haiku-E1)
HM  Haiku keywords/core + MLP shelves (facets-mlp.json)            only the shelf part replaced
M   question words as keywords (no LLM) + MLP shelves
L   question words as keywords (no LLM) + LR shelves
MT  question words + the nearest book-profile topics as keywords (kw-topics.json, no LLM) + MLP shelves
Question words: the question split on spaces and punctuation, common endings and request words dropped; core empty.
Writes cands-e2e.json and adds new books to pool.json / pool_books.json."""
import json, os, re, sys
sys.argv = [sys.argv[0]]
HERE = os.path.dirname(os.path.abspath(__file__))
exec(open(os.path.join(HERE, "cands_facet.py"), encoding="utf-8").read().split("\nout = {}\n")[0])
kw_haiku = dict(kw)
def load(p): return [json.loads(l) for l in open(p, encoding="utf-8") if l.strip()]
H = {int(o["id"][1:]): o for o in load(os.path.join(HERE, "qlabel", "haiku-E1.jsonl"))}
MLPF = {o["id"]: o for o in json.load(open(os.path.join(HERE, "facets-mlp.json"), encoding="utf-8"))}
KT = {int(k): v for k, v in json.load(open(os.path.join(HERE, "kw-topics.json"), encoding="utf-8")).items()}
LRF = {o["id"]: o for o in json.load(open(os.path.join(HERE, "facets-lr.json"), encoding="utf-8"))}
STOP = set("책 도서 관련 사람 처음 위한 대한 읽는 추천 추천해줘 추천해 알려줘 찾아줘 읽을 읽기 좋은 있어 있나요 있을까요 싶어 싶은 거 것 걸 같은 비슷한 처럼 주세요 해줘 좀 그냥 말고 빼고 없는".split())
END = re.compile("(으로|에서|에게|한테|이랑|하고|까지|부터|처럼|같은|이나|에|의|을|를|이|가|은|는|와|과|로|도|만|요)$")
def words(q):
    out = []
    for t in re.split(r"[\s,.?!·~\"'()]+", q):
        t = END.sub("", t)
        if len(t) >= 2 and t not in STOP and t not in out:
            out.append(t)
    return out[:8]
def facets(src, qid):
    o = src.get(qid) or {}
    return {k: o.get(k) for k in ("field", "subfield", "fiction", "genre", "country", "audience", "level", "form")}, (o.get("exclude") or {})
out = {}
for q in questions:
    qid = q["id"]; s = str(qid)
    kw.clear(); kw.update(kw_haiku)
    out.setdefault("P", {})[s] = build(q)
    for tag, src, kwsrc in (("H", H, "haiku"), ("HM", MLPF, "haiku"), ("M", MLPF, "words"), ("L", LRF, "words"), ("MT", MLPF, "topics")):
        kw.clear(); kw.update(kw_haiku)
        if kwsrc == "words":
            kw[qid] = {"keywords": words(q["q"]), "core": []}
        elif kwsrc == "topics":
            kw[qid] = {"keywords": KT[qid]["keywords"], "core": []}
        f, ex = facets(src, qid); F[qid] = f; EX[qid] = ex
        out.setdefault(tag, {})[s] = build_f(q, filt=True)
json.dump(out, open(os.path.join(HERE, "cands-e2e.json"), "w", encoding="utf-8"), ensure_ascii=False)
print("question words, examples:", [words(q["q"]) for q in questions[:4]])
for v in ("H", "HM", "M", "L", "MT"):
    same = sum(len(set(out[v][s]) & set(out["P"][s])) for s in out["P"]) / sum(len(out["P"][s]) for s in out["P"])
    print(v, "books shared with P: %.0f%%" % (100 * same))
pool = json.load(open(os.path.join(HERE, "pool.json")))
pb = json.load(open(os.path.join(HERE, "pool_books.json"), encoding="utf-8"))
for per in out.values():
    for s, ids in per.items():
        pool[s] = sorted(set(pool.get(s, [])) | set(ids))
        for x in ids:
            pb.setdefault(str(x), {"title": books[x]["title"], "author": books[x]["author"], "line": books[x]["line"]})
json.dump(pool, open(os.path.join(HERE, "pool.json"), "w"))
json.dump(pb, open(os.path.join(HERE, "pool_books.json"), "w", encoding="utf-8"), ensure_ascii=False)
J = json.load(open(os.path.join(HERE, "judged.json")))
print("unjudged pairs:", sum(1 for s, ids in pool.items() for x in ids if str(x) not in J.get(s, {})))
