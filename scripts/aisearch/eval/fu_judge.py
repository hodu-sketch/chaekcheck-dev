"""Follow-up test, step after the second call: cards per variant (fit >= 2, at most 8, as production shows them) from
fu/pk/out-*.json through fu/pk/map.json, then the (follow-up, book) pairs no judge has graded yet, 25 per file, as
fu/jd/in-NN.json for Sonnet (fu/JUDGE_RULES.md). With argv "score": reads fu/jd/out-*.json and prints the scores.
Writes fu/cards.json."""
import glob, json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__)); FD = os.path.join(HERE, "fu")
M = json.load(open(os.path.join(FD, "pk", "map.json")))
picks = {}
for p in glob.glob(os.path.join(FD, "pk", "out-*.json")):
    for o in json.load(open(p, encoding="utf-8")): picks[o["id"]] = o["picks"]
msgs = {}
for p in glob.glob(os.path.join(FD, "pk", "in-*.json")):
    for o in json.load(open(p, encoding="utf-8")): msgs[o["id"]] = o
cands = json.load(open(os.path.join(FD, "cands.json")))
FU = {f["id"]: f for f in json.load(open(os.path.join(FD, "all.json"), encoding="utf-8"))}
B = {b["id"]: b for b in json.load(open(os.path.join(FD, "base.json"), encoding="utf-8"))}
pb = json.load(open(os.path.join(HERE, "pool_books.json"), encoding="utf-8"))
import csv
books = {int(r[0]): {"title": r[1], "author": r[2], "line": r[3]} for r in csv.reader(open(os.path.join(HERE, "sb.csv"), encoding="utf-8"))}
VARS = ["H", "M4", "M6", "M7", "M8"]
cards, missing = {v: {} for v in VARS}, 0
for fid in M["sel"]:
    for v in VARS:
        key = M["map"].get(v, {}).get(fid)
        if key is None: cards[v][fid] = []; continue          # no candidates: blocked (no second call)
        if key not in picks: missing += 1; cards[v][fid] = None; continue
        ids = cands["M8" if v == "M8" else v][fid]
        cards[v][fid] = [ids[p["no"] - 1] for p in picks[key] if p.get("fit", 0) >= 2 and 1 <= p["no"] <= len(ids)][:8]
json.dump(cards, open(os.path.join(FD, "cards.json"), "w"), indent=0)
print("missing second calls", missing)
jp = os.path.join(FD, "judged.json"); J = json.load(open(jp)) if os.path.exists(jp) else {}
for p in glob.glob(os.path.join(FD, "jd", "out-*.json")):
    J.update(json.load(open(p, encoding="utf-8")))
json.dump(J, open(jp, "w"))
if sys.argv[1:] == ["score"]:
    from collections import defaultdict
    kinds = ["refers", "modify", "shift", "new", "chat"]
    for v in VARS:
        row = {}
        for k in kinds:
            ids = [f for f in M["sel"] if FU[f]["kind"] == k]
            if k == "chat":
                row[k] = "0장 %d/%d" % (sum(1 for f in ids if not cards[v][f]), len(ids)); continue
            g = [J.get(f"{f}|{b}") for f in ids for b in (cards[v][f] or [])]
            g = [x for x in g if x is not None]
            empty = sum(1 for f in ids if not cards[v][f])
            row[k] = "맞음 %.0f%% 아주 %.0f%% 틀린 %.2f 빈 %d" % (100 * sum(x >= 2 for x in g) / max(len(g), 1), 100 * sum(x == 3 for x in g) / max(len(g), 1), sum(x <= 1 for x in g) / len(ids), empty)
        print(v, row)
    sys.exit()
todo = []
for v in VARS:
    for fid, bs in cards[v].items():
        for b in bs or []:
            k = f"{fid}|{b}"
            if k not in J and k not in [t["key"] for t in todo]:
                f = FU[fid]; base = B[f["base"]]; bk = books.get(b) or pb.get(str(b), {})
                todo.append({"key": k, "q1": base["q1"], "shown": [f"{i + 1}. {s['title']} / {s['author']}" for i, s in enumerate(base["shown"])],
                             "q2": f["q2"], "want": f["want"], "book": {"title": bk.get("title"), "author": bk.get("author"), "line": bk.get("line")}})
os.makedirs(os.path.join(FD, "jd"), exist_ok=True)
for p in glob.glob(os.path.join(FD, "jd", "in-*.json")): os.remove(p)
n = 0
for i in range(0, len(todo), 25):
    n += 1; json.dump(todo[i:i + 25], open(os.path.join(FD, "jd", f"in-{n:02d}.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=0)
print("pairs to judge", len(todo), "files", n)
