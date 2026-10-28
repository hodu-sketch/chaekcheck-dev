"""Blind Haiku pairs: closeness order vs strong availability blend, joinable plans, 50 users × places 1,5,10,15,20."""
import random, sys
import avail_eval as ae
from pathlib import Path
D = Path(__file__).resolve().parent
rng = random.Random(41); idx = rng.sample(range(len(ae.base.users)), 50)
pairs = []
for v in ('유사도 순(지금)', '섞기 강하게(λ8, μ3)'):
    for i in idx:
        bases = ae.base.users[i]; plan = ae.joinable[ae.user_region[i]]
        top = ae.variant(v, ae.eligible(ae.rr(bases), bases, plan))[:20]
        for place in (1, 5, 10, 15, 20):
            if place <= len(top):
                r = top[place - 1][0]
                src = next((b for b in bases if r in ae.neigh.get(b, [])), bases[0])
                pairs.append((v, src, r))
rng.shuffle(pairs)
books = {}
for l in open(D / 'books.tsv', encoding='utf-8'):
    p = l.rstrip('\n').split('\t'); books[p[0]] = (p[1], p[2])
desc = {}
for l in open(D / 'blurbs.tsv', encoding='utf-8'):
    a, b = l.rstrip('\n').split('\t', 1); desc[a] = b
def line(b):
    b = str(b); t, a = books.get(b, ('?', '?')); return f'{t} / {a} / {desc.get(b, "(소개 없음)")}'.replace('\t', ' ')
with open(D / 'avail-pairs.tsv', 'w', encoding='utf-8', newline='\n') as f:
    for i, (v, s, r) in enumerate(pairs, 1): f.write(f'{i}\t{v}\t{s}\t{r}\n')
for c in range(0, len(pairs), 250):
    with open(D / f'avail-in-{c // 250:02d}.tsv', 'w', encoding='utf-8', newline='\n') as f:
        for i, (v, s, r) in enumerate(pairs[c:c + 250], c + 1): f.write(f'{i}\t{line(s)}\t{line(r)}\n')
print(len(pairs))
