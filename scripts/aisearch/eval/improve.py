"""Can the KURE classifier get closer to Haiku? On the same 600 test questions (Sonnet answers):
A learning curve: train on 500 / 1000 / 1500 / 2306 questions
B book transfer: also train on the 27,227 book vectors with their profile shelves (same KURE space as questions)
C hierarchy: small shelves only under the big shelves predicted
D threshold per slot chosen on dev questions (q-24) instead of 0.35"""
import glob, json, os
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.multiclass import OneVsRestClassifier
from sklearn.preprocessing import MultiLabelBinarizer
HERE = os.path.dirname(os.path.abspath(__file__)); SP = os.path.join(HERE, '..')
_REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
_PROJECTS = os.path.dirname(_REPO)  # the folder that holds this repo and chaekcheck-data
ML = os.path.join(_PROJECTS, r"chaekcheck-data\ml")
T = json.load(open(os.path.join(SP, 'taxonomy2.json'), encoding='utf-8'))
parent = {s: f for f, v in T['field'].items() for s in v}
def load(p): return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]
test = [r for k in range(25, 31) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl'))]
seen = {r['q'] for r in test}
train = [r for k in range(1, 25) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) if r['q'] not in seen]
z = np.load(os.path.join(HERE, 'kure-split.npz')); Vtr, Vte = z['tr'], z['te']
assert Vtr.shape[0] == len(train)
dev_mask = np.array([r['seed'] > 2300 for r in train])  # q-24 seeds 2301-2400
def f1(pred, gold):
    tp = sum(len(set(p) & set(g)) for p, g in zip(pred, gold)); fp = sum(len(set(p) - set(g)) for p, g in zip(pred, gold))
    fn = sum(len(set(g) - set(p)) for p, g in zip(pred, gold)); return 2 * tp / max(2 * tp + fp + fn, 1)
SLOTS = ['field', 'subfield', 'genre', 'mood']
def fit(X, ys, labels):
    mlb = MultiLabelBinarizer(classes=labels).fit([labels]); Y = mlb.transform(ys)
    keep = Y.sum(0) > 1
    m = OneVsRestClassifier(LogisticRegression(max_iter=3000, C=4)).fit(X, Y[:, keep])
    return m, np.array(labels)[keep]
LABELS = {'field': [f for f in T['field']], 'subfield': list(parent), 'genre': T['genre'], 'mood': T['mood']}
def proba(m, cls, X, labels):
    P = np.zeros((X.shape[0], len(labels))); idx = {c: i for i, c in enumerate(labels)}
    for j, c in enumerate(cls): P[:, idx[c]] = m.predict_proba(X)[:, j]
    return P
def decide(P, labels, th): return [[labels[i] for i in np.where(row >= th)[0]] for row in P]
gold = {k: [r.get(k) or [] for r in test] for k in SLOTS}
# A
print('A learning curve (F1 on 600 test)')
for n in (500, 1000, 1500, len(train)):
    row = []
    for k in SLOTS:
        m, cls = fit(Vtr[:n], [r.get(k) or [] for r in train[:n]], LABELS[k])
        row.append(f1(decide(proba(m, cls, Vte, LABELS[k]), LABELS[k], 0.35), gold[k]))
    print(f'  {n:5} questions: ' + '  '.join(f'{k} {v*100:.1f}%' for k, v in zip(SLOTS, row)))
# B book vectors
ids = json.load(open(os.path.join(ML, 'pool-30000-intro.ids.json')))
BV = np.load(os.path.join(ML, 'pool-30000-intro.npy'))
prof = {}
for p in glob.glob(os.path.join(SP, 'profile3', 'out-*.jsonl')) + glob.glob(os.path.join(SP, 'profile4', 'out-*.jsonl')):
    for o in load(p): prof[int(o['id'])] = o
rows = [(i, prof[b]) for i, b in enumerate(ids) if b in prof]
print('\nbooks with vector and profile:', len(rows))
Xb = BV[[i for i, _ in rows]]
book_y = {'field': [[o['field']] if o.get('field') else [] for _, o in rows],
          'subfield': [[o['subfield']] if o.get('subfield') else [] for _, o in rows],
          'genre': [o.get('genre') or [] for _, o in rows], 'mood': [o.get('mood') or [] for _, o in rows]}
best = {}
for k in SLOTS:
    mq, cq = fit(Vtr[~dev_mask], [r.get(k) or [] for r, d in zip(train, dev_mask) if not d], LABELS[k])
    mb, cb = fit(Xb, book_y[k], LABELS[k])
    Pq_dev, Pb_dev = proba(mq, cq, Vtr[dev_mask], LABELS[k]), proba(mb, cb, Vtr[dev_mask], LABELS[k])
    gdev = [r.get(k) or [] for r, d in zip(train, dev_mask) if d]
    cands = []
    for w in (0.0, 0.3, 0.5, 0.7, 1.0):
        for th in np.arange(0.1, 0.7, 0.05):
            cands.append((f1(decide((1 - w) * Pq_dev + w * Pb_dev, LABELS[k], th), gdev), w, th))
    _, w, th = max(cands)
    mq, cq = fit(Vtr, [r.get(k) or [] for r in train], LABELS[k])
    Pq, Pb = proba(mq, cq, Vte, LABELS[k]), proba(mb, cb, Vte, LABELS[k])
    P = (1 - w) * Pq + w * Pb
    best[k] = (P, th)
    print(f'  {k:9} questions only @0.35: {f1(decide(Pq, LABELS[k], 0.35), gold[k])*100:.1f}%   books only: {f1(decide(Pb, LABELS[k], th), gold[k])*100:.1f}%   '
          f'mix w={w} th={th:.2f} (D): {f1(decide(P, LABELS[k], th), gold[k])*100:.1f}%')
# C hierarchy on top of the best mix
Pf, thf = best['field']; Ps, ths = best['subfield']
fields = decide(Pf, LABELS['field'], thf)
subs = [[s for s in row if parent[s] in fs] for row, fs in zip(decide(Ps, LABELS['subfield'], ths), fields)]
print(f"\nC subfield only under predicted fields: {f1(subs, gold['subfield'])*100:.1f}%")
