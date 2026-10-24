"""Covers for the 「나에게 맞는 책」 tab (plan cycle 23 request 2026-10-24, user decision #5 "어짜피 보여야 하는 거면
수집하자"): the fetch-covers results for a chosen range of books, counted, fetched first, and packed for the server.

  python -X utf8 scripts/crawl/cover_scope.py count        # covers of the range: have / none (why) / not yet
  python -X utf8 scripts/crawl/cover_scope.py prioritize   # the range goes first in fetch-covers (book_cover.priority)
  python -X utf8 scripts/crawl/cover_scope.py export       # <data>/covers-export/: <sha1>.jpg + book-texts TSV

The range (all read-only: the PC's PostgreSQL SELECT, the data folder's files):
  lists    the recommended lists' books (picks.list_book.book_id; the NLK librarian picks by ISBN)
  pool     the 30,000 books held by the most libraries (<data>/ml/pool-30000.ids.json, the cycle 23 pre-test's pool)
  popular  the top 100 of each popular list of the pre-test (Kyobo general 4 sites, YES24 V1 2 sites;
           data/cycle23-pretest/popular.tsv)

export writes one folder for the server's /srv/chaekcheck/covers/ (flat, <sha1(cover address)>.jpg, 200px JPEG, the
same names as scripts/crawl/lists/covers/ and the same nginx rule, ADR-0144) and book-texts-cover_file.tsv
(kind \\t book_id \\t source \\t made_on \\t text; kind cover_file, text = the 40-character sha1) for the `book-texts`
import mode. book_id is the PC catalogue's (see meta.planned_from in covers.sqlite): after another import, run
`fetch-covers --plan-only` (it renumbers the rows) and export again. Nothing goes to the server from here.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sqlite3
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import crawl_catalog as cc  # noqa: E402
import fetch_covers as fc  # noqa: E402
import recommended_lists as rl  # noqa: E402

REPO = rl.ROOT


def scope(data: Path) -> dict[str, set[int]]:
    import psycopg
    env = rl.read_env(REPO / '.env')
    db = psycopg.connect(host='localhost', port=env.get('DB_PORT', '5432'), dbname=env['DB_NAME'],
                         user=env['DB_USERNAME'], password=env.get('DB_PASSWORD', ''), autocommit=True)
    cur = db.cursor()
    cur.execute("SELECT DISTINCT book_id FROM picks.list_book WHERE book_id IS NOT NULL")
    lists = {r[0] for r in cur.fetchall()}
    cur.execute("""SELECT DISTINCT ON (lb.id) b.id FROM picks.list_book lb JOIN catalog.book b
                   ON b.isbn13 = coalesce(lb.pinned_isbn13, lb.isbn13)
                   WHERE lb.book_id IS NULL ORDER BY lb.id, cardinality(b.library_ids) DESC""")
    lists |= {r[0] for r in cur.fetchall()}
    db.close()
    pool = {int(i) for i in json.loads((data / 'ml' / 'pool-30000.ids.json').read_text())}
    _, rows = rl.read_tsv(REPO / 'data' / 'cycle23-pretest' / 'popular.tsv')
    popular = {int(r['book_id']) for r in rows if r['book_id'] and int(r['rank']) <= 100}
    return {'lists': lists, 'pool': pool, 'popular': popular}


def covers_db(data: Path) -> sqlite3.Connection:
    return fc.open_covers_db(data / 'covers' / 'covers.sqlite')


def rows_of(con: sqlite3.Connection, ids: set[int]) -> dict[int, tuple]:
    out = {}
    for book_id, status, source, cover_url, file, size, candidates, last_error, fetched_at in con.execute(
            'SELECT book_id, status, source, cover_url, file, bytes, candidates, last_error, fetched_at FROM book_cover'):
        if book_id in ids:
            out[book_id] = (status, source, cover_url, file, size, candidates, last_error, fetched_at)
    return out


def why(row) -> str:
    if row is None:
        return 'not planned (a book newer than the plan)'
    status, _, _, _, _, candidates, _, _ = row
    if status == 'DONE':
        return 'have'
    library = [c for c in json.loads(candidates) if c != fc.NLK]
    if status == 'PENDING':
        return 'not yet (pending)'
    return 'none: no library cover address, NLK had none' if not library else 'none: every address failed'


def count(data: Path) -> int:
    sets = scope(data)
    union = set().union(*sets.values())
    con = covers_db(data)
    rows = rows_of(con, union)
    meta = dict(con.execute('SELECT key, value FROM meta'))
    result = {'checked_at': fc.now(), 'planned_from': meta.get('planned_from'), 'sizes': {k: len(v) for k, v in
                                                                                         sets.items()},
              'union': len(union)}
    for name, ids in list(sets.items()) + [('union', union)]:
        result[name] = dict(Counter(why(rows.get(i)) for i in ids))
    by_source: dict[str, list[int]] = defaultdict(list)
    files = {}
    for book_id in union:
        row = rows.get(book_id)
        if row and row[0] == 'DONE':
            by_source[row[1]].append(row[4] or 0)
            files[row[3]] = row[4] or 0
    result['by_source'] = {s: {'covers': len(v), 'avg_kb': round(sum(v) / len(v) / 1024, 1)}
                           for s, v in sorted(by_source.items(), key=lambda kv: -len(kv[1]))}
    result['distinct_files'] = len(files)
    result['files_mb'] = round(sum(files.values()) / 1048576, 1)
    hosts = Counter()
    for book_id in union:
        row = rows.get(book_id)
        if row and row[0] == 'DONE':
            hosts[fc.host_of(row[2])] += 1
    result['by_host_top'] = hosts.most_common(12)
    out = REPO / 'data' / 'cycle23-intro'
    out.mkdir(parents=True, exist_ok=True)
    (out / 'cover-scope.json').write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=1))
    return 0


def prioritize(data: Path) -> int:
    """Marks the range priority 1; fetch-covers takes a server's priority books first (restart it to apply)."""
    sets = scope(data)
    union = set().union(*sets.values())
    con = covers_db(data)
    con.execute('UPDATE book_cover SET priority = 0 WHERE priority <> 0')
    con.executemany('UPDATE book_cover SET priority = 1 WHERE book_id = ?', [(i,) for i in sorted(union)])
    con.commit()
    marked = con.execute('SELECT status, count(*) FROM book_cover WHERE priority = 1 GROUP BY status').fetchall()
    print(f'priority 1: {dict(marked)} (range {len(union)} books)')
    return 0


