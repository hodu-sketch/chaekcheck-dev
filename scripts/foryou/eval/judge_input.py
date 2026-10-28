"""judge-pairs.tsv -> judge-in-XX.tsv chunks (variant hidden, order shuffled) for Haiku agents."""
import csv, random, sys
from pathlib import Path
D = Path(__file__).resolve().parent
csv.field_size_limit(10**8)
books = {r[0]: (r[1], r[2]) for r in (l.rstrip('\n').split('\t') for l in open(D / 'books.tsv', encoding='utf-8'))}
desc = {r[0]: r[1] for r in (l.rstrip('\n').split('\t', 1) for l in open(D / 'blurbs.tsv', encoding='utf-8'))}
with open(Path(sys.argv[1]) / 'intro-books.tsv', encoding='utf-8') as f:
    next(f)
    for line in f:
        p = line.rstrip('\n').split('\t')
        if len(p) >= 4 and p[0] not in desc:
            desc[p[0]] = p[3][:160]
pairs = [l.rstrip('\n').split('\t') for l in open(D / 'judge-pairs.tsv', encoding='utf-8')]
random.Random(5).shuffle(pairs)
def line(b):
    t, a = books.get(b, ('?', '?'))
    return f'{t} / {a} / {desc.get(b, "(소개 없음)")}'.replace('\t', ' ')
for i in range(0, len(pairs), 200):
    with open(D / f'judge-in-{i // 200:02d}.tsv', 'w', encoding='utf-8', newline='\n') as f:
        for pid, _v, src, rec in pairs[i:i + 200]:
            f.write(f'{pid}\t{line(src)}\t{line(rec)}\n')
print(len(pairs))
