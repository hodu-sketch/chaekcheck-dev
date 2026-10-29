"""book-vectors.bin -> a PostgreSQL binary COPY file for `book_vec(id bigint, embedding vector(1024))`
(docs/aisearch/reports/2026-10-29-hnsw.md 3절).

Each row is the server's int8 row times its scale (float32), i.e. exactly the vector BookVectorFile.nearest scores
against, so `embedding <#> q` ranks as the server does (up to float rounding).
usage: python make_copy_file.py MODEL_DIR/book-vectors.bin OUT.copy
"""
import struct, sys
import numpy as np

src, out = sys.argv[1], sys.argv[2]
raw = open(src, 'rb').read()
assert raw[:6] == b'CCBV1\n'
n, dim = struct.unpack_from('<ii', raw, 6)
p = 14
ids = np.frombuffer(raw, '<i8', n, p); p += 8 * n
scales = np.frombuffer(raw, '<f4', n, p); p += 4 * n
rows = np.frombuffer(raw, 'i1', n * dim, p).reshape(n, dim)
vec = (rows.astype(np.float32) * scales[:, None]).astype('>f4')
norms = np.linalg.norm(rows.astype(np.float32) * scales[:, None], axis=1)
print(f'{n} x {dim}; norm min {norms.min():.5f} max {norms.max():.5f}')

with open(out, 'wb') as f:
    f.write(b'PGCOPY\n\xff\r\n\x00' + struct.pack('>ii', 0, 0))
    vhead = struct.pack('>ihh', 4 + 4 * dim, dim, 0)  # field length, then pgvector's binary form: dim, unused, floats
    for i in range(n):
        f.write(struct.pack('>hiq', 2, 8, int(ids[i])))
        f.write(vhead)
        f.write(vec[i].tobytes())
    f.write(struct.pack('>h', -1))
print('wrote', out)
