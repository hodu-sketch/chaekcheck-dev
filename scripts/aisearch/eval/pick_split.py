"""Second-call inputs for chosen variants, the user message built as Prompts.pickMessages builds it (first questions,
no history): "질문: …\n고를 수 있는 책:\n1. 제목: … / 저자: … / 한 줄: …". Writes pick-in-<tag>-<part>.json:
[{"id", "variant", "message", "ids": [book ids by number]}], 25 questions a file."""
import json
import os

HERE = os.environ.get("AISEARCH_EVAL_DIR") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "data", "aisearch-eval")
TAGS = {"A": "A 지금(낱말 30)", "C": "C 낱말 50", "F": "F 부정어 빼기(낱말 30)", "H": "H 낱말+벡터 섞기 30",
        "I": "I 낱말+벡터 섞기 40"}
cands = json.load(open(os.path.join(HERE, "cands.json"), encoding="utf-8"))
books = json.load(open(os.path.join(HERE, "pool_books.json"), encoding="utf-8"))
questions = {str(q["id"]): q["q"] for q in json.load(open(os.path.join(HERE, "eval_questions.json"), encoding="utf-8"))}

for tag, name in TAGS.items():
    items = []
    for qid in sorted(cands[name], key=int):
        ids = cands[name][qid]
        msg = "질문: " + questions[qid] + "\n고를 수 있는 책:"
        for i, b in enumerate(ids):
            bk = books[str(b)]
            msg += f"\n{i + 1}. 제목: {bk['title']} / 저자: {bk['author'] or '(모름)'}"
            if bk["line"].strip():
                msg += f" / 한 줄: {bk['line']}"
        items.append({"id": int(qid), "variant": tag, "message": msg, "ids": ids})
    for part in range(2):
        json.dump(items[part * 25:(part + 1) * 25],
                  open(os.path.join(HERE, f"pick-in-{tag}-{part + 1}.json"), "w", encoding="utf-8"), ensure_ascii=False)
print("ok")
