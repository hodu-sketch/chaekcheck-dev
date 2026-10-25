"""AI 검색 찾기용 한 줄을 API 키 없이 Claude Code 하위 에이전트(Haiku)로 만들 때 쓰는 나누기·합치기 도구.

사용자 2026-10-25 "멈추고 하위 에이전트로": `make_search_lines.py`(API 바로 호출)를 멈추고, 남은 책을 묶음 파일로 나눠
Claude Code 세션 안의 Haiku 하위 에이전트에게 한 묶음씩 맡깁니다(추천도서 AI 요약과 같은 방식, docs/maintenance/4-picks.md 8절).
지시문은 `make_search_lines.SYSTEM`과 같습니다.

  split: 대상 TSV와 소개 TSV에서 아직 한 줄이 없는 책을 묶음 입력 파일 in-NNN.tsv(book_id, title, author, intro)로 나눔
  join:  하위 에이전트가 쓴 out-NNN.tsv(book_id, line)를 검사해 book-texts 파일(--out)에 이어 씀. 틀린 줄은 버리고 셈

사용 예:
  python -I scripts/aisearch/agent_search_lines.py split --targets T.tsv --intros intro-books.tsv --done lines.tsv --dir D
  python -I scripts/aisearch/agent_search_lines.py join --dir D --out lines.tsv
"""
from __future__ import annotations

import argparse
import csv
import datetime
import glob
import os

INTRO_MAX_CHARS = 600            # [안] 하위 에이전트 한 번에 150권이 들어가게 API 배치(1,200자)의 절반
CHUNK = 150
LINE_MAX_CHARS = 300

# a line about the missing introduction, not about the book (2026-10-25, chunk 012): dropped at join
EMPTY_INTRO_WORDS = ("소개 정보", "소개글에서", "확인되지 않", "확인할 수 없")


def done_ids(path: str) -> set[int]:
    ids = set()
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for row in f:
                cells = row.rstrip("\r\n").split("\t")
                if cells[0] == "search_line":
                    ids.add(int(cells[1]))
    return ids


def clean(text: str) -> str:
    return " ".join(text.split())


def split(args) -> None:
    csv.field_size_limit(10_000_000)
    done = done_ids(args.done)
    targets = []
    with open(args.targets, encoding="utf-8", newline="") as f:
        for row in csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
            if int(row[0]) not in done:
                targets.append((int(row[0]), clean(row[1]), clean(row[2]) if len(row) > 2 else ""))
    wanted = {t[0] for t in targets}
    intros = {}
    with open(args.intros, encoding="utf-8", newline="") as f:
        reader = csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE)
        next(reader)
        for row in reader:
            book_id = int(row[0])
            if book_id in wanted and book_id not in intros and row[3].strip():
                intros[book_id] = clean(row[3])[:INTRO_MAX_CHARS]
    todo = [t for t in targets if t[0] in intros]
    os.makedirs(args.dir, exist_ok=True)
    for n in range(0, len(todo), CHUNK):
        with open(os.path.join(args.dir, f"in-{n // CHUNK:03d}.tsv"), "w", encoding="utf-8", newline="") as f:
            for book_id, title, author in todo[n:n + CHUNK]:
                f.write(f"{book_id}\t{title}\t{author}\t{intros[book_id]}\n")
    print(f"done before {len(done)}, to do {len(todo)}, chunks {(len(todo) + CHUNK - 1) // CHUNK}")


def join(args) -> None:
    done = done_ids(args.out)
    made_on = args.made_on or datetime.date.today().isoformat()
    added = bad = missing = empty = 0
    with open(args.out, "a", encoding="utf-8", newline="") as out:
        for in_path in sorted(glob.glob(os.path.join(args.dir, "in-*.tsv"))):
            asked = set()
            with open(in_path, encoding="utf-8") as f:
                for row in f:
                    asked.add(int(row.split("\t", 1)[0]))
            out_path = in_path.replace("in-", "out-")
            got = set()
            if os.path.exists(out_path):
                with open(out_path, encoding="utf-8") as f:
                    for row in f:
                        cells = row.rstrip("\r\n").split("\t")
                        if len(cells) != 2 or not cells[0].strip().isdigit():
                            bad += 1
                            continue
                        book_id, line = int(cells[0]), clean(cells[1])
                        if line == "-" or any(word in line for word in EMPTY_INTRO_WORDS):
                            # no intro content: the book stays with its title and author only
                            empty += 1
                            continue
                        if book_id not in asked or book_id in done or not 1 <= len(line) <= LINE_MAX_CHARS:
                            bad += 1
                            continue
                        out.write(f"search_line\t{book_id}\tintro\t{made_on}\t{line}\n")
                        done.add(book_id)
                        got.add(book_id)
                        added += 1
            missing += len(asked - got - done)
    print(f"added {added}, no intro content {empty}, bad rows {bad}, asked but missing {missing}, "
          f"total lines {len(done)}")


def main() -> None:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("split")
    s.add_argument("--targets", required=True)
    s.add_argument("--intros", required=True)
    s.add_argument("--done", required=True)
    s.add_argument("--dir", required=True)
    j = sub.add_parser("join")
    j.add_argument("--dir", required=True)
    j.add_argument("--out", required=True)
    j.add_argument("--made-on", default=None)
    args = ap.parse_args()
    split(args) if args.cmd == "split" else join(args)


if __name__ == "__main__":
    main()
