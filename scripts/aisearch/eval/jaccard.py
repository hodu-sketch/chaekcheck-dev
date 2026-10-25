"""How alike two runs of the same variant are: per question, shared shown cards / all shown cards (Jaccard)."""
import glob
import json
import os
import sys


def load(d):
    o = {}
    for p in glob.glob(os.path.join(d, "pick-out-*.json")):
        t = os.path.basename(p)[len("pick-out-"):].removesuffix(".json").split("-")[0]
        for it in json.load(open(p, encoding="utf-8")):
            o[(t, it["id"])] = [k["no"] for k in it["picks"] if k.get("fit", 0) >= 2][:8]
    return o


a, b = load(sys.argv[1]), load(sys.argv[2])
for t in sorted({k[0] for k in b}):
    qs = [q for (tt, q) in b if tt == t and (t, q) in a]
    ov = [len(set(a[(t, q)]) & set(b[(t, q)])) / max(len(set(a[(t, q)]) | set(b[(t, q)])), 1) for q in qs]
    print(t, "same cards between runs (Jaccard) %.2f over %d questions" % (sum(ov) / len(ov), len(qs)))
