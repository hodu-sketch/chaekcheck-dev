"""Splits the books to judge (pool.json minus what judged.json already has) into batch files judge-in-N.json of about
`per` books each, whole questions kept together. judged.json: {question id: {book id: grade}} (made by judge_merge.py)."""
import json
import os
import sys

HERE = os.environ.get("AISEARCH_EVAL_DIR") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "data", "aisearch-eval")
per = int(sys.argv[1]) if len(sys.argv) > 1 else 500
questions = {str(q["id"]): q["q"] for q in json.load(open(os.path.join(HERE, "eval_questions.json"), encoding="utf-8"))}
pool = json.load(open(os.path.join(HERE, "pool.json")))
books = json.load(open(os.path.join(HERE, "pool_books.json"), encoding="utf-8"))
judged = json.load(open(os.path.join(HERE, "judged.json"))) if os.path.exists(os.path.join(HERE, "judged.json")) else {}

for name in os.listdir(HERE):
    if name.startswith("judge-in-") or name.startswith("judge-out-"):
        os.remove(os.path.join(HERE, name))
batches, cur, size = [], [], 0
for qid in sorted(pool, key=int):
    todo = [b for b in pool[qid] if str(b) not in judged.get(qid, {})]
    if not todo:
        continue
    item = {"id": int(qid), "question": questions[qid],
            "books": [{"bookId": b, "title": books[str(b)]["title"], "author": books[str(b)]["author"],
                       "line": books[str(b)]["line"][:220]} for b in todo]}
    cur.append(item)
    size += len(todo)
    if size >= per:
        batches.append(cur)
        cur, size = [], 0
if cur:
    batches.append(cur)
for i, batch in enumerate(batches, 1):
    json.dump(batch, open(os.path.join(HERE, f"judge-in-{i}.json"), "w", encoding="utf-8"), ensure_ascii=False)
    print(i, len(batch), sum(len(x["books"]) for x in batch))
