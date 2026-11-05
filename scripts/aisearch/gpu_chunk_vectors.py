"""The chunk vectors of build_chunk_vectors.py on a rented GPU (user 2026-10-31: the PC CPU needed ~59 h more; "PC에만
둔다" = the intros are only STORED on the PC, so a rented GPU may read them while it works and must delete them after).

The pieces are cut here on the PC exactly as build_chunk_vectors.py cuts them (same tokenizer, pack() and head()), so
the GPU only encodes ready texts with the same model in float32 and the parts are the same as the PC's.

  python -X utf8 scripts/aisearch/gpu_chunk_vectors.py pack            # PC: gpu-in/texts.jsonl.gz, the missing parts
                                                                        #     plus one finished part to compare
  python3 gpu_chunk_vectors.py encode texts.jsonl.gz OUTDIR            # GPU: OUTDIR/part-NNN.npy + .owners.json
  python -X utf8 scripts/aisearch/gpu_chunk_vectors.py take OUTDIR     # PC: compare the check part, then move the
                                                                        #     other parts into chunk512-parts
"""
from __future__ import annotations

import gzip
import json
import os
import sys
import time
from pathlib import Path

MODEL = 'nlpai-lab/KURE-v1'
SIZE = 512


def pack() -> int:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import build_chunk_vectors as b
    from transformers import AutoTokenizer
    tok = AutoTokenizer.from_pretrained(b.MODEL)

    def count(s: str) -> int:
        return len(tok(s, add_special_tokens=False)['input_ids'])

    todo, _ = b.books()
    parts = [todo[i:i + b.PART] for i in range(0, len(todo), b.PART)]
    done = [n for n in range(len(parts)) if (b.PARTS / f'part-{n:03d}.npy').exists()]
    missing = [n for n in range(len(parts)) if n not in done]
    check = done[-1] if done else None
    out = b.OUT / 'gpu-in'
    out.mkdir(exist_ok=True)
    pieces = 0
    with gzip.open(out / 'texts.jsonl.gz', 'wt', encoding='utf-8') as f:
        for n in ([check] if check is not None else []) + missing:
            texts, owners = [], []
            for book_id, head, intro in parts[n]:
                for piece in b.e.pack(intro, SIZE - count(head) - 3, count):
                    texts.append(f'{head}\n{piece}')
                    owners.append(book_id)
            f.write(json.dumps({'part': n, 'check': n == check, 'texts': texts, 'owners': owners}, ensure_ascii=False) + '\n')
            pieces += len(texts)
    print(f'check part {check}, {len(missing)} missing parts ({missing[0]}..{missing[-1]}), {pieces} pieces -> {out}')
    return 0


def encode(src: str, outdir: str) -> int:
    import numpy as np
    from sentence_transformers import SentenceTransformer
    out = Path(outdir)
    out.mkdir(parents=True, exist_ok=True)
    model = SentenceTransformer(MODEL, device='cuda')
    model.max_seq_length = SIZE + 8
    with gzip.open(src, 'rt', encoding='utf-8') as f:
        for line in f:
            part = json.loads(line)
            n = part['part']
            if (out / f'part-{n:03d}.npy').exists():
                continue
            t = time.monotonic()
            vectors = model.encode(part['texts'], batch_size=64, normalize_embeddings=True, show_progress_bar=False,
                                   convert_to_numpy=True).astype('float32')
            np.save(out / f'part-{n:03d}.tmp.npy', vectors)
            os.replace(out / f'part-{n:03d}.tmp.npy', out / f'part-{n:03d}.npy')
            (out / f'part-{n:03d}.owners.json').write_text(json.dumps(part['owners']))
            print(f'part {n}: {len(part["texts"])} pieces in {time.monotonic() - t:.0f}s', flush=True)
    print('all parts encoded', flush=True)
    return 0


def take(outdir: str) -> int:
    import numpy as np
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import build_chunk_vectors as b
    src = Path(outdir)
    made = sorted(src.glob('part-*.npy'))
    report = {}
    for path in made:
        n = path.name[5:8]
        mine = b.PARTS / path.name
        theirs = np.load(path)
        owners = json.loads((src / f'part-{n}.owners.json').read_text())
        if mine.exists():
            local = np.load(mine)
            same_owners = owners == json.loads((b.PARTS / f'part-{n}.owners.json').read_text())
            cos = (local * theirs).sum(axis=1)
            report[n] = {'pieces': len(owners), 'same_owners': same_owners,
                         'cos_min': float(cos.min()), 'cos_mean': float(cos.mean())}
            print(f'part {n} exists here: {report[n]}')
            if not same_owners or cos.min() < 0.99:
                raise SystemExit(f'part {n} differs from the PC part: nothing moved')
    for path in made:
        n = path.name[5:8]
        if not (b.PARTS / path.name).exists():
            os.replace(src / f'part-{n}.owners.json', b.PARTS / f'part-{n}.owners.json')
            os.replace(path, b.PARTS / path.name)
    (b.OUT / 'gpu-take.json').write_text(json.dumps(report, indent=1))
    print(f'moved the parts the PC did not have; compared {len(report)}')
    return 0


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    command = sys.argv[1] if len(sys.argv) > 1 else ''
    if command == 'pack':
        sys.exit(pack())
    if command == 'encode':
        sys.exit(encode(sys.argv[2], sys.argv[3]))
    if command == 'take':
        sys.exit(take(sys.argv[2]))
    raise SystemExit(__doc__)
