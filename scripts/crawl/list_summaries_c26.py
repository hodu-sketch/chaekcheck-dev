"""Cycle 26 step 0-4 (user 2026-10-28 "그래", through plan: fill before deploy 1 the 책첵 summaries of the recommended-list
books that show a list summary now but have none of their own): the same inputs and rules as foryou_blurbs.py (R8-4:
해요체, 80~150 characters, no praise, no ending, no AI words, no 8-character run from the intro, NONE when unknown), but
for a given list of book ids and written by Claude Haiku subagents instead of the Batches API (no API key).

  python -X utf8 scripts/crawl/list_summaries_c26.py prepare <ids.txt> <out dir> [chunk size]
      reads the PC service DB (5432, the same catalog export as production) and the collected DB, writes
      requests.jsonl and chunk-NN.jsonl ({book_id, line, intro}) for the subagents; no request to any site
  python -X utf8 scripts/crawl/list_summaries_c26.py collect <out dir>
      reads answer-NN.jsonl ({book_id, text}) next to the chunks, checks every text with foryou_blurbs.check, writes
      book-texts-list-summaries.tsv (the book-texts header first, kind for_you_blurb) and report.json; books whose text
      broke a rule are listed in retry.jsonl for another round

The PC's .env gives the database name and user; the port is always 5432 (the main folder's database), whatever the
worktree's .env says.
"""
from __future__ import annotations

import datetime as dt
import json
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import foryou_blurbs as fb  # noqa: E402


def connect():
    import psycopg
    e = fb.env()
    return psycopg.connect(host='localhost', port='5432', dbname=e['DB_NAME'], user=e['DB_USERNAME'],
                           password=e.get('DB_PASSWORD', ''), autocommit=True)


def prepare(ids_file: str, out_dir: str, chunk: int) -> int:
    ids = sorted({int(x) for x in Path(ids_file).read_text(encoding='utf-8').split()})
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    db = connect()
    cur = db.cursor()
    cur.execute("""
        SELECT b.id, b.title, b.author_display, b.publisher, b.published_on FROM catalog.book b
        WHERE b.id = ANY(%s) AND NOT EXISTS (SELECT 1 FROM foryou.book_blurb x WHERE x.book_id = b.id)
        ORDER BY b.id""", (ids,))
    books = cur.fetchall()
    cur.execute("""
        SELECT h.book_id, l.collection_library_id, h.item_key FROM catalog.holding h
        JOIN collection.collection_link l ON l.library_id = h.library_id WHERE h.book_id = ANY(%s)""",
                ([b[0] for b in books],))
    holdings = cur.fetchall()
    db.close()
    collected = sqlite3.connect(f'file:{(fb.DATA / "ebook-catalog.sqlite").as_posix()}?mode=ro', uri=True, timeout=120)
    collected.execute('PRAGMA busy_timeout=120000')
    intros: dict[int, str] = {}
    for book_id, lib, item in holdings:
        row = collected.execute('SELECT intro_raw FROM holding WHERE library_id = ? AND item_key = ?',
                                (int(lib), item)).fetchone()
        if row and row[0] and len(row[0]) > len(intros.get(book_id, '')):
            intros[book_id] = row[0]
    collected.close()
    requests = [{'book_id': b[0], 'line': fb.bibliographic(*b[1:5]), 'intro': intros.get(b[0], '')[:fb.INTRO_CHARS]}
                for b in books]
    write_jsonl(out / 'requests.jsonl', requests)
    for n, start in enumerate(range(0, len(requests), chunk), 1):
        write_jsonl(out / f'chunk-{n:02d}.jsonl', requests[start:start + chunk])
    with_intro = sum(1 for r in requests if r['intro'])
    print(json.dumps({'ids': len(ids), 'books': len(requests), 'already_have_one_or_unknown': len(ids) - len(requests),
                      'with_intro': with_intro, 'without_intro': len(requests) - with_intro,
                      'chunks': -(-len(requests) // chunk)}, ensure_ascii=False))
    return 0


def collect(out_dir: str) -> int:
    out = Path(out_dir)
    books = {r['book_id']: r for r in read_jsonl(out / 'requests.jsonl')}
    answers: dict[int, str] = {}
    for path in sorted(out.glob('answer-*.jsonl')):
        for a in read_jsonl(path):
            answers[int(a['book_id'])] = str(a.get('text', '')).strip()
    kept, dropped, retry, samples = [], {}, [], {}
    for book_id, book in sorted(books.items()):
        text = answers.get(book_id)
        why = 'missing' if text is None else fb.check(text, book['intro'])
        if why:
            dropped[why] = dropped.get(why, 0) + 1
            if why != 'none':
                retry.append(book)
            samples.setdefault(why, [])
            if len(samples[why]) < 5:
                samples[why].append({'book_id': book_id, 'book': book['line'], 'text': text})
            continue
        kept.append((book_id, text))
    made_on = dt.date.today().isoformat()
    path = out / 'book-texts-list-summaries.tsv'
    with path.open('w', encoding='utf-8', newline='\n') as f:
        f.write('kind\tbook_id\tsource\tmade_on\ttext\n')
        for book_id, text in kept:
            f.write(f'for_you_blurb\t{book_id}\thaiku-agents\t{made_on}\t{text}\n')
    write_jsonl(out / 'retry.jsonl', retry)
    report = {'requests': len(books), 'kept': len(kept), 'dropped': dropped, 'retry': len(retry), 'file': str(path),
              'dropped_samples': samples}
    (out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'dropped_samples'}, ensure_ascii=False))
    return 0


def write_jsonl(path: Path, rows: list) -> None:
    with path.open('w', encoding='utf-8', newline='\n') as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + '\n')


def read_jsonl(path: Path) -> list:
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    if len(sys.argv) >= 4 and sys.argv[1] == 'prepare':
        sys.exit(prepare(sys.argv[2], sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 100))
    if len(sys.argv) == 3 and sys.argv[1] == 'collect':
        sys.exit(collect(sys.argv[2]))
    sys.exit(__doc__)
