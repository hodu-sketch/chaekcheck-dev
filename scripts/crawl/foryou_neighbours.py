"""Cycle 23 step 10, first round (user 2026-10-25: "일단 추천도서에 있는 것들 자료만으로도", "병렬로 임베딩 추천도서에
있는 것들 진행해줘"): nlpai-lab/KURE-v1 neighbours of the recommended-list books and the books the for-you build
scored, among themselves only. No request to any library site; SELECTs on the PC's service DB and the collected DB.

Run with the data folder's venv (torch CPU, sentence-transformers):

  <data>/ml/venv/Scripts/python.exe -X utf8 scripts/crawl/foryou_neighbours.py [--intro-chars 300] [--with-pool]

- Books: picks.list_book.book_id (every list) + foryou.book_score.book_id (the latest build's scored books).
- Text: "제목 · 저자 · 출판사 · 연도", then on the next line the first --intro-chars characters of the longest list intro
  of the book's holdings (holding.intro_raw of the collected DB, as probe_intro.py picks it); without an intro the line
  alone (cycle 23 intro test, docs/reports/cycle23-intro-test-2026-10-25.md).
- --with-pool (user 2026-10-25: "그것도 넣어줘"): the 30,000 books held by the most libraries join with the vectors the
  intro test saved (<data>/ml/pool-30000-intro.npy, same text rule, intros as collected by 2026-10-24 20:23); a book in
  both keeps the vector made now. The saved ids must still be our books (the same catalog import).
- Neighbours: the 20 nearest by cosine among the same books, the book itself and other editions or volumes of its own
  work left out (the same main_title_norm, or the same work_key with the same first author), and one edition of each
  work among them (same_work). Books with no neighbour left get no row.
- Writes <data>/ml/foryou/neighbours.tsv (first line "# export_id=<latest SUCCEEDED catalog_import.export_id>", rows
  "book_id<TAB>n1,n2,...", the collection import mode neighbours reads it) and neighbours-review.tsv (titles and
  cosines for people to read). The intro text stays in the data folder (outside git).
"""
from __future__ import annotations

import argparse
import itertools
import functools
import hashlib
import json
import os
import re
import sqlite3
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DATA = Path(os.environ.get('CHAEKCHECK_DATA') or REPO.parent / 'chaekcheck-data')
ML = DATA / 'ml'
os.environ.setdefault('HF_HOME', str(ML / 'hf'))
MODEL = 'nlpai-lab/KURE-v1'
NEIGHBOURS = 20
SHORTLIST = 200  # nearest candidates looked at first for each book (2026-10-26, 80,000 books)


def env() -> dict[str, str]:
    values = {}
    for line in (REPO / '.env').read_text(encoding='utf-8').splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            k, v = line.split('=', 1)
            values[k.strip()] = v.strip()
    return values


def text_of(title, author, publisher, published) -> str:
    parts = [title or '', author or '', publisher or '']
    if published:
        parts.append(str(published)[:4])
    return ' · '.join(p for p in parts if p)


_BRACKETS = re.compile(r'[(\[〈<《「『【{].*?[)\]〉>》」』】}]')
_TAIL_NUMBER = re.compile(r'(?:[\s.,·:\-]*vol(?:ume)?\.?\s*\d+\.?|[\s.,·:\-]*제?\d+\s*(?:권|부|편|화)?|[\s.,·:\-]+[ivx]+)$',
                          re.IGNORECASE)
# a year edition's year at the start or the end ("2025 한국이 열광할 세계 트렌드", "트렌드 코리아 2026"; user 2026-10-26)
_YEAR = re.compile(r'^(?:19|20)\d{2}\s+|\s+(?:19|20)\d{2}\s*(?:년판|년)?$')
# a shorter title inside a longer one, or a shared ending, counts for one work only from this many letters on
_CONTAINED = 3
_SHARED_END = 4
# ... and an ending counts only with this many letters of the beginning shared too ("그리스신화이야기" and
# "로마신화이야기" stay two books)
_SHARED_START = 2
_ROLES = re.compile(r'\s*(지음|저|글|그림|엮음|편저|편|옮김|역|번역|감수|원작)\.?$')


@functools.lru_cache(maxsize=None)
def work_key(title: str | None) -> str:
    """
    The title with its edition and volume marks taken off (user 2026-10-25: other editions and volumes of the same
    book stay among the neighbours): anything in brackets, a subtitle after ':' or ' - ', and a volume number at the
    end ("토지. 2(1부 2권)" → "토지", "마션 (THE MARTIAN)" → "마션", "달러구트 꿈 백화점 2" → "달러구트꿈백화점"); then
    lower case without spaces or marks. A title that would come out empty (a number alone, as "1984") keeps its letters.
    """
    text = (title or '').strip()
    bare = _BRACKETS.sub(' ', text)
    bare = re.split(r'\s*:\s*|\s+-\s*|\s*-\s+', bare)[0]  # "삼국지(20)-두 영웅의 대결" too (2026-10-26)
    bare = _YEAR.sub('', bare.strip())
    for _ in range(2):  # "토지. 2. 3" or "1부 2권"
        bare = _TAIL_NUMBER.sub('', bare.strip())
    key = re.sub(r'[\W_]+', '', bare).lower()
    return key or re.sub(r'[\W_]+', '', text).lower()


