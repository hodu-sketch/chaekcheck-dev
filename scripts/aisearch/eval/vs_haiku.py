"""Haiku (same labelling rules, qlabel/haiku-*.jsonl) vs the classifiers, scored against the Sonnet answers."""
import json, os, glob
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__)); SP = os.path.join(HERE, '..')
def load(p): return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]
test = [r for k in range(25, 31) for r in load(os.path.join(SP, 'qgen', f'q-{k}.jsonl'))]
ev = load(os.path.join(SP, 'qlabel', 'sonnet-E1.jsonl'))
H = {}
for p in glob.glob(os.path.join(SP, 'qlabel', 'haiku-*.jsonl')):
    for o in load(p): H[str(o['id'])] = o
SINGLE = ['notBook', 'titleLike', 'refersToShown', 'wants_exclusion', 'fiction', 'audience', 'level']
MULTI = ['field', 'subfield', 'genre', 'mood']
def sval(r, k):
    if k == 'wants_exclusion': return str(any(v for v in (r.get('exclude') or {}).values()))
    v = r.get(k); return str(v if v not in ('',) else '')
def f1(pred, gold):
    tp = sum(len(set(p) & set(g)) for p, g in zip(pred, gold)); fp = sum(len(set(p) - set(g)) for p, g in zip(pred, gold))
    fn = sum(len(set(g) - set(p)) for p, g in zip(pred, gold)); return 2 * tp / max(2 * tp + fp + fn, 1)
C = json.load(open(os.path.join(HERE, 'pred-classifiers.json'), encoding='utf-8'))
R = json.load(open(os.path.join(HERE, 'train-results.json')))
for split, rows, ids in (('test', test, [f"s{r['seed']}" for r in test]), ('ev', ev, [str(r['id']) for r in ev])):
    hp = [H.get(i) for i in ids]
    miss = sum(1 for x in hp if x is None)
    out = {}
    for k in SINGLE: out[k] = np.mean([h is not None and sval(h, k) == sval(r, k) for h, r in zip(hp, rows)])
    for k in MULTI: out[k] = f1([(h or {}).get(k) or [] for h in hp], [r.get(k) or [] for r in rows])
    print(f"\n== {split} ({len(rows)} questions, Haiku missing {miss})")
    print(f"{'slot':16}{'TF-IDF':>10}{'KURE':>10}{'Haiku':>10}")
    for k in SINGLE + MULTI:
        print(f"{k:16}{R[f'TF-IDF|{split}'][k]*100:9.1f}%{R[f'KURE|{split}'][k]*100:9.1f}%{out[k]*100:9.1f}%")
    json.dump(out, open(os.path.join(HERE, f'haiku-{split}.json'), 'w'), indent=1)
