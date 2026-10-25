"""Follow-up test (2nd turn): candidates of the current question for three first calls, as production does them.
H   production: Haiku first call with the earlier turn (fu/h-out-*.jsonl). notBook -> no cards. refersToShown -> the
    books shown last, then their neighbours (nb.csv = foryou.book_neighbour, at most 12), then keyword books; else the
    named-book neighbours, then RRF(keyword 50, vector of the current question alone 60), books shown before left out.
M2  plan 1 + 2, no LLM: routing MLP on the current question (fu/facets.json route); refers -> as H with the question
    words as keywords; else keywords = words of current + earlier question, vector of "earlier + current question",
    shelves (bonus + clear filters, cands_facet.build_f) from the MLP on the joined text.
M3  plan 1 + 2 + 3: as M2, shelves = the earlier question's MLP shelves (facets-mlp.json) with every slot the current
    question's MLP fills put over them (dialogue state); excludes of both.
M4  plan 4 = M3 + new-topic rule: when the current question alone gets a big field from the MLP and none of it is the
    earlier question's field, the earlier question is dropped (words, vector and shelves of the current one only).
M5  M4, but "not a book question" never blocks: the search runs and the second call may pick none.
M7  M6, but the earlier turn is left out when the new-topic rule fires (sent only when needed).
M8  M5 (titles only), the earlier turn left out when the new-topic rule fires.
M6  M5 with a fuller earlier turn for the second call: the shown books numbered, with author and one line.
Also prints the KURE closeness of earlier and current question per kind (a possible second rule, not used).
Writes fu/cands.json {variant: {id: [book ids]}} and fu/pick-in-<variant>-<n>.json for the second call."""
import glob, json, os, re, sys
sys.argv = [sys.argv[0]]
HERE = os.path.dirname(os.path.abspath(__file__))
exec(open(os.path.join(HERE, "cands_facet.py"), encoding="utf-8").read().split("\nout = {}\n")[0])
e2e = open(os.path.join(HERE, "cands_e2e.py"), encoding="utf-8").read()
exec(e2e[e2e.index("STOP = "):e2e.index("def facets(")])
FD = os.path.join(HERE, "fu")
def load(p): return [json.loads(l) for l in open(p, encoding="utf-8") if l.strip()]
FU = json.load(open(os.path.join(FD, "all.json"), encoding="utf-8"))
QV1 = qv["q"]
B = {b["id"]: b for b in json.load(open(os.path.join(FD, "base.json"), encoding="utf-8"))}
HO = {o["id"]: o for p in glob.glob(os.path.join(FD, "h-out-*.jsonl")) for o in load(p)}
FX = json.load(open(os.path.join(FD, "facets.json"), encoding="utf-8"))
Q1F = {o["id"]: o for o in json.load(open(os.path.join(HERE, "facets-mlp.json"), encoding="utf-8"))}
z = np.load(os.path.join(FD, "vec.npz"))
VQ = {str(i): v for i, v in zip(z["ids"], z["q2"])}; VC = {str(i): v for i, v in zip(z["ids"], z["cat"])}
MAX, NB = 30, 12
SLOTS = ("field", "subfield", "fiction", "genre", "country", "audience", "level", "form")

def nearby(last, before):
    out, taken = [], set(before)
    for rank in range(50):
        more = False
        for s in last:
            ids = neighbours.get(s, [])
            if rank >= len(ids): continue
            more = True
            x = ids[rank]
            if x in books and books[x]["plan"] > 0 and x not in taken and len(out) < NB:
                taken.add(x); out.append(x)
        if not more or len(out) >= NB: break
    return out

def refers_cands(last, kws, core):
    chosen = [x for x in last if x in books and books[x]["plan"] > 0][:MAX]
    for x in nearby(last, last):
        if len(chosen) < MAX: chosen.append(x)
    for x in keyword_match(kws, core, MAX * 3):
        if len(chosen) >= MAX: break
        if x not in chosen: chosen.append(x)
    return chosen

def search(q2, kws, core, vec, before, f=None, ex=None):
    chosen, titles = [], set()
    key = named(q2)
    if key:
        for n in named_neighbours(key):
            t = title_key(n)
            if len(chosen) < MAX and t not in titles and n not in before:
                titles.add(t); chosen.append(n)
    excl = set(chosen) | set(before)
    kl = [x for x in keyword_match(kws, core, 50 + len(excl)) if x not in excl][:50]
    vl = vec_list(vec, excl, 60)
    score = {}
    for lst in (kl, vl):
        for r, x in enumerate(lst): score[x] = score.get(x, 0) + 1 / (60 + r + 1)
    f = f or {}; ex = ex or {}
    ranked = sorted(score, key=lambda x: -(score[x] + (0.004 * matches(f, prof.get(x)) if f else 0)))
    if f:
        ranked = [x for x in ranked if not excluded(f, ex, prof.get(x))] + [x for x in ranked if excluded(f, ex, prof.get(x))]
    for x in ranked:
        if len(chosen) >= MAX: break
        t = title_key(x)
        if t in titles: continue
        titles.add(t); chosen.append(x)
    return chosen

