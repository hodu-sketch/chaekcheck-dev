"""KURE-v1 vectors of the 100,000 books held by the most libraries (user 2026-10-26), the same text rule as
pool-30000-intro.npy (scripts/crawl/probe_embeddings.py --intro): "제목 · 저자 · 출판사 · 연도" and, on the next line, the
first 300 characters of the book's longest list intro. The 30,000 books already made keep their vectors; the others
with an intro of at least INTRO_MIN characters (build_100k.py prepare) are made here, in parts of PART books saved as they
finish, so a stopped run goes on from the last part. Books without an intro are left out (user: "소개글 없는 건 따로 빼줘").

  <data>/ml/venv/Scripts/python.exe -X utf8 scripts/aisearch/build_100k_vectors.py          # make the missing parts
  <data>/ml/venv/Scripts/python.exe -X utf8 scripts/aisearch/build_100k_vectors.py join     # pool-100000-intro.npy

Then scripts/aisearch/make_book_vectors.py OUT.bin --pool pool-100000-intro writes the backend's book-vectors.bin.
"""
from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DATA = Path(os.environ.get('CHAEKCHECK_DATA') or REPO.parent / 'chaekcheck-data')
ML = DATA / 'ml'
OUT100 = DATA / 'aisearch' / '100k'
PARTS = ML / 'pool-100k-parts'
os.environ.setdefault('HF_HOME', str(ML / 'hf'))
MODEL = 'nlpai-lab/KURE-v1'
INTRO_MIN = 50
INTRO_CHARS = 300
PART = 2000


def read_tsv(path: Path) -> list[dict]:
    lines = path.read_text(encoding='utf-8').split('\n')
    head = lines[0].split('\t')
    return [dict(zip(head, line.split('\t'))) for line in lines[1:] if line]


def todo() -> list[tuple[int, str]]:
    old = set(json.loads((ML / 'pool-30000-intro.ids.json').read_text()))
    intros = {int(r['book_id']): r['intro'] for r in read_tsv(ML / 'intro-books-100k.tsv')}
    books = []
    for t in read_tsv(OUT100 / 'targets.tsv'):
        book_id = int(t['book_id'])
        if book_id in old or int(t['intro_chars']) < INTRO_MIN:
            continue
        base = ' · '.join(p for p in (t['title'], t['author'], t['publisher'], t['year']) if p)
        books.append((book_id, f'{base}\n{intros[book_id][:INTRO_CHARS]}'))
    return books


def make() -> int:
    import numpy as np
    from sentence_transformers import SentenceTransformer
    PARTS.mkdir(parents=True, exist_ok=True)
    books = todo()
    parts = [books[i:i + PART] for i in range(0, len(books), PART)]
    print(f'{len(books)} books to make in {len(parts)} parts of {PART}', flush=True)
    model = SentenceTransformer(MODEL, device='cpu')
    started = time.monotonic()
    made = 0
    for n, part in enumerate(parts):
        path = PARTS / f'part-{n:03d}.npy'
        if path.exists() and (PARTS / f'part-{n:03d}.ids.json').exists():
            continue
        t = time.monotonic()
        vectors = model.encode([text for _, text in part], batch_size=32, normalize_embeddings=True,
                               show_progress_bar=False, convert_to_numpy=True).astype('float32')
        np.save(PARTS / f'part-{n:03d}.tmp.npy', vectors)
        os.replace(PARTS / f'part-{n:03d}.tmp.npy', path)
        (PARTS / f'part-{n:03d}.ids.json').write_text(json.dumps([book_id for book_id, _ in part]))
        made += len(part)
        rate = made / (time.monotonic() - started)
        left = sum(len(p) for p in parts[n + 1:])
        print(f'part {n + 1}/{len(parts)}: {len(part)} books in {time.monotonic() - t:.0f}s, '
              f'{rate:.2f} books/s, about {left / rate / 3600:.1f} h left', flush=True)
    print('all parts made', flush=True)
    return 0


def join() -> int:
    import numpy as np
    ids = json.loads((ML / 'pool-30000-intro.ids.json').read_text())
    blocks = [np.load(ML / 'pool-30000-intro.npy')]
    expected = len(todo())
    got = 0
    for path in sorted(PARTS.glob('part-[0-9][0-9][0-9].npy')):
        part_ids = json.loads(path.with_name(path.stem + '.ids.json').read_text())
        blocks.append(np.load(path))
        ids += part_ids
        got += len(part_ids)
    if got < expected:
        raise SystemExit(f'{expected - got} books still without a vector: run without "join" first')
    matrix = np.concatenate(blocks).astype('float32')
    np.save(ML / 'pool-100000-intro.npy', matrix)
    (ML / 'pool-100000-intro.ids.json').write_text(json.dumps(ids))
    print(json.dumps({'books': len(ids), 'old_30000': len(ids) - got, 'new': got, 'dim': int(matrix.shape[1]),
                      'file': str(ML / 'pool-100000-intro.npy')}))
    return 0


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    sys.exit(join() if sys.argv[1:] == ['join'] else make())