def export(data: Path) -> int:
    sets = scope(data)
    union = set().union(*sets.values())
    con = covers_db(data)
    rows = rows_of(con, union)
    out = data / 'covers-export'
    out.mkdir(parents=True, exist_ok=True)
    lines = ['kind\tbook_id\tsource\tmade_on\ttext']
    copied = missing = 0
    for book_id in sorted(union):
        row = rows.get(book_id)
        if not row or row[0] != 'DONE':
            continue
        _, source, cover_url, file, _, _, _, fetched_at = row
        sha1 = Path(file).stem
        target = out / f'{sha1}.jpg'
        if not target.exists():
            src = data / file
            if not src.exists():
                missing += 1
                continue
            shutil.copyfile(src, target)
            copied += 1
        lines.append(f'cover_file\t{book_id}\tfetch-covers:{source}\t{(fetched_at or "")[:10]}\t{sha1}')
    (out / 'book-texts-cover_file.tsv').write_text('\n'.join(lines) + '\n', encoding='utf-8', newline='\n')
    files = list(out.glob('*.jpg'))
    print(f'{len(lines) - 1} books linked, {len(files)} files ({sum(f.stat().st_size for f in files) / 1048576:.1f} MB) '
          f'in {out}; {copied} copied now, {missing} files missing')
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('step', choices=['count', 'prioritize', 'export'])
    parser.add_argument('--data-dir', default='')
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    data = Path(args.data_dir) if args.data_dir else cc.ROOT
    return {'count': count, 'prioritize': prioritize, 'export': export}[args.step](data)


if __name__ == '__main__':
    sys.exit(main())