@functools.lru_cache(maxsize=None)
def first_author(author: str | None) -> str:
    """
    The first name of an author line, without its role or a name in brackets ("앤디 위어 지음, 박아람 옮김" → "앤디위어",
    "셰익스피어(William Shakespeare)" → "셰익스피어").
    """
    first = _BRACKETS.sub(' ', re.split(r'[,;/|]', author or '')[0]).strip()
    return re.sub(r'[\W_]+', '', _ROLES.sub('', first)).lower()


def same_work(a: tuple, b: tuple) -> bool:
    """
    Two books (id, title, author_display, …) are one work when their work keys are the same and their first authors
    agree (one name inside the other, as "셰익스피어" in "윌리엄셰익스피어"), or one of them has no author. Since
    2026-10-26 (100,000 books, user "③을 고치고 이웃 표를 다시 계산"), also when both have an author, the authors agree,
    and one key holds the other ({_CONTAINED}+ letters: "명상록" in "초역명상록") or the keys end alike ({_SHARED_END}+
    letters, with {_SHARED_START}+ letters of the beginning too: "라만차돈키호테" and "라만차의비범한이달고돈키호테").
    """
    ka, kb = work_key(a[1]), work_key(b[1])
    x, y = first_author(a[2]), first_author(b[2])
    authors_agree = not x or not y or x in y or y in x
    if ka == kb:
        return authors_agree
    if not (x and y and authors_agree):
        return False
    short, long_ = sorted((ka, kb), key=len)
    if len(short) >= _CONTAINED and short in long_:
        return True
    return (len(os.path.commonprefix([ka[::-1], kb[::-1]])) >= _SHARED_END
            and len(os.path.commonprefix([ka, kb])) >= _SHARED_START)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--intro-chars', type=int, default=300)
    parser.add_argument('--out', default=str(ML / 'foryou'))
    parser.add_argument('--with-pool', action='store_true')
    # user 2026-10-26 "10만권으로 늘릴 예정이야": the collect PC's pool-100000-intro (80,549 books with an intro)
    parser.add_argument('--pool', default='pool-30000-intro', help='saved pool vectors in <data>/ml, without .npy')
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    import numpy as np
    import psycopg
    e = env()
    db = psycopg.connect(host='localhost', port=e.get('DB_PORT', '5432'), dbname=e['DB_NAME'], user=e['DB_USERNAME'],
                         password=e.get('DB_PASSWORD', ''), autocommit=True)
    cur = db.cursor()
    cur.execute("SELECT export_id FROM collection.catalog_import WHERE status = 'SUCCEEDED' ORDER BY id DESC LIMIT 1")
    export_id = cur.fetchone()[0]
    cur.execute("""
        SELECT b.id, b.title, b.author_display, b.publisher, b.published_on, b.main_title_norm FROM catalog.book b
        WHERE b.id IN (SELECT book_id FROM picks.list_book WHERE book_id IS NOT NULL
                       UNION SELECT book_id FROM foryou.book_score)
        ORDER BY b.id""")
    books = cur.fetchall()
    ids = [b[0] for b in books]
    # holdings of these books, to find the longest list intro in the collected DB
    cur.execute("""
        SELECT h.book_id, l.collection_library_id, h.item_key FROM catalog.holding h
        JOIN collection.collection_link l ON l.library_id = h.library_id
        WHERE h.book_id = ANY(%s)""", (ids,))
    holdings = cur.fetchall()
    collected = sqlite3.connect(f'file:{(DATA / "ebook-catalog.sqlite").as_posix()}?mode=ro', uri=True, timeout=120)
    collected.execute('PRAGMA busy_timeout=120000')
    intros: dict[int, str] = {}
    for book_id, lib, item in holdings:
        row = collected.execute('SELECT intro_raw FROM holding WHERE library_id = ? AND item_key = ?',
                                (int(lib), item)).fetchone()
        if row and row[0] and len(row[0]) > len(intros.get(book_id, '')):
            intros[book_id] = row[0]
    collected.close()
    texts = []
    for b in books:
        line = text_of(*b[1:5])
        intro = intros.get(b[0])
        texts.append(f'{line}\n{intro[:args.intro_chars]}' if intro and args.intro_chars > 0 else line)
    print(f'books {len(books)}, with intro {sum(1 for b in books if b[0] in intros)}, export_id {export_id}',
          flush=True)

    saved = out / 'vectors.ids.json'
    texts_hash = hashlib.sha1('\0'.join(texts).encode('utf-8')).hexdigest()
    last = json.loads(saved.read_text()) if saved.exists() else None
    # the first run (2026-10-25 morning) kept the ids alone; its texts were made from the same intros a few minutes before
    if last in ({'ids': ids, 'texts': texts_hash}, ids):
        vectors = np.load(out / 'vectors.npy')  # the same books and texts as the last run
        load = seconds = 0.0
        print('vectors: the last run\'s, same books and texts', flush=True)
    else:
        from sentence_transformers import SentenceTransformer
        started = time.monotonic()
        model = SentenceTransformer(MODEL, device='cpu')
        load = time.monotonic() - started
        t = time.monotonic()
        vectors = model.encode(texts, batch_size=32, normalize_embeddings=True, convert_to_numpy=True,
                               show_progress_bar=False).astype('float32')
        seconds = time.monotonic() - t
        print(f'model {load:.0f}s, encode {seconds:.0f}s', flush=True)
        np.save(out / 'vectors.npy', vectors)
        saved.write_text(json.dumps({'ids': ids, 'texts': texts_hash}))

    pool_added = 0
    if args.with_pool:
        pool_ids = json.loads((ML / f'{args.pool}.ids.json').read_text())
        pool_vectors = np.load(ML / f'{args.pool}.npy')
        known = set(ids)
        keep = [i for i, book_id in enumerate(pool_ids) if book_id not in known]
        extra_ids = [pool_ids[i] for i in keep]
        cur.execute('SELECT id, title, author_display, publisher, published_on, main_title_norm FROM catalog.book '
                    'WHERE id = ANY(%s)', (extra_ids,))
        found = {r[0]: r for r in cur.fetchall()}
        if len(found) != len(extra_ids):
            raise SystemExit(f'{len(extra_ids) - len(found)} saved pool ids are not our books: another catalog import?')
        books = books + [found[i] for i in extra_ids]
        vectors = np.concatenate([vectors, pool_vectors[keep]])
        pool_added = len(extra_ids)
        print(f'pool: {len(pool_ids)} saved, {pool_added} added, {len(pool_ids) - pool_added} already here', flush=True)

    rows, review = [], []
    for start in range(0, len(books), 1000):  # 1,000 rows at a time: the whole matrix of 31,000 books is ~4 GB
        block = vectors[start:start + 1000] @ vectors.T
        # the nearest SHORTLIST of each row in order (enough for 20 after editions are left out), the whole row only
        # when they are not; a full sort of 80,000 books per row took too long
        near = np.argpartition(-block, SHORTLIST, axis=1)[:, :SHORTLIST]
        if start % 10000 == 0:
            print(f'neighbours {start}/{len(books)}', flush=True)
        for k in range(block.shape[0]):
            b = books[start + k]
            picked = []
            for j in itertools.chain(near[k][np.argsort(-block[k][near[k]])], np.argsort(-block[k])):
                n = books[j]
                if n[0] == b[0] or (b[5] and n[5] == b[5]) or same_work(n, b):
                    continue  # the book itself and other editions or volumes of the same work
                if any(same_work(n, p) for p, _ in picked):
                    continue  # one edition of each work among the neighbours
                if any(p[0] == n[0] for p, _ in picked):
                    continue  # the whole row after the shortlist sees them again
                picked.append((n, float(block[k][j])))
                if len(picked) == NEIGHBOURS:
                    break
            if not picked:
                continue
            rows.append(f"{b[0]}\t{','.join(str(n[0]) for n, _ in picked)}")
            for rank, (n, score) in enumerate(picked, 1):
                review.append('\t'.join(str(x).replace('\t', ' ') for x in
                                        (b[0], b[1], rank, n[0], n[1], n[2] or '', f'{score:.3f}')))
    with (out / 'neighbours.tsv').open('w', encoding='utf-8', newline='\n') as f:
        f.write(f'# export_id={export_id}\n')
        f.write('\n'.join(rows) + '\n')
    with (out / 'neighbours-review.tsv').open('w', encoding='utf-8', newline='\n') as f:
        f.write('book_id\ttitle\trank\tneighbour_id\tneighbour_title\tneighbour_author\tcosine\n')
        f.write('\n'.join(review) + '\n')
    summary = {'model': MODEL, 'export_id': str(export_id), 'books': len(books), 'pool_added': pool_added,
               'made_now': len(ids), 'made_now_with_intro': len(intros),
               'intro_chars': args.intro_chars, 'load_seconds': round(load, 1), 'encode_seconds': round(seconds, 1),
               'rows': len(rows), 'made_at': time.strftime('%Y-%m-%d %H:%M:%S')}
    (out / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    return 0


if __name__ == '__main__':
    sys.exit(main())
