"""Cycle 23 pre-test (plan cycle 23 spec R7-3, R7-4, R6-1a): nlpai-lab/KURE-v1 neighbours from bibliographic text.
No request to any library site; the model is downloaded once from Hugging Face into the data folder.

Run with the data folder's venv (torch CPU, sentence-transformers):

  <data>/ml/venv/Scripts/python.exe -X utf8 scripts/crawl/probe_embeddings.py

1. Speed: 1,000 service books (every N-th id), text "제목 · 저자 · 출판사 · 출판 연도", seconds on this CPU → all books.
2. Neighbours: 30 seed books from the recommended lists (KAIST, SNU 100, Nobel, NLK librarian picks), 20 nearest by
   cosine in a pool of the 30,000 books held by the most libraries (other editions of the seed's own title left out).
3. Popular: how many of those neighbours are in the popular lists of probe_popular.py (top 100 per site, top 400).
Writes <data dir of the repo>/data/cycle23-pretest/neighbours.tsv and summary.json; the vectors stay in <data>/ml/.

--intro (cycle 23 intro test, plan request to-dev-intro-test-2026-10-24.md): the text gets the book's list intro, first
INTRO_CHARS characters, from <data>/ml/intro-books.tsv (probe_intro.py count); the seeds are the pre-test's 30
(docs/cycle23-foryou/experiments/2026-10-24-pretest/summary.json); results go to data/cycle23-intro/ (neighbours-intro.tsv, summary-intro.json).
"""
from __future__ import annotations

import json
import os
import random
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DATA = Path(os.environ.get('CHAEKCHECK_DATA') or REPO.parent / 'chaekcheck-data')
ML = DATA / 'ml'
os.environ.setdefault('HF_HOME', str(ML / 'hf'))
OUT = REPO / 'data' / 'cycle23-pretest'
MODEL = 'nlpai-lab/KURE-v1'
POOL = 30000
SAMPLE = 1000
SEEDS = {'kaist': 8, 'snu100': 8, 'nobel': 7, 'librarian': 7}
NEIGHBOURS = 20
INTRO_CHARS = 300


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


INTROS: dict[int, str] = {}


def text_with_intro(book_id, title, author, publisher, published) -> str:
    """The bibliographic line, then the intro's first INTRO_CHARS characters on the next line when there is one."""
    base = text_of(title, author, publisher, published)
    intro = INTROS.get(book_id)
    return f'{base}\n{intro[:INTRO_CHARS]}' if intro else base


