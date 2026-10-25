"""Per-shelf training counts and test F1 (KURE + logistic regression, threshold per slot from dev), to aim new questions."""
import json, os
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.multiclass import OneVsRestClassifier
HERE = os.path.dirname(os.path.abspath(__file__)); SP = os.path.join(HERE, '..')
T = json.load(open(os.path.join(SP, 'taxonomy2.json'), encoding='utf-8'))
def load(p): return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]
test = [r for k in range(25, 31) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl'))]
seen = {r['q'] for r in test}
train = [r for k in range(1, 25) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) if r['q'] not in seen]
z = np.load(os.path.join(HERE, 'kure-split.npz')); Vtr, Vte = z['tr'], z['te']
TH = {'field': 0.30, 'subfield': 0.10, 'genre': 0.10, 'mood': 0.15, 'form': 0.15}
LABELS = {'field': list(T['field']), 'subfield': [s for v in T['field'].values() for s in v], 'genre': T['genre'], 'mood': T['mood'], 'form': T['form']}
out = {}
for k, labs in LABELS.items():
    Y = np.array([[l in (r.get(k) or []) for l in labs] for r in train], dtype=int)
    G = np.array([[l in (r.get(k) or []) for l in labs] for r in test], dtype=int)
    keep = Y.sum(0) > 1
    m = OneVsRestClassifier(LogisticRegression(max_iter=3000, C=4)).fit(Vtr, Y[:, keep])
    P = np.zeros_like(G, dtype=float); P[:, keep] = m.predict_proba(Vte)
    pred = P >= TH[k]
    for j, l in enumerate(labs):
        tp = (pred[:, j] & (G[:, j] == 1)).sum(); fp = (pred[:, j] & (G[:, j] == 0)).sum(); fn = ((~pred[:, j]) & (G[:, j] == 1)).sum()
        out[f'{k}:{l}'] = {'train': int(Y[:, j].sum()), 'test': int(G[:, j].sum()), 'f1': float(2 * tp / max(2 * tp + fp + fn, 1))}
json.dump(out, open(os.path.join(HERE, 'weak.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
rows = sorted(out.items(), key=lambda x: (x[1]['f1'] if x[1]['test'] else 0.5, x[1]['train']))
print('weakest 25:'); [print(f"  {k:28} train {v['train']:4} test {v['test']:3} F1 {v['f1']*100:5.1f}%") for k, v in rows[:25]]
print('train count median per slot:', {k: int(np.median([v['train'] for kk, v in out.items() if kk.startswith(k + ':')])) for k in LABELS})
