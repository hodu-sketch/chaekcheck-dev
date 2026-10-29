"""Cycle 26 step 11 (ADR-0163): the book-texts file that links books to the collected covers uploaded to S3.

Reads the collect PC's covers.sqlite (book_cover, status DONE, file covers/<xx>/<sha1>.jpg) and writes, for every book
whose picture is in the given folders (the ones upload-collected.ps1 finished, its done*.txt), one row
cover_file<TAB><book_id><TAB><TAB><TAB><sha1> after the first row "# export_id=<id>" and the header
(docs/maintenance/8-covers.md 3절). book_id is catalog.book.id of the import the covers were planned from
(meta planned_from), so the export id given must be that import's. A book with two rows keeps the first by priority.
Also writes <out>.keys.txt, the sha1 list, to compare with the S3 listing before the file is put in.

Usage: python -I scripts/covers/cover_file_rows.py <covers.sqlite> <upload log folder> <export id> <out.tsv>
"""
import re
import sqlite3
import sys
from pathlib import Path

FILE = re.compile(r"covers/([0-9a-f]{2})/([0-9a-f]{40})\.jpg")


def main(db_path: str, log_dir: str, export_id: str, out_path: str) -> None:
    uploaded = set()
    for done in Path(log_dir).glob("done*.txt"):
        uploaded.update(line.strip() for line in done.read_text(encoding="utf-8").splitlines() if line.strip())
    db = sqlite3.connect(f"file:{Path(db_path).as_posix()}?mode=ro", uri=True)
    print(db.execute("SELECT value FROM meta WHERE key = 'planned_from'").fetchone()[0])
    rows, keys, seen, skipped, bad = [], set(), set(), 0, 0
    for book_id, file in db.execute(
            "SELECT book_id, file FROM book_cover WHERE status = 'DONE' ORDER BY priority DESC, book_id"):
        match = FILE.fullmatch(file or "")
        if not match:
            bad += 1
            continue
        if match.group(1) not in uploaded:
            skipped += 1
            continue
        if book_id in seen:
            continue
        seen.add(book_id)
        keys.add(match.group(2))
        rows.append(f"cover_file\t{book_id}\t\t\t{match.group(2)}")
    out = Path(out_path)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# export_id={export_id}\n")
        f.write("kind\tbook_id\tsource\tmade_on\ttext\n")
        f.write("\n".join(rows) + "\n")
    Path(str(out) + ".keys.txt").write_text("\n".join(sorted(keys)) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(rows)} rows, {len(keys)} pictures; {len(uploaded)} folders uploaded; "
          f"{skipped} rows of folders not uploaded, {bad} rows without a file name")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    main(*sys.argv[1:])
