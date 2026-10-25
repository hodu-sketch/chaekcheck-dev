"""KURE vector (frozen) + a small 2-layer neural net per slot (one hidden layer of 512), threshold per slot from dev
(q-24), vs the logistic regression with the same thresholds. Same 600 test questions."""
import json, os, time
import numpy as np
import torch, torch.nn as nn
torch.manual_seed(0)
HERE = os.path.dirname(os.path.abspath(__file__)); SP = os.path.join(HERE, '..')
T = json.load(open(os.path.join(SP, 'taxonomy2.json'), encoding='utf-8'))
def load(p): return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]
test = [r for k in range(25, 31) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl'))]
seen = {r['q'] for r in test}
train = [r for k in range(1, 25) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) if r['q'] not in seen]
z = np.load(os.path.join(HERE, 'kure-split.npz')); Vtr, Vte = z['tr'], z['te']
dev = np.array([r['seed'] > 2300 for r in train])
LABELS = {'field': list(T['field']), 'subfield': [s for v in T['field'].values() for s in v], 'genre': T['genre'], 'mood': T['mood']}
def f1(pred, gold):
    tp = sum(len(set(p) & set(g)) for p, g in zip(pred, gold)); fp = sum(len(set(p) - set(g)) for p, g in zip(pred, gold))
    fn = sum(len(set(g) - set(p)) for p, g in zip(pred, gold)); return 2 * tp / max(2 * tp + fp + fn, 1)
def Y(rows, labs): return np.array([[1.0 if l in (r.get(k_) or []) else 0.0 for l in labs] for r in rows], dtype=np.float32)
def net(X, Yt, epochs=60):
    m = nn.Sequential(nn.Linear(1024, 512), nn.ReLU(), nn.Dropout(0.3), nn.Linear(512, Yt.shape[1]))
    opt = torch.optim.AdamW(m.parameters(), lr=1e-3, weight_decay=1e-4); lossf = nn.BCEWithLogitsLoss()
    X, Yt = torch.tensor(X), torch.tensor(Yt)
    for _ in range(epochs):
        perm = torch.randperm(len(X))
        for i in range(0, len(X), 64):
            b = perm[i:i + 64]; opt.zero_grad(); lossf(m(X[b]), Yt[b]).backward(); opt.step()
    m.eval(); return lambda A: torch.sigmoid(m(torch.tensor(A))).detach().numpy()
for k_ in LABELS:
    labs = LABELS[k_]
    t0 = time.perf_counter()
    p_dev = net(Vtr[~dev], Y([r for r, d in zip(train, dev) if not d], labs))(Vtr[dev])
    gdev = [r.get(k_) or [] for r, d in zip(train, dev) if d]
    th = max(np.arange(0.05, 0.7, 0.05), key=lambda t: f1([[labs[i] for i in np.where(row >= t)[0]] for row in p_dev], gdev))
    p = net(Vtr, Y(train, labs))(Vte)
    pred = [[labs[i] for i in np.where(row >= th)[0]] for row in p]
    print(f'{k_:9} MLP th={th:.2f}: {f1(pred, [r.get(k_) or [] for r in test])*100:.1f}%  ({time.perf_counter()-t0:.0f}s train)')
