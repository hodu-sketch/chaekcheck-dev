"""Production flow (replay-on/off.json, titles sent to the second call) against the evaluation's candidate lists:
off vs A (keywords) and S (named book), on vs H (mix) and SH (named book + mix). Same titles share / all titles."""
import json
import os
import sys

D = sys.argv[1]
pb = json.load(open(os.path.join(D, "pool_books.json"), encoding="utf-8"))
c = json.load(open(os.path.join(D, "cands.json"), encoding="utf-8"))
cs = json.load(open(os.path.join(D, "cands-sim.json"), encoding="utf-8"))
qs = {str(q["id"]): q for q in json.load(open(os.path.join(D, "eval_questions.json"), encoding="utf-8"))}


def titles(ids):
    return [pb[str(b)]["title"] for b in ids]


for mode, plain, named in (("off", "A 지금(낱말 30)", "S 기준 책 이웃 + 낱말"), ("on", "H 낱말+벡터 섞기 30", "SH 기준 책 이웃 + 섞기")):
    rep = json.load(open(os.path.join(D, f"replay-{mode}.json"), encoding="utf-8"))
    rows = []
    for qid, got in rep.items():
        if qid in cs[named] and qs[qid]["type"] == "비슷한책":
            want = titles(cs[named][qid])
            kind = "named"
        else:
            want = titles(c[plain][qid])
            kind = "plain"
        a, b = set(got), set(want)
        rows.append((qid, kind, len(a & b) / max(len(a | b), 1), got[:1] == want[:1], len(got)))
    for kind in ("plain", "named"):
        part = [r for r in rows if r[1] == kind]
        if part:
            print(mode, kind, len(part), "questions: same titles %.2f, same first %d/%d, sizes %s" % (
                sum(r[2] for r in part) / len(part), sum(r[3] for r in part), len(part),
                sorted(set(r[4] for r in part))))
    low = sorted(rows, key=lambda r: r[2])[:4]
    print("  lowest:", [(r[0], round(r[2], 2)) for r in low])
