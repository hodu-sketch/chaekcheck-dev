"""Recall of cheap first passes (binary, int4) against the exact int8 top-k of BookVectorFile.nearest.

Usage: python -X utf8 cascade_recall.py <book-vectors.bin> <queries.f32> <out.json>

- exact: (int8 row . q) * scale, the same score the server uses.
- ham: symmetric binary, sign bits of book and question (x > 0, like pgvector binary_quantize), hamming distance.
- asym: question kept as floats, book as +1/-1 from its sign bits (same 128 bytes per book).
- asym_c: like asym, but the mean book vector is taken off before the sign.
- int4: int8 row rounded to 16 levels (row / 16, -8..7), times scale (512 bytes per book).
- cascade: ham keeps N1, asym keeps N2 of those, then exact top-k of the N2.
Recall = share of the exact top-k found in the N candidates, averaged over the questions.
"""
import json
import struct
import sys
import time

import numpy as np


def read_books(path):
    b = open(path, 'rb').read()
    n, d = struct.unpack('<ii', b[6:14])
    o = 14 + 8 * n
    scales = np.frombuffer(b, '<f4', n, o)
    o += 4 * n
    rows = np.frombuffer(b, np.int8, n * d, o).reshape(n, d)
    return rows, scales


def read_queries(path):
    b = open(path, 'rb').read()
    qn, qd = struct.unpack('<ii', b[:8])
    return np.frombuffer(b, '<f4', qn * qd, 8).reshape(qn, qd)


def top(scores, n):
    """Indices of the n highest scores per row (unordered)."""
    return np.argpartition(-scores, n, axis=1)[:, :n]


def recall(gt, cand):
    r = [len(set(g) & set(c)) / len(g) for g, c in zip(gt, cand)]
    return {'mean': round(float(np.mean(r)), 4), 'p10': round(float(np.percentile(r, 10)), 3),
            'min': round(float(np.min(r)), 3)}


def main(books_path, queries_path, out_path):
    rows, scales = read_books(books_path)
    q = read_queries(queries_path)
    n, d = rows.shape
    rf = rows.astype(np.float32)
    exact = (q @ rf.T) * scales[None, :]

    bits = (rows > 0)
    qbits = (q > 0)
    pm = np.where(bits, 1.0, -1.0).astype(np.float32)
    # hamming = (d - (+-1 . +-1)) / 2, so a higher dot product is a smaller distance
    ham = np.where(qbits, 1.0, -1.0).astype(np.float32) @ pm.T
    asym = q @ pm.T
    f = rf * scales[:, None]
    asym_c = q @ np.where(f - f.mean(0) > 0, 1.0, -1.0).astype(np.float32).T
    int4 = (q @ np.clip(np.round(rows / 16), -8, 7).astype(np.float32).T) * scales[None, :]

    out = {'books': n, 'dims': d, 'questions': len(q), 'single': {}, 'cascade': {}}
    for k in (60, 120):
        gt = top(exact, k)
        for name, s in (('ham', ham), ('asym', asym), ('asym_c', asym_c), ('int4', int4)):
            for keep in (500, 1000, 2000, 5000, 10000):
                out['single'][f'{name} top{k} N={keep}'] = recall(gt, top(s, keep))
        for n1, n2 in ((10000, 1000), (10000, 2000), (20000, 2000)):
            c1 = top(ham, n1)
            c2 = [c[np.argpartition(-asym[i, c], n2)[:n2]] for i, c in enumerate(c1)]
            out['cascade'][f'ham {n1} -> asym {n2}, top{k}'] = recall(gt, c2)

    # numpy hamming over packed bits, one question at a time (only a rough cost; the server would use Java)
    packed = np.packbits(bits, axis=1).view(np.uint64)
    qpacked = np.packbits(qbits, axis=1).view(np.uint64)
    lut = np.array([bin(i).count('1') for i in range(256)], np.uint8)
    t = []
    for qp in qpacked:
        t0 = time.perf_counter()
        x = np.bitwise_xor(packed, qp).view(np.uint8)
        dist = lut[x].reshape(n, -1).sum(1)
        np.argpartition(dist, 10000)[:10000]
        t.append((time.perf_counter() - t0) * 1000)
    out['numpy_hamming_ms'] = {'median': round(float(np.median(t)), 1), 'p90': round(float(np.percentile(t, 90)), 1)}

    json.dump(out, open(out_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    for part in ('single', 'cascade'):
        for name, r in out[part].items():
            print(f'{name:32s} mean {r["mean"]:.4f} p10 {r["p10"]:.3f} min {r["min"]:.3f}')
    print('numpy hamming ms', out['numpy_hamming_ms'])


if __name__ == '__main__':
    main(*sys.argv[1:4])
