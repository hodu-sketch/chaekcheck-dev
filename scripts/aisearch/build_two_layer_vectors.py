"""The two-layer book vector file for the backend (user 2026-10-30 "두 층 (조각 512) + BM25 이걸로 개발해"): per book its
summary vector ("제목 · 저자 · 출판사 · 연도" + newline + the Haiku summary, as the experiment's summary vector) and its
intro piece vectors (build_chunk_vectors.py); the backend counts a book's best row (BookVectors, two-max). Books
without an intro or a summary keep their row of today's book-vectors.bin.

  py scripts/aisearch/build_two_layer_vectors.py summary        # summary vectors, in resumable parts
  py scripts/aisearch/build_two_layer_vectors.py file OUT.bin   # summary + piece rows (+ old rows) as CCBV1, int8

py = <data>/ml/venv/Scripts/python.exe -X utf8. Needs <data>/aisearch/two-layer/summary-terms.tsv
(build_summary_terms.py) and, for `file`, <data>/ml/two-layer/chunk512.npy + .owners.json (build_chunk_vectors.py join).
"""
from __future__ import annotations

import json
import os
import struct
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'scripts' / 'aisearch'))
import build_chunk_vectors as chunks  # noqa: E402

ML = chunks.ML
TEXTS = chunks.DATA / 'aisearch' / 'two-layer' / 'summary-terms.tsv'
PARTS = chunks.OUT / 'summary-parts'
PART = 5000


def summaries() -> list[tuple[int, str]]:
    """(book id, "head\\nsummary") of the vector file's books that have a summary, in the vector file's order."""
    written = {int(r['book_id']): r['summary'] for r in chunks.read_tsv(TEXTS) if r.get('summary')}
    heads = {int(t['book_id']): chunks.e.head(t) for t in chunks.read_tsv(chunks.DATA / 'aisearch' / '100k' / 'targets.tsv')}
    return [(i, f'{heads[i]}\n{written[i]}') for i in chunks.vector_ids() if i in written and i in heads]


def make_summaries() -> int:
    from sentence_transformers import SentenceTransformer
    import numpy as np
    PARTS.mkdir(parents=True, exist_ok=True)
    todo = summaries()
    parts = [todo[i:i + PART] for i in range(0, len(todo), PART)]
    print(f'{len(todo)} summaries in {len(parts)} parts', flush=True)
    model = SentenceTransformer(chunks.MODEL, device='cpu')
    for n, part in enumerate(parts):
        path = PARTS / f'part-{n:03d}.npy'
        if path.exists():
            continue
        t = time.monotonic()
        v = model.encode([text for _, text in part], batch_size=32, normalize_embeddings=True, show_progress_bar=False,
                         convert_to_numpy=True).astype('float32')
        np.save(PARTS / f'part-{n:03d}.tmp.npy', v)
        os.replace(PARTS / f'part-{n:03d}.tmp.npy', path)
        (PARTS / f'part-{n:03d}.ids.json').write_text(json.dumps([i for i, _ in part]))
        print(f'part {n + 1}/{len(parts)}: {len(part)} in {time.monotonic() - t:.0f}s', flush=True)
    return 0


def old_rows() -> tuple[list[int], 'object', 'object']:
    """Today's file: ids, scales, int8 rows."""
    import numpy as np
    b = chunks.VECTORS.read_bytes()
    count, dim = struct.unpack('<ii', b[6:14])
    o = 14
    ids = list(struct.unpack(f'<{count}q', b[o:o + 8 * count]))
    o += 8 * count
    scales = np.frombuffer(b, '<f4', count, o)
    o += 4 * count
    return ids, scales, np.frombuffer(b, np.int8, count * dim, o).reshape(count, dim)


def make_file(out: str) -> int:
    import numpy as np
    s_ids, s_rows = [], []
    for path in sorted(PARTS.glob('part-[0-9][0-9][0-9].npy')):
        s_ids += json.loads(path.with_name(path.stem + '.ids.json').read_text())
        s_rows.append(np.load(path))
    if len(s_ids) != len(summaries()):
        raise SystemExit('summary vectors missing: run "summary" first')
    summary_of = dict(zip(s_ids, np.concatenate(s_rows)))
    piece_owners = json.loads((chunks.OUT / 'chunk512.owners.json').read_text())
    pieces = np.load(chunks.OUT / 'chunk512.npy')
    pieces_of: dict[int, list[int]] = {}
    for k, owner in enumerate(piece_owners):
        pieces_of.setdefault(owner, []).append(k)
    old_ids, old_scales, old_q = old_rows()
    ids, floats, kept_old = [], [], []
    for k, book_id in enumerate(old_ids):
        if book_id in summary_of or book_id in pieces_of:
            if book_id in summary_of:
                ids.append(book_id)
                floats.append(summary_of[book_id])
            for p in pieces_of.get(book_id, []):
                ids.append(book_id)
                floats.append(pieces[p])
        else:
            kept_old.append(k)
    matrix = np.vstack(floats).astype(np.float32)
    matrix /= np.linalg.norm(matrix, axis=1, keepdims=True)
    scales = np.abs(matrix).max(axis=1) / 127.0
    q = np.round(matrix / scales[:, None]).clip(-127, 127).astype(np.int8)
    back = q.astype(np.float32) * scales[:, None]
    cos = (back * matrix).sum(axis=1) / np.linalg.norm(back, axis=1)
    ids += [old_ids[k] for k in kept_old]
    scales = np.concatenate([scales, old_scales[kept_old]])
    q = np.vstack([q, old_q[kept_old]])
    with open(out, 'wb') as f:
        f.write(b'CCBV1\n')
        f.write(struct.pack('<ii', len(ids), q.shape[1]))
        f.write(np.asarray(ids, dtype='<i8').tobytes())
        f.write(scales.astype('<f4').tobytes())
        f.write(q.tobytes())
    per_book = {}
    for i in ids:
        per_book[i] = per_book.get(i, 0) + 1
    print(json.dumps({'rows': len(ids), 'books': len(per_book), 'with_summary': len(summary_of),
                      'with_pieces': len(pieces_of), 'old_rows_kept': len(kept_old),
                      'rows_per_book_max': max(per_book.values()), 'mb': round(os.path.getsize(out) / 2**20, 1),
                      'int8_worst_cosine': round(float(cos.min()), 5)}))
    return 0


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    if sys.argv[1:2] == ['summary']:
        sys.exit(make_summaries())
    if sys.argv[1:2] == ['file'] and len(sys.argv) == 3:
        sys.exit(make_file(sys.argv[2]))
    raise SystemExit('usage: build_two_layer_vectors.py summary | file OUT.bin')
