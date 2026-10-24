"""1단계 첫 실행용: 저장소 목록 파일에서 Haiku에 줄 책(제목·저자) TSV를 만듭니다.

책 번호는 임시(줄 차례)입니다. 실제 번호는 수집을 반영한 뒤 2단계 TSV의 book_id를 씁니다
(사용자 결정 2026-10-24, docs/plan-feedback.md `# 사이클 24` "1단계").
같은 ISBN(없으면 같은 제목)은 한 줄로 합칩니다. 사서 목록은 저자 칸이 없어 비워 둡니다.

  python scripts/aisearch/repo_list_books.py OUT.tsv
"""
import csv
import sys
from collections import Counter

ROOT_LISTS = "scripts/crawl/lists/"
SUMMARIES = "backend/src/main/resources/picks/ai-summaries.tsv"


def read(path):
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def main(out):
    rows = {}

    def add(list_key, title, author, isbn):
        title, author, isbn = (title or "").strip(), (author or "").strip(), (isbn or "").strip()
        if not title:
            return
        key = isbn or "t:" + title
        if key in rows:
            rows[key]["lists"].add(list_key)
            if not rows[key]["author"] and author:
                rows[key]["author"] = author
        else:
            rows[key] = {"title": title, "author": author, "lists": {list_key}}

    for name in ("kaist", "leedongjin", "nobel"):
        for r in read(ROOT_LISTS + name + ".tsv"):
            add(name, r.get("pinned_title") or r["title"], r.get("pinned_author") or r["author"],
                r.get("pinned_isbn13") or r["isbn13"])
    for r in read(ROOT_LISTS + "snu100.tsv"):
        add("snu100", r.get("pinned_title") or r["title"], r.get("pinned_author") or r["author"], r.get("pinned_isbn13"))
    for r in read(SUMMARIES):
        if r["list"] == "librarian":
            add("librarian", r["title"], "", r["isbn13"])
    with open(out, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        w.writerow(["book_id", "title", "author", "line"])
        for i, v in enumerate(rows.values(), 1):
            w.writerow([i, v["title"], v["author"], ""])
    c = Counter(k for v in rows.values() for k in v["lists"])
    print("books", len(rows), dict(c), "no author", sum(1 for v in rows.values() if not v["author"]))


if __name__ == "__main__":
    main(sys.argv[1])
