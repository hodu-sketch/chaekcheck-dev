"""Pairs for the Haiku judge over four ordering variants: 50 mixed users, picks at places 1, 5, 10, 15, 20 each, the
base book nearest to the pick (by vector) as book A. Writes order-pairs.tsv and order-in-XX.tsv (variant hidden)."""
import random, sys
import order_eval as oe
from pathlib import Path
D = Path(__file__).resolve().parent
NAMES = ['지금 운영(보유 많은 순)', '유사도 순(돌아가며)', '유사도 순 + 겹침 줄이기', '유사도 순 + 겹침 + 분야']
rng = random.Random(11)
users = rng.sample(oe.base.users, 50)
pairs = []
for v in NAMES:
    for bases in users:
        recs = oe.VARIANTS[v](bases)
        for place in (1, 5, 10, 15, 20):
            if place > len(recs):
                continue
            r = recs[place - 1]
            src = max(bases, key=lambda b: float(oe.V[oe.row[b]] @ oe.V[oe.row[r]]) if b in oe.row and r in oe.row else -1)
            pairs.append((v, src, r))
rng.shuffle(pairs)
books = oe.base.books
desc = {}
for l in open(D / 'blurbs.tsv', encoding='utf-8'):
    a, b = l.rstrip('\n').split('\t', 1); desc[int(a)] = b
for l in open(Path(sys.argv[2]) / 'intro-books.tsv', encoding='utf-8'):
    p = l.rstrip('\n').split('\t')
    if len(p) >= 4 and p[0].isdigit() and int(p[0]) not in desc:
        desc[int(p[0])] = p[3][:160]
def line(b):
    t, a, _ = books.get(b, ('?', '?', 0))
    return f'{t} / {a} / {desc.get(b, "(소개 없음)")}'.replace('\t', ' ')
with open(D / 'order-pairs.tsv', 'w', encoding='utf-8', newline='\n') as f:
    for i, (v, s, r) in enumerate(pairs, 1):
        f.write(f'{i}\t{v}\t{s}\t{r}\n')
for c in range(0, len(pairs), 250):
    with open(D / f'order-in-{c // 250:02d}.tsv', 'w', encoding='utf-8', newline='\n') as f:
        for i, (v, s, r) in enumerate(pairs[c:c + 250], c + 1):
            f.write(f'{i}\t{line(s)}\t{line(r)}\n')
print(len(pairs))
