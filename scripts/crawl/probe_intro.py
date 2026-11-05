"""Cycle 23 intro test (plan request to-dev-intro-test-2026-10-24.md, user 2026-10-24 "원문이 없는 책은 다른 데서
소개글을 찾아와봐").

  python -X utf8 scripts/crawl/probe_intro.py count     # 1. books with and without a list intro (no request)
  python -X utf8 scripts/crawl/probe_intro.py nlk       # 2. ④ the NLK ISBN bibliography for 50 books without one

count: every service book (PC PostgreSQL, SELECT only) and its holdings' `holding.intro_raw` in the collected DB
(read-only). A book is A when its first holding (smallest holding id) has an intro, B when only another library's
holding has one (order ①), C when none has. For C, the platforms its holdings come from, and whether every one of its
libraries is still waiting for the running crawl (their intro may still come). The 30,000-book pool of the pre-test
(<data>/ml/pool-30000.ids.json), the 30 seeds (docs/features/foryou/experiments/2026-10-24-pretest/summary.json) and the 1,000-book timing
sample get their longest intro written to <data>/ml/intro-books.tsv (outside git: the text is the sites').
50 C books (seed 23) go to data/cycle23-intro/no-intro-sample.tsv.

nlk: those 50 books in the National Library of Korea ISBN bibliography (www.nl.go.kr/seoji): by ISBN when the book
has one, else by title with the first author's family name; BOOK_INTRODUCTION_URL and BOOK_SUMMARY_URL are read when
present. One request at a time, GAP seconds apart, no retry; never while fetch-covers is asking the same server (the
caller stops it first). The texts go to <data>/ml/nlk-intro-sample.tsv, counts to data/cycle23-intro/nlk.json.
"""
from __future__ import annotations

import argparse
import json
import random
import sqlite3
import statistics
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import crawl_catalog as cc  # noqa: E402
import fetch_covers as fc  # noqa: E402
import recommended_lists as rl  # noqa: E402

REPO = rl.ROOT
OUT = REPO / 'data' / 'cycle23-intro'
GAP = 2.0
SAMPLE = 50
INTRO_CHARS = 300


def service_db():
    import psycopg
    env = rl.read_env(REPO / '.env')
    return psycopg.connect(host='localhost', port=env.get('DB_PORT', '5432'), dbname=env['DB_NAME'],
                           user=env['DB_USERNAME'], password=env.get('DB_PASSWORD', ''))


