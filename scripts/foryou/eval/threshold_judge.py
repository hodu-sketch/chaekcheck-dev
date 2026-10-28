"""Blind Haiku pairs: closeness order vs threshold 3 and 5 (joinable plans), 50 users × places 1,5,10,15,20."""
import random
import avail_eval as ae
from pathlib import Path
D = Path(__file__).resolve().parent
rng = random.Random(43); idx = rng.sample(range(len(ae.base.users)), 50)
pairs = []
for name, T in (('T1', 1), ('T3', 3), ('T5', 5)):
    for i in idx:
        bases = ae.base.users[i]; plan = ae.joinable[ae.user_region[i]]
        top = [r for r in ae.eligible(ae.rr(bases), bases, plan) if r[2] or r[1] >= T][:20]
        for place in (1, 5, 10, 15, 20):
            if place <= len(top):
                r = top[place - 1][0]
                src = next((b for b in bases if r in ae.neigh.get(b, [])), bases[0])
                pairs.append((name, src, r))
rng.shuffle(pairs)
books = {}
for l in open(D / 'books.tsv', encoding='utf-8'):
    p = l.rstrip('\n').split('\t'); books[p[0]] = (p[1], p[2])
desc = {}
for l in open(D / 'blurbs.tsv', encoding='utf-8'):
    a, b = l.rstrip('\n').split('\t', 1); desc[a] = b
def line(b):
    b = str(b); t, a = books.get(b, ('?', '?')); return f'{t} / {a} / {desc.get(b, "(소개 없음)")}'.replace('\t', ' ')
with open(D / 'thr-pairs.tsv', 'w', encoding='utf-8', newline='\n') as f:
    for i, (v, s, r) in enumerate(pairs, 1): f.write(f'{i}\t{v}\t{s}\t{r}\n')
for c in range(0, len(pairs), 250):
    with open(D / f'thr-in-{c // 250:02d}.tsv', 'w', encoding='utf-8', newline='\n') as f:
        for i, (v, s, r) in enumerate(pairs[c:c + 250], c + 1): f.write(f'{i}\t{line(s)}\t{line(r)}\n')
print(len(pairs))
