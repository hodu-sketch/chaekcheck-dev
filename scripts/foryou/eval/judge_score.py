"""judge-out-XX.tsv + judge-pairs.tsv -> mean score, share of 4-5, same-work share per variant."""
import glob, json
from collections import defaultdict
from pathlib import Path
D = Path(__file__).resolve().parent
variant = {l.split('\t')[0]: l.split('\t')[1] for l in open(D / 'judge-pairs.tsv', encoding='utf-8')}
got = {}
for p in sorted(glob.glob(str(D / 'judge-out-*.tsv'))):
    for l in open(p, encoding='utf-8'):
        f = l.strip().split('\t')
        if len(f) >= 3 and f[0] in variant and f[1].strip() in '12345' and f[1].strip():
            got[f[0]] = (int(f[1]), f[2].strip() == '1')
s = defaultdict(list)
for pid, (score, same) in got.items():
    s[variant[pid]].append((score, same))
out = {v: {'n': len(x), 'mean': sum(a for a, _ in x) / len(x), 'good_4_5': sum(a >= 4 for a, _ in x) / len(x),
           'bad_1_2': sum(a <= 2 for a, _ in x) / len(x), 'same_work': sum(b for _, b in x) / len(x)} for v, x in s.items()}
out['missing'] = len(variant) - len(got)
(D / 'judge.json').write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding='utf-8')
print(json.dumps(out, ensure_ascii=False, indent=1))