def target_ids(cur, data: Path) -> dict[int, str]:
    """Books whose intro text is kept for the embedding: pool, seeds, timing sample (same rule as probe_embeddings)."""
    ids = {int(i): 'pool' for i in json.loads((data / 'ml' / 'pool-30000.ids.json').read_text())}
    summary = json.loads((REPO / 'docs' / 'features' / 'foryou' / 'experiments' / '2026-10-24-pretest' / 'summary.json').read_text(encoding='utf-8'))
    for seed in summary['per_seed']:
        ids[int(seed['seed_id'])] = 'seed'
    cur.execute('SELECT count(*), min(id) FROM catalog.book')
    total, lo = cur.fetchone()
    step = max(1, total // 1000)
    cur.execute('SELECT id FROM catalog.book WHERE (id - %s) %% %s = 0 ORDER BY id LIMIT 1000', (lo, step))
    for (book_id,) in cur.fetchall():
        ids.setdefault(book_id, 'sample')
    return ids


def count(data: Path, counts_only: bool = False) -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    collected = sqlite3.connect(f'file:{(data / "ebook-catalog.sqlite").as_posix()}?mode=ro', uri=True, timeout=120)
    collected.execute('PRAGMA busy_timeout=120000')
    started_crawl = fc.crawl_started()
    # libraries the crawl never asks (*.dkyobobook.co.kr, crawl_skip): their holdings never get a list intro
    never = {r[0] for r in collected.execute('SELECT id, base_url, crawl_skip FROM library')
             if r[2] or cc.is_blocked_host(r[1])}
    recrawled = set()
    if started_crawl:
        recrawled = {r[0] for r in collected.execute(
            "SELECT library_id FROM crawl_run WHERE started_at >= ? AND status IN ('SUCCEEDED', 'FAILED', 'MISMATCH')",
            (started_crawl,))}
    db = service_db()
    cur = db.cursor()
    targets = target_ids(cur, data)
    pool = {i for i, k in targets.items() if k == 'pool'}
    lookup = collected.execute
    stats = {'all': Counter(), 'pool': Counter()}
    c_platforms = Counter()
    c_waiting = 0
    c_reason = Counter()
    no_intro: list[tuple] = []
    intros: dict[int, tuple[str, str, int]] = {}
    t0 = time.monotonic()

    def finish(book, rows):
        nonlocal c_waiting
        first = rows[0]
        has_first = first[3] > 0
        has_any = any(r[3] > 0 for r in rows)
        kind = 'A' if has_first else ('B' if has_any else 'C')
        stats['all'][kind] += 1
        if book[0] in pool:
            stats['pool'][kind] += 1
        if kind == 'C':
            c_platforms[' + '.join(sorted({r[2] for r in rows}))] += 1
            if all(r[0] in never for r in rows):
                c_reason['only libraries never asked (*.dkyobobook.co.kr, crawl_skip)'] += 1
            elif started_crawl and any(r[0] not in never and r[0] not in recrawled for r in rows):
                c_reason['a library still waiting for the running crawl'] += 1
            else:
                c_reason['every asked library done, the list has no intro'] += 1
            if started_crawl and all(r[0] not in recrawled for r in rows):
                c_waiting += 1
            no_intro.append(book)
        if book[0] in targets and has_any:
            best = max(rows, key=lambda r: r[3])
            text = lookup('SELECT intro_raw FROM holding WHERE library_id = ? AND item_key = ?', (best[0], best[1])).fetchone()[0]
            intros[book[0]] = (best[2], text, len(text))

    with db.cursor(name='probe_intro') as rows_cur:
        rows_cur.itersize = 50000
        rows_cur.execute("""
            SELECT b.id, b.isbn13, b.title, b.author_display, l.collection_library_id, h.item_key, h.platform
            FROM catalog.book b JOIN catalog.holding h ON h.book_id = b.id
            JOIN collection.collection_link l ON l.library_id = h.library_id
            ORDER BY b.id, h.id""")
        book, rows = None, []
        for book_id, isbn13, title, author, lib, item, platform in rows_cur:
            if book is not None and book_id != book[0]:
                finish(book, rows)
                rows = []
                if stats['all'].total() % 100000 == 0:
                    print(f"{stats['all'].total()} books, {time.monotonic() - t0:.0f}s", flush=True)
            book = (book_id, isbn13, title, author)
            got = lookup('SELECT length(intro_raw) FROM holding WHERE library_id = ? AND item_key = ?',
                         (int(lib), item)).fetchone()
            rows.append((int(lib), item, platform, (got[0] or 0) if got else 0))
        if book is not None:
            finish(book, rows)
    db.rollback()
    if counts_only:
        result = {'checked_at': fc.now(), 'crawl_started': started_crawl, 'libraries_recrawled': len(recrawled),
                  'libraries_never_asked': len(never), 'all': dict(stats['all']), 'pool': dict(stats['pool']),
                  'c_reason': dict(c_reason), 'c_platforms_top': c_platforms.most_common(15),
                  'seconds': round(time.monotonic() - t0)}
        (OUT / 'counts-reasons.json').write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
        print(json.dumps(result, ensure_ascii=False, indent=1))
        return 0

    with (data / 'ml' / 'intro-books.tsv').open('w', encoding='utf-8', newline='\n') as f:
        f.write('book_id\tplatform\tlength\tintro\n')
        for book_id, (platform, text, length) in sorted(intros.items()):
            f.write(f"{book_id}\t{platform}\t{length}\t{text.replace(chr(9), ' ').replace(chr(10), ' ').replace(chr(13), ' ')}\n")
    rng = random.Random(23)
    sample = rng.sample(no_intro, min(SAMPLE, len(no_intro)))
    rl.write_tsv(OUT / 'no-intro-sample.tsv', ['book_id', 'isbn13', 'title', 'author'],
                 [dict(book_id=b[0], isbn13=b[1] or '', title=b[2], author=b[3] or '') for b in sample])
    result = {
        'checked_at': fc.now(), 'crawl_started': started_crawl, 'libraries_recrawled': len(recrawled),
        'all': dict(stats['all']), 'pool': dict(stats['pool']), 'seconds': round(time.monotonic() - t0),
        'c_platforms_top': c_platforms.most_common(15), 'c_only_in_libraries_not_yet_recrawled': c_waiting,
        'intro_texts_kept': len(intros),
        'kept_by_kind': dict(Counter(targets[i] for i in intros)),
        'targets_by_kind': dict(Counter(targets.values())),
    }
    (OUT / 'counts.json').write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=1))
    return 0


