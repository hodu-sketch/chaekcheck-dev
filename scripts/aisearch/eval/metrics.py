"""Merges judge-out-*.json into judged.json, then scores every variant of cands.json.
Per question: rel = candidates graded 2+, good = graded 3. Over the 50 questions:
- 맞는 후보(평균): rel per question, the books the second call can choose from
- 8권 채움: questions with 8+ relevant candidates (a full answer is possible)
- 0권: questions with no relevant candidate (no_match)
- 정밀도: rel / candidates
- 찾은 비율: rel / every relevant book any variant found for that question (relative recall)
- 앞 8권 맞음: relevant among the first 8 candidates (what no_text would show)
Also by question type. Writes metrics.json and prints a table."""
import glob
import json
import os
import sys

HERE = os.environ.get("AISEARCH_EVAL_DIR") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "data", "aisearch-eval")
judged = json.load(open(os.path.join(HERE, "judged.json"))) if os.path.exists(os.path.join(HERE, "judged.json")) else {}
for path in sorted(glob.glob(os.path.join(HERE, "judge-out-*.json"))):
    for qid, grades in json.load(open(path, encoding="utf-8")).items():
        judged.setdefault(qid, {}).update({str(k): int(v) for k, v in grades.items()})
json.dump(judged, open(os.path.join(HERE, "judged.json"), "w"))

cands = json.load(open(os.path.join(HERE, "cands.json"), encoding="utf-8"))
qtype = {str(q["id"]): q["type"] for q in json.load(open(os.path.join(HERE, "eval_questions.json"), encoding="utf-8"))}
missing = sum(1 for per in cands.values() for qid, ids in per.items() for b in ids if str(b) not in judged.get(qid, {}))
if missing:
    print("not judged yet:", missing)

all_rel = {}
for per in cands.values():
    for qid, ids in per.items():
        all_rel.setdefault(qid, set()).update(b for b in ids if judged.get(qid, {}).get(str(b), 0) >= 2)


def score(per, qids):
    n = len(qids)
    rel = [sum(1 for b in per[q] if judged[q].get(str(b), 0) >= 2) for q in qids]
    good = [sum(1 for b in per[q] if judged[q].get(str(b), 0) >= 3) for q in qids]
    top8 = [sum(1 for b in per[q][:8] if judged[q].get(str(b), 0) >= 2) for q in qids]
    size = sum(len(per[q]) for q in qids)
    recall = [rel[i] / len(all_rel[q]) for i, q in enumerate(qids) if all_rel[q]]
    return {"맞는 후보": round(sum(rel) / n, 1), "아주 맞음": round(sum(good) / n, 1),
            "8권 채움": sum(1 for r in rel if r >= 8), "0권": sum(1 for r in rel if r == 0),
            "정밀도%": round(100 * sum(rel) / size), "찾은 비율%": round(100 * sum(recall) / len(recall)),
            "앞8권 맞음": round(sum(top8) / n, 1), "질문 수": n}


out = {}
qids_all = sorted(next(iter(cands.values())), key=int)
types = sorted(set(qtype.values()), key=lambda t: min(int(q) for q in qids_all if qtype[q] == t))
for name, per in cands.items():
    out[name] = {"전체": score(per, qids_all)}
    for t in types:
        out[name][t] = score(per, [q for q in qids_all if qtype[q] == t])
json.dump(out, open(os.path.join(HERE, "metrics.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)

cols = ["맞는 후보", "아주 맞음", "8권 채움", "0권", "정밀도%", "찾은 비율%", "앞8권 맞음"]
print("| 변형 | " + " | ".join(cols) + " |")
for name in cands:
    s = out[name]["전체"]
    print(f"| {name} | " + " | ".join(str(s[c]) for c in cols) + " |")
print("\n유형별 맞는 후보(평균)")
print("| 변형 | " + " | ".join(f"{t}({out[next(iter(out))][t]['질문 수']})" for t in types) + " |")
for name in cands:
    print(f"| {name} | " + " | ".join(str(out[name][t]["맞는 후보"]) for t in types) + " |")
