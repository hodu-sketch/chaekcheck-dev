"""Round 2: train on the first 2,306 questions plus the 3,000 aimed at weak shelves (q-31..60), same 600 test questions.
Logistic regression and the small net, threshold per slot from dev (q-24, left out of the dev-time model)."""
import json, os, sys, time
import numpy as np
import torch, torch.nn as nn
from sklearn.linear_model import LogisticRegression
from sklearn.multiclass import OneVsRestClassifier
_REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
_PROJECTS = os.path.dirname(_REPO)  # the folder that holds this repo and chaekcheck-data
ML = os.path.join(_PROJECTS, r"chaekcheck-data\ml")
os.environ["HF_HOME"] = os.path.join(ML, "hf"); os.environ["HF_HUB_OFFLINE"] = "1"
torch.manual_seed(0)
HERE = os.path.dirname(os.path.abspath(__file__)); SP = os.path.join(HERE, '..')
T = json.load(open(os.path.join(SP, 'taxonomy2.json'), encoding='utf-8'))
def load(p): return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]
test = [r for k in range(25, 31) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl'))]
seen = {r['q'] for r in test}
files = list(range(1, 25)) + list(range(31, 61))
train = [r for k in files if os.path.exists(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) if r['q'] not in seen]
print('train', len(train), 'test', len(test))
z = np.load(os.path.join(HERE, 'kure-split.npz'))
old = {r['q']: v for r, v in zip([r for k in range(1, 25) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) if r['q'] not in seen], z['tr'])}
cache = os.path.join(HERE, 'kure-new.npz')
if os.path.exists(cache):
    extra = [r for k in range(31, 61) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) if r['q'] not in seen]
    old.update({r['q']: v for r, v in zip(extra, np.load(cache)['v'])})
new = [r['q'] for r in train if r['q'] not in old]
if new:
    from sentence_transformers import SentenceTransformer
    nv = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu").encode(new, normalize_embeddings=True, batch_size=32)
    old.update(dict(zip(new, nv)))
Vtr = np.array([old[r['q']] for r in train], dtype=np.float32); Vte = z['te']
dev = np.array([r['seed'] > 2300 and r['seed'] <= 2400 for r in train])
LABELS = {'field': list(T['field']), 'subfield': [s for v in T['field'].values() for s in v], 'genre': T['genre'], 'mood': T['mood']}
def f1(pred, gold):
    tp = sum(len(set(p) & set(g)) for p, g in zip(pred, gold)); fp = sum(len(set(p) - set(g)) for p, g in zip(pred, gold))
    fn = sum(len(set(g) - set(p)) for p, g in zip(pred, gold)); return 2 * tp / max(2 * tp + fp + fn, 1)
def Y(rows, k, labs): return np.array([[1.0 if l in (r.get(k) or []) else 0.0 for l in labs] for r in rows], dtype=np.float32)
def lr(X, Yt):
    keep = Yt.sum(0) > 1
    m = OneVsRestClassifier(LogisticRegression(max_iter=3000, C=4)).fit(X, Yt[:, keep])
    def f(A):
        P = np.zeros((len(A), Yt.shape[1])); P[:, keep] = m.predict_proba(A); return P
    return f
def mlp(X, Yt, epochs=60):
    m = nn.Sequential(nn.Linear(1024, 512), nn.ReLU(), nn.Dropout(0.3), nn.Linear(512, Yt.shape[1]))
    opt = torch.optim.AdamW(m.parameters(), lr=1e-3, weight_decay=1e-4); lossf = nn.BCEWithLogitsLoss()
    Xt, Yt = torch.tensor(X), torch.tensor(Yt)
    for _ in range(epochs):
        perm = torch.randperm(len(Xt))
        for i in range(0, len(Xt), 64):
            b = perm[i:i + 64]; opt.zero_grad(); lossf(m(Xt[b]), Yt[b]).backward(); opt.step()
    m.eval(); return lambda A: torch.sigmoid(m(torch.tensor(A))).detach().numpy()
res = {}
if len(sys.argv) > 1: LABELS = {k: v for k, v in LABELS.items() if k in sys.argv[1:]}
for k, labs in LABELS.items():
    for name, fn in (('LR', lr), ('MLP', mlp)):
        tr_rows = [r for r, d in zip(train, dev) if not d]; dv_rows = [r for r, d in zip(train, dev) if d]
        p_dev = fn(Vtr[~dev], Y(tr_rows, k, labs))(Vtr[dev])
        th = max(np.arange(0.05, 0.7, 0.05), key=lambda t: f1([[labs[i] for i in np.where(row >= t)[0]] for row in p_dev], [r.get(k) or [] for r in dv_rows]))
        p = fn(Vtr, Y(train, k, labs))(Vte)
        s = f1([[labs[i] for i in np.where(row >= th)[0]] for row in p], [r.get(k) or [] for r in test])
        res[f'{k}|{name}'] = s
        print(f'{k:9} {name:4} th={th:.2f}: {s*100:.1f}%', flush=True)
json.dump(res, open(os.path.join(HERE, 'retrain2-' + '-'.join(LABELS) + '.json'), 'w'), indent=1)