def nlk(data: Path) -> int:
    key = fc.nlk_key_of(rl.read_env(REPO / '.env'), data)
    if not key:
        print('no National Library of Korea key: not asked')
        return 1
    _, books = rl.read_tsv(OUT / 'no-intro-sample.tsv')
    last = [0.0]
    requests = [0]

    def get(url: str) -> tuple[int, bytes]:
        wait = last[0] + GAP - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        try:
            req = urllib.request.Request(url, headers={'User-Agent': cc.USER_AGENT})
            with urllib.request.urlopen(req, timeout=60) as response:
                return response.status, response.read(2_000_000)
        except urllib.error.HTTPError as ex:
            return ex.code, b''
        except (urllib.error.URLError, TimeoutError, OSError):
            return 0, b''
        finally:
            last[0] = time.monotonic()
            requests[0] += 1

    def search(**params) -> list[dict]:
        status, body = get(rl.SEOJI_API + '?' + urllib.parse.urlencode(
            dict(params, cert_key=key, result_style='json', page_no=1, page_size=10)))
        try:
            return json.loads(body.decode('utf-8')).get('docs', []) or [] if status == 200 else []
        except ValueError:
            return []

    rows, texts = [], []
    for b in books:
        how, docs = '', []
        if b['isbn13']:
            how, docs = 'isbn', search(isbn=b['isbn13'])
        elif b['author']:
            names = [part.split() for part in b['author'].replace('·', ',').replace(';', ',').split(',') if part.split()]
            family = rl.text_key(names[0][-1]) if names else ''  # the first author's last word, as in KakaoBooks
            how = 'title+author'
            docs = [d for d in search(title=b['title'])
                    if family and family in rl.text_key(d.get('AUTHOR') or '')
                    and rl.text_key((d.get('TITLE') or '').split(':')[0]) == rl.text_key(b['title'].split(':')[0])]
        else:
            how = 'no isbn, no author: not asked'
        found = docs[0] if docs else None
        intro = ''
        fields = {}
        if found:
            for name in ('BOOK_INTRODUCTION_URL', 'BOOK_SUMMARY_URL'):
                url = (found.get(name) or '').strip()
                fields[name] = bool(url)
                if url and not intro:
                    status, body = get(rl.https(url) if url.startswith('http://') else url)
                    if status == 200 and body:
                        if body[:3] == b'\xff\xd8\xff' or body[:8] == b'\x89PNG\r\n\x1a\n' or body[:4] == b'%PDF':
                            fields['intro_is_picture'] = True  # a scanned page (seen 2026-10-24: JPEG), not text
                        else:
                            intro = cc.clean_intro(body.decode('utf-8', 'replace')) or ''
        rows.append(dict(book_id=b['book_id'], how=how, found=bool(found), publish=found.get('PUBLISH_PREDATE', '')
                         if found else '', **fields, intro_chars=len(intro)))
        if intro:
            texts.append((b['book_id'], intro))
    with (data / 'ml' / 'nlk-intro-sample.tsv').open('w', encoding='utf-8', newline='\n') as f:
        for book_id, intro in texts:
            f.write(f"{book_id}\t{intro.replace(chr(9), ' ').replace(chr(10), ' ')}\n")
    lengths = [r['intro_chars'] for r in rows if r['intro_chars']]
    result = {'checked_at': fc.now(), 'books': len(rows), 'requests': requests[0],
              'with_isbn': sum(1 for r in rows if r['how'] == 'isbn'),
              'found_in_bibliography': sum(1 for r in rows if r['found']),
              'with_intro_url': sum(1 for r in rows if r.get('BOOK_INTRODUCTION_URL') or r.get('BOOK_SUMMARY_URL')),
              'intro_is_picture': sum(1 for r in rows if r.get('intro_is_picture')),
              'filled': len(lengths), 'median_chars': statistics.median(lengths) if lengths else 0, 'rows': rows}
    (OUT / 'nlk.json').write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k != 'rows'}, ensure_ascii=False, indent=1))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('step', choices=['count', 'nlk'])
    parser.add_argument('--data-dir', default='')
    parser.add_argument('--counts-only', action='store_true', help='count: keep the intro file and the sample as they are')
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    data = Path(args.data_dir) if args.data_dir else cc.ROOT
    return count(data, args.counts_only) if args.step == 'count' else nlk(data)


if __name__ == '__main__':
    sys.exit(main())