def main() -> int:
    global OUT
    sys.stdout.reconfigure(encoding='utf-8')
    with_intro = '--intro' in sys.argv[1:]
    suffix = '-intro' if with_intro else ''
    if with_intro:
        OUT = REPO / 'data' / 'cycle23-intro'
        # split on \n only: an intro may hold other line separators (U+2028, \x85 ...) that splitlines() would cut
        lines = (ML / 'intro-books.tsv').read_text(encoding='utf-8').split('\n')[1:]
        for line in filter(None, lines):
            book_id, _, _, intro = line.split('\t', 3)
            INTROS[int(book_id)] = intro
    import numpy as np
    import psycopg
    import torch
    from sentence_transformers import SentenceTransformer
    e = env()
    db = psycopg.connect(host='localhost', port=e.get('DB_PORT', '5432'), dbname=e['DB_NAME'], user=e['DB_USERNAME'],
                         password=e.get('DB_PASSWORD', ''), autocommit=True)
    cur = db.cursor()
    OUT.mkdir(parents=True, exist_ok=True)
    ML.mkdir(parents=True, exist_ok=True)
    summary: dict = {'model': MODEL, 'torch': torch.__version__, 'threads': torch.get_num_threads(),
                     'gpu': torch.cuda.is_available(), 'intro': with_intro, 'intro_chars': INTRO_CHARS if with_intro else 0,
                     'intros_loaded': len(INTROS)}

    started = time.monotonic()
    model = SentenceTransformer(MODEL, device='cpu')
    summary['load_seconds'] = round(time.monotonic() - started, 1)
    summary['dimension'] = model.get_sentence_embedding_dimension()
    print(f"model loaded in {summary['load_seconds']}s, dimension {summary['dimension']}, threads {summary['threads']}")

    # 1. speed on 1,000 books spread over the ids
    cur.execute('SELECT count(*), min(id), max(id) FROM catalog.book')
    total, lo, hi = cur.fetchone()
    step = max(1, total // SAMPLE)
    cur.execute('SELECT id, title, author_display, publisher, published_on FROM catalog.book WHERE (id - %s) %% %s = 0 '
                'ORDER BY id LIMIT %s', (lo, step, SAMPLE))
    sample_rows = cur.fetchall()
    sample = [text_with_intro(*r) if with_intro else text_of(*r[1:]) for r in sample_rows]
    summary['sample_with_intro'] = sum(1 for r in sample_rows if r[0] in INTROS)
    model.encode(sample[:16], batch_size=16)  # warm-up
    t = time.monotonic()
    model.encode(sample, batch_size=32, normalize_embeddings=True)
    seconds = time.monotonic() - t
    summary.update(sample_books=len(sample), sample_seconds=round(seconds, 1), books=total,
                   all_books_hours=round(seconds / len(sample) * total / 3600, 1),
                   sample_text_examples=sample[:3], mean_chars=round(sum(map(len, sample)) / len(sample), 1))
    print(f"speed: {len(sample)} books in {seconds:.1f}s -> {total} books about {summary['all_books_hours']} h")

    # 2. pool and seeds
    cur.execute('SELECT id, title, author_display, publisher, published_on, main_title_norm, cardinality(library_ids) '
                'FROM catalog.book ORDER BY cardinality(library_ids) DESC, id LIMIT %s', (POOL,))
    pool = cur.fetchall()
    summary['pool'] = len(pool)
    summary['pool_min_libraries'] = pool[-1][6]
    pool_file = ML / f'pool-{POOL}{suffix}.npy'
    ids_file = ML / f'pool-{POOL}{suffix}.ids.json'
    summary['pool_with_intro'] = sum(1 for r in pool if r[0] in INTROS)
    if pool_file.exists() and ids_file.exists() and json.loads(ids_file.read_text()) == [r[0] for r in pool]:
        vectors = np.load(pool_file)
        summary['pool_seconds'] = 'cached'
    else:
        t = time.monotonic()
        vectors = model.encode([text_with_intro(*r[:5]) if with_intro else text_of(*r[1:5]) for r in pool],
                               batch_size=32, normalize_embeddings=True,
                               show_progress_bar=False, convert_to_numpy=True).astype('float32')
        summary['pool_seconds'] = round(time.monotonic() - t, 1)
        np.save(pool_file, vectors)
        ids_file.write_text(json.dumps([r[0] for r in pool]))
    print(f"pool: {len(pool)} books (>= {summary['pool_min_libraries']} libraries), {summary['pool_seconds']}s")

    rng = random.Random(23)
    seeds = []
    if with_intro:  # the pre-test's 30 seeds, in its order
        before = json.loads((REPO / 'docs' / 'cycle23-foryou' / 'experiments' / '2026-10-24-pretest' / 'summary.json').read_text(encoding='utf-8'))
        for p in before['per_seed']:
            cur.execute('SELECT id, title, author_display, publisher, published_on, main_title_norm, %s '
                        'FROM catalog.book WHERE id = %s', (p['list'], p['seed_id']))
            seeds.append(cur.fetchone())
    for key, n in ({} if with_intro else SEEDS).items():
        if key == 'librarian':
            cur.execute("""SELECT DISTINCT ON (lb.id) b.id, b.title, b.author_display, b.publisher, b.published_on,
                             b.main_title_norm, %s FROM picks.list_book lb JOIN catalog.book b
                             ON b.isbn13 = coalesce(lb.pinned_isbn13, lb.isbn13)
                           WHERE lb.list_key = 'librarian' ORDER BY lb.id, cardinality(b.library_ids) DESC""", (key,))
        else:
            cur.execute("""SELECT b.id, b.title, b.author_display, b.publisher, b.published_on, b.main_title_norm, %s
                           FROM picks.list_book lb JOIN catalog.book b ON b.id = lb.book_id
                           WHERE lb.list_key = %s ORDER BY lb.id""", (key, key))
        found = cur.fetchall()
        summary[f'seeds_available_{key}'] = len(found)
        seeds += rng.sample(found, min(n, len(found)))
    summary['seeds_with_intro'] = sum(1 for s in seeds if s[0] in INTROS)
    seed_vectors = model.encode([text_with_intro(*s[:5]) if with_intro else text_of(*s[1:5]) for s in seeds],
                                batch_size=32, normalize_embeddings=True,
                                convert_to_numpy=True).astype('float32')

    # 3. popular sets from probe_popular.py
    popular = {'top100': set(), 'top400': set()}
    lines = (REPO / 'data' / 'cycle23-pretest' / 'popular.tsv').read_text(encoding='utf-8').splitlines()
    header = lines[0].split('\t')
    for line in lines[1:]:
        r = dict(zip(header, line.split('\t')))
        if r.get('book_id'):
            if int(r['rank']) <= 100:
                popular['top100'].add(int(r['book_id']))
            if int(r['rank']) <= 400:
                popular['top400'].add(int(r['book_id']))
    pool_ids = {r[0] for r in pool}
    summary['popular_top100'] = len(popular['top100'])
    summary['popular_top400'] = len(popular['top400'])
    summary['popular_top100_in_pool'] = len(popular['top100'] & pool_ids)
    summary['popular_top400_in_pool'] = len(popular['top400'] & pool_ids)

    rows = []
    per_seed = []
    for s, v in zip(seeds, seed_vectors):
        scores = vectors @ v
        order = np.argsort(-scores)
        picked = []
        for i in order:
            b = pool[i]
            if b[0] == s[0] or (b[5] and b[5] == s[5]):
                continue  # the seed itself and other editions or volumes of the same title
            picked.append((b, float(scores[i])))
            if len(picked) == NEIGHBOURS:
                break
        in100 = sum(1 for b, _ in picked if b[0] in popular['top100'])
        in400 = sum(1 for b, _ in picked if b[0] in popular['top400'])
        per_seed.append({'list': s[6], 'seed_id': s[0], 'seed': text_of(*s[1:5]), 'seed_intro': s[0] in INTROS,
                         'in_top100': in100, 'in_top400': in400})
        for rank, (b, score) in enumerate(picked, 1):
            rows.append([s[6], s[0], s[1], rank, b[0], b[1], b[2] or '', b[3] or '', str(b[4] or '')[:4],
                         f'{score:.3f}', 'Y' if b[0] in popular['top100'] else '', 'Y' if b[0] in popular['top400'] else '',
                         'Y' if b[0] in INTROS else ''])
    OUT.mkdir(parents=True, exist_ok=True)
    with (OUT / f'neighbours{suffix}.tsv').open('w', encoding='utf-8', newline='\n') as f:
        f.write('\t'.join(['list', 'seed_id', 'seed_title', 'rank', 'book_id', 'title', 'author', 'publisher', 'year',
                           'cosine', 'popular_top100', 'popular_top400', 'intro']) + '\n')
        for r in rows:
            f.write('\t'.join(str(x).replace('\t', ' ') for x in r) + '\n')
    summary['per_seed'] = per_seed
    summary['neighbours'] = len(rows)
    summary['neighbours_in_top100'] = sum(p['in_top100'] for p in per_seed)
    summary['neighbours_in_top400'] = sum(p['in_top400'] for p in per_seed)
    summary['seeds_with_any_top100'] = sum(1 for p in per_seed if p['in_top100'])
    summary['seeds_with_any_top400'] = sum(1 for p in per_seed if p['in_top400'])
    (OUT / f'summary{suffix}.json').write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps({k: v for k, v in summary.items() if k != 'per_seed'}, ensure_ascii=False, indent=1))
    return 0


if __name__ == '__main__':
    sys.exit(main())
