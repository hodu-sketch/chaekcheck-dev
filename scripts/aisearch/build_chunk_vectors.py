"""KURE-v1 vectors of the intro pieces for the AI search's two layers (user 2026-10-30 "두 층 (조각 512) + BM25 이걸로
개발해", docs/aisearch/reports/2026-10-29-intro-text.md): every book of the backend's book-vectors.bin that has a list
intro gets its intro cut into pieces of at most 512 tokens at sentence ends, each piece read as
"제목 · 저자 · 출판사 · 연도" + newline + piece, exactly as the experiment's chunk512 (its pack() and head() are used).
The intros stay on this PC; only the vectors go to the server (cycle 23 [확정]).

Parts of PART books are saved as they finish, so a stopped run goes on from the last part.

  <data>/ml/venv/Scripts/python.exe -X utf8 scripts/aisearch/build_chunk_vectors.py          # make the missing parts
  <data>/ml/venv/Scripts/python.exe -X utf8 scripts/aisearch/build_chunk_vectors.py join     # chunk512.npy + owners

Output in <data>/ml/two-layer/: chunk512.npy (float32, pieces x 1024, normalised), chunk512.owners.json (the book id
of each piece), chunk512-stats.json.
"""
from __future__ import annotations

import json
import os
import struct
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DATA = Path(os.environ.get('CHAEKCHECK_DATA') or REPO.parent / 'chaekcheck-data')
ML = DATA / 'ml'
OUT = ML / 'two-layer'
PARTS = OUT / 'chunk512-parts'
os.environ.setdefault('HF_HOME', str(ML / 'hf'))
sys.path.insert(0, str(REPO / 'scripts' / 'aisearch' / 'eval'))
import intro_text_eval as e  # noqa: E402  the experiment's head() and pack(), so the pieces are cut the same way

MODEL = e.MODEL
SIZE = 512
INTRO_MAX = e.LENGTH_INTRO_MAX
VECTORS = ML / 'aisearch-model-100k' / 'book-vectors.bin'
PART = 2000


def read_tsv(path: Path) -> list[dict]:
    lines = path.read_text(encoding='utf-8').split('\n')
    head = lines[0].split('\t')
    return [dict(zip(head, line.split('\t'))) for line in lines[1:] if line]


def vector_ids() -> list[int]:
    """The book ids of book-vectors.bin, in its order."""
    with VECTORS.open('rb') as f:
        head = f.read(14)
        count = struct.unpack('<i', head[6:10])[0]
        return list(struct.unpack(f'<{count}q', f.read(8 * count)))


def books() -> tuple[list[tuple[int, str, str]], dict]:
    """(book id, head line, intro) of the vector file's books that have an intro and a head line."""
    intros = {int(r['book_id']): r['intro'] for r in read_tsv(ML / 'intro-books-100k.tsv')}
    heads = {int(t['book_id']): e.head(t) for t in read_tsv(DATA / 'aisearch' / '100k' / 'targets.tsv')}
    ids = vector_ids()
    out = [(i, heads[i], intros[i][:INTRO_MAX]) for i in ids if i in intros and i in heads and len(intros[i]) >= e.INTRO_MIN]
    stats = {'vector_books': len(ids), 'with_intro_and_head': len(out),
             'no_intro': sum(1 for i in ids if i not in intros),
             'no_head': sum(1 for i in ids if i in intros and i not in heads)}
    return out, stats


def make() -> int:
    import numpy as np
    from sentence_transformers import SentenceTransformer
    PARTS.mkdir(parents=True, exist_ok=True)
    todo, stats = books()
    (OUT / 'chunk512-stats.json').write_text(json.dumps(stats, indent=1))
    parts = [todo[i:i + PART] for i in range(0, len(todo), PART)]
    print(f'{stats}: {len(parts)} parts of {PART} books', flush=True)
    model = SentenceTransformer(MODEL, device='cpu')
    model.max_seq_length = SIZE + 8
    tok = model.tokenizer

    def count(s: str) -> int:
        return len(tok(s, add_special_tokens=False)['input_ids'])

    started = time.monotonic()
    made = 0
    for n, part in enumerate(parts):
        path = PARTS / f'part-{n:03d}.npy'
        if path.exists() and (PARTS / f'part-{n:03d}.owners.json').exists():
            continue
        t = time.monotonic()
        texts, owners = [], []
        for book_id, head, intro in part:
            for piece in e.pack(intro, SIZE - count(head) - 3, count):
                texts.append(f'{head}\n{piece}')
                owners.append(book_id)
        vectors = model.encode(texts, batch_size=16, normalize_embeddings=True, show_progress_bar=False,
                               convert_to_numpy=True).astype('float32')
        np.save(PARTS / f'part-{n:03d}.tmp.npy', vectors)
        os.replace(PARTS / f'part-{n:03d}.tmp.npy', path)
        (PARTS / f'part-{n:03d}.owners.json').write_text(json.dumps(owners))
        made += len(part)
        rate = made / (time.monotonic() - started)
        left = sum(len(p) for p in parts[n + 1:])
        print(f'part {n + 1}/{len(parts)}: {len(part)} books, {len(texts)} pieces in {time.monotonic() - t:.0f}s, '
              f'{rate:.2f} books/s, about {left / rate / 3600:.1f} h left', flush=True)
    print('all parts made', flush=True)
    return 0


def join() -> int:
    import numpy as np
    todo, stats = books()
    blocks, owners = [], []
    for n in range((len(todo) + PART - 1) // PART):
        path = PARTS / f'part-{n:03d}.npy'
        if not path.exists():
            raise SystemExit(f'part {n} is missing: run without "join" first')
        blocks.append(np.load(path))
        owners += json.loads((PARTS / f'part-{n:03d}.owners.json').read_text())
    matrix = np.concatenate(blocks).astype('float32')
    np.save(OUT / 'chunk512.npy', matrix)
    (OUT / 'chunk512.owners.json').write_text(json.dumps(owners))
    stats.update({'pieces': len(owners), 'books_with_pieces': len(set(owners)),
                  'pieces_per_book_mean': round(len(owners) / max(1, len(set(owners))), 2)})
    (OUT / 'chunk512-stats.json').write_text(json.dumps(stats, indent=1))
    print(json.dumps(stats))
    return 0


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    sys.exit(join() if sys.argv[1:] == ['join'] else make())
