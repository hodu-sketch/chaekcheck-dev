"""Book vectors for the AI search's vector candidates (user 2026-10-25, report docs/features/aisearch/reports/2026-10-25-eval.md):
the KURE-v1 vectors the crawl PC made for the neighbours (title, author, publisher, year + 300 intro characters, 1024
dimensions, normalised), each row cut to int8 with its own scale, in one little-endian file the backend reads into
memory (AiSearchVectors):

    magic  b"CCBV1\\n"          6 bytes
    count  int32, dim int32
    ids    count x int64       book ids (catalog ids of the export the vectors were made from)
    scales count x float32     row i = int8 row i x scales[i]
    rows   count x dim int8

usage: make_book_vectors.py OUT.bin [--ml DIR] [--pool pool-30000-intro|pool-100000-intro]   (DIR: the data folder's ml\\, default next to this repository)
The rows of foryou\\vectors.npy (books made with the neighbours) win over the pool vectors for the same book.
Prints the count, the file size and how far int8 is from float32 (worst cosine)."""
import argparse
import json
import os
import struct

import numpy as np

parser = argparse.ArgumentParser()
parser.add_argument("out")
parser.add_argument("--ml", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..",
                                                 "chaekcheck-data", "ml"))
parser.add_argument("--pool", default="pool-30000-intro",
                    help="the pool vectors in DIR (pool-100000-intro: the 100,000 books, scripts/aisearch/build_100k_vectors.py)")
args = parser.parse_args()

ids, rows = [], []
seen = set()
for name in (os.path.join("foryou", "vectors"), args.pool):
    part_ids = json.load(open(os.path.join(args.ml, name + ".ids.json")))
    part = np.load(os.path.join(args.ml, name + ".npy")).astype(np.float32)
    for i, book_id in enumerate(part_ids):
        if book_id not in seen:
            seen.add(book_id)
            ids.append(book_id)
            rows.append(part[i])
matrix = np.vstack(rows)
matrix /= np.linalg.norm(matrix, axis=1, keepdims=True)
scales = np.abs(matrix).max(axis=1) / 127.0
q = np.round(matrix / scales[:, None]).clip(-127, 127).astype(np.int8)

back = q.astype(np.float32) * scales[:, None]
cos = (back * matrix).sum(axis=1) / np.linalg.norm(back, axis=1)

with open(args.out, "wb") as f:
    f.write(b"CCBV1\n")
    f.write(struct.pack("<ii", len(ids), matrix.shape[1]))
    f.write(np.asarray(ids, dtype="<i8").tobytes())
    f.write(scales.astype("<f4").tobytes())
    f.write(q.tobytes())
print(json.dumps({"books": len(ids), "dim": int(matrix.shape[1]), "mb": round(os.path.getsize(args.out) / 2**20, 1),
                  "int8_worst_cosine": round(float(cos.min()), 5)}))
