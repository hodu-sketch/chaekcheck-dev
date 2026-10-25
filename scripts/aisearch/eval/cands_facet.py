"""Fair re-measure of the detailed dictionary with the 27,227 production books profiled.
P     production (same as cands_more.py P)
PF    P's ranking, then each book in the RRF list gets a bonus per shelf matching the question's facets
      (kw-facets2.json, Sonnet, same dictionary); keywords, core words and the question vector stay as they were
PFw   PF over a wider pool (keyword 100, vector 120) so the shelves can pull in books P never sees
PFx   PF plus two clear filters: fiction=false pushes fiction books to the end; a shelf the question excludes
      (sonnet-E1 exclude) pushes those books to the end
Writes cands-facet.json and adds new books to pool.json / pool_books.json."""
import glob, json, os, sys
sys.argv = [sys.argv[0]]
HERE = os.path.dirname(os.path.abspath(__file__))
exec(open(os.path.join(HERE, "cands_more.py"), encoding="utf-8").read().split("out = {}")[0])
prof = {}
for d in ("profile3", "profile4"):
    for p in glob.glob(os.path.join(HERE, d, "out-*.jsonl")):
        for l in open(p, encoding="utf-8"):
            if l.strip():
                o = json.loads(l); prof[int(o["id"])] = o
F = {x["id"]: x for x in json.load(open(os.path.join(HERE, "kw-facets2.json"), encoding="utf-8"))}
EX = {}
ep = os.path.join(HERE, "qlabel", "sonnet-E1.jsonl")
if os.path.exists(ep):
    for l in open(ep, encoding="utf-8"):
        if l.strip():
            o = json.loads(l); EX[int(str(o["id"])[1:])] = o.get("exclude") or {}
print("profiles", len(prof), "facets", len(F), "exclude labels", len(EX))

def matches(f, o):
    if not o: return 0
    m = 0
    if f.get("field") and o.get("field") in f["field"]: m += 1
    if f.get("subfield") and o.get("subfield") in f["subfield"]: m += 1
    if f.get("fiction") is not None and o.get("fiction") == f["fiction"]: m += 1
    if f.get("genre") and set(o.get("genre") or []) & set(f["genre"]): m += 1
    if f.get("country") and o.get("country") == f["country"]: m += 1
    if f.get("audience") and o.get("audience") == f["audience"]: m += 1
    if f.get("level") and o.get("level") == f["level"]: m += 1
    if f.get("form") and set(o.get("form") or []) & set(f["form"]): m += 1
    return m

def excluded(f, ex, o):
    if not o: return False
    if f.get("fiction") is False and o.get("fiction"): return True
    if o.get("field") in (ex.get("field") or []) or o.get("subfield") in (ex.get("subfield") or []): return True
    if set(o.get("genre") or []) & set(ex.get("genre") or []): return True
    return False

def build_f(q, bonus=0.004, kdepth=50, vdepth=60, filt=False, size=30, k=60):
    qid = q["id"]; b = kw[qid]; f = F.get(qid, {}); ex = EX.get(qid, {})
    chosen, titles = [], set()
    key = named(q["q"])
    if key:
        for n in named_neighbours(key):
            t = title_key(n)
            if len(chosen) < size and t not in titles:
                titles.add(t); chosen.append(n)
    excl = set(chosen)
    kws = [x for x in keyword_match(b["keywords"], b["core"], kdepth + len(excl)) if x not in excl][:kdepth]
    vs = vec_list(qv["q"][qid - 1], excl, vdepth)
    score = {}
    for lst in (kws, vs):
        for r, x in enumerate(lst):
            score[x] = score.get(x, 0) + 1 / (k + r + 1)
    ranked = sorted(score, key=lambda x: -(score[x] + bonus * matches(f, prof.get(x))))
    if filt:
        ranked = [x for x in ranked if not excluded(f, ex, prof.get(x))] + [x for x in ranked if excluded(f, ex, prof.get(x))]
    for x in ranked:
        if len(chosen) >= size: break
        t = title_key(x)
        if t in titles: continue
        titles.add(t); chosen.append(x)
    return chosen

out = {}
for q in questions:
    s = str(q["id"])
    out.setdefault("P", {})[s] = build(q)
    out.setdefault("PF", {})[s] = build_f(q)
    out.setdefault("PFw", {})[s] = build_f(q, kdepth=100, vdepth=120)
    out.setdefault("PFx", {})[s] = build_f(q, filt=True)
json.dump(out, open(os.path.join(HERE, "cands-facet.json"), "w", encoding="utf-8"), ensure_ascii=False)
for v in ("PF", "PFw", "PFx"):
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