def merge(a, b):
    out = {}
    for k in SLOTS:
        v = b.get(k)
        out[k] = v if (v not in (None, "", [])) else a.get(k)
    ex = {}
    for k in ("field", "subfield", "genre"):
        ex[k] = list(dict.fromkeys(((a.get("exclude") or {}).get(k) or []) + ((b.get("exclude") or {}).get(k) or [])))
    return out, ex

out, route_used, newt, close = {"H": {}, "M2": {}, "M3": {}, "M4": {}, "M5": {}, "M6": {}, "M7": {}, "M8": {}}, {}, {}, {}
for fu in FU:
    i = fu["id"]; b = B[fu["base"]]; last = [s["id"] for s in b["shown"]]; q2 = fu["q2"]
    h = HO[i]
    if h.get("notBook"): out["H"][i] = []
    elif h.get("refersToShown"): out["H"][i] = refers_cands(last, h.get("keywords") or [], h.get("core") or [])
    else: out["H"][i] = search(q2, h.get("keywords") or [], h.get("core") or [], VQ[i], last)
    r = FX[i]["route"]; route_used[i] = r
    kws = list(dict.fromkeys(words(q2) + words(b["q1"])))
    cat = FX[i]["cat"]
    f3, ex3 = merge(Q1F[fu["base"]], FX[i]["q2"])
    a2 = FX[i]["q2"]; f1s = Q1F[fu["base"]]
    newt[i] = bool(a2.get("field")) and not (set(a2.get("field") or []) & set(f1s.get("field") or []))
    close[i] = float(QV1[fu["base"] - 1] @ VQ[i])
    for v, (f, ex) in (("M2", ({k: cat.get(k) for k in SLOTS}, cat.get("exclude") or {})), ("M3", (f3, ex3)),
                       ("M4", (f3, ex3)), ("M5", (f3, ex3)), ("M6", (f3, ex3)), ("M7", (f3, ex3)), ("M8", (f3, ex3))):
        if r == "not_book" and v in ("M2", "M3", "M4"): out[v][i] = []
        elif r == "refers_shown": out[v][i] = refers_cands(last, kws, [])
        elif v in ("M4", "M5", "M6", "M7", "M8") and newt[i]: out[v][i] = search(q2, words(q2), [], VQ[i], last, {k: a2.get(k) for k in SLOTS}, a2.get("exclude") or {})
        else: out[v][i] = search(q2, kws, [], VC[i], last, f, ex)
json.dump(out, open(os.path.join(FD, "cands.json"), "w"), indent=0)

def pick_message(q2, ids):
    s = "질문: " + q2 + "\n고를 수 있는 책:"
    for n, x in enumerate(ids, 1):
        bk = books[x]
        s += f"\n{n}. 제목: {bk['title']} / 저자: {bk['author'] or '(모름)'}"
        if bk["line"]: s += f" / 한 줄: {bk['line']}"
    return s
def rich(b):
    s = "보여 준 책:"
    for n, x in enumerate(b["shown"], 1):
        s += f"\n{n}. 제목: {x['title']} / 저자: {x['author'] or '(모름)'}"
        if books.get(x["id"], {}).get("line"): s += f" / 한 줄: {books[x['id']]['line']}"
    return s
for v in out:
    items = []
    for fu in FU:
        ids = out[v][fu["id"]]
        if not ids: continue
        b = B[fu["base"]]
        if v in ("M7", "M8") and newt[fu["id"]] and route_used[fu["id"]] != "refers_shown":
            items.append({"id": fu["id"], "variant": v, "ids": ids, "messages": [{"role": "user", "content": pick_message(fu["q2"], ids)}]})
            continue
        items.append({"id": fu["id"], "variant": v, "ids": ids, "messages": [
            {"role": "user", "content": b["q1"]},
            {"role": "assistant", "content": rich(b) if v in ("M6", "M7") else "보여 준 책:\n" + "\n".join("- " + s["title"] for s in b["shown"])},
            {"role": "user", "content": pick_message(fu["q2"], ids)}]})
    half = (len(items) + 1) // 2
    for n, part in ((1, items[:half]), (2, items[half:])):
        json.dump(part, open(os.path.join(FD, f"pick-in-{v}-{n}.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    print(v, "with candidates", len(items), "of", len(FU))
from collections import Counter
print("H route:", Counter((fu["kind"], "not_book" if HO[fu["id"]].get("notBook") else "refers" if HO[fu["id"]].get("refersToShown") else "search") for fu in FU))
print("M route:", Counter((fu["kind"], route_used[fu["id"]]) for fu in FU))
print("new-topic rule fires:", Counter((fu["kind"], newt[fu["id"]]) for fu in FU))
for k in ("refers", "modify", "shift", "new", "chat"):
    xs = sorted(close[fu["id"]] for fu in FU if fu["kind"] == k)
    if xs: print("closeness", k, "median %.3f  p10 %.3f  p90 %.3f" % (xs[len(xs) // 2], xs[len(xs) // 10], xs[9 * len(xs) // 10]))
