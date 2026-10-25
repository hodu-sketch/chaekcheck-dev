"""LR and MLP trained on all 6,298 training questions (threshold per slot from dev q-24), predicting the shelves of the
57 eval questions in the kw-facets2.json shape plus exclude: facets-lr.json, facets-mlp.json."""
import json, os
import numpy as np
import torch, torch.nn as nn
from sklearn.linear_model import LogisticRegression
from sklearn.multiclass import OneVsRestClassifier
torch.set_num_threads(12)
ML = r"C:\Users\yujun\Documents\Claude\Projects\chaekcheck-data\ml"
os.environ["HF_HOME"] = os.path.join(ML, "hf"); os.environ["HF_HUB_OFFLINE"] = "1"
HERE = os.path.dirname(os.path.abspath(__file__)); SP = os.path.join(HERE, '..')
T = json.load(open(os.path.join(SP, 'taxonomy2.json'), encoding='utf-8'))
def load(p): return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]
test = [r for k in range(25, 31) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl'))]
seen = {r['q'] for r in test}
evq = json.load(open(os.path.join(SP, 'eval_questions.json'), encoding='utf-8'))
base = [r for k in range(1, 25) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) if r['q'] not in seen]
extra = [r for k in range(31, 71) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) if r['q'] not in seen]
rows = base + extra
z = np.load(os.path.join(HERE, 'kure-split.npz')); V = np.vstack([z['tr'], np.load(os.path.join(HERE, 'kure-new.npz'))['v'][:len(extra)]]).astype(np.float32)
from sentence_transformers import SentenceTransformer
Vev = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu").encode([q['q'] for q in evq], normalize_embeddings=True).astype(np.float32)
dev = np.array([2300 < r['seed'] <= 2400 for r in rows])
subs = [s for v in T['field'].values() for s in v]
MULTI = {'field': list(T['field']), 'subfield': subs, 'genre': T['genre'], 'form': T['form'],
         'ex_field': list(T['field']), 'ex_subfield': subs, 'ex_genre': T['genre']}
SINGLE = {'fiction': ['True', 'False', 'None'], 'country': [''] + T['country'], 'audience': [''] + T['audience'], 'level': [''] + T['level']}
def get(r, k):
    if k.startswith('ex_'): return (r.get('exclude') or {}).get(k[3:]) or []
    return r.get(k) or []
def sget(r, k): return str(r.get(k)) if k == 'fiction' else (r.get(k) or '')
def f1(pred, gold):
    tp = sum(len(set(p) & set(g)) for p, g in zip(pred, gold)); fp = sum(len(set(p) - set(g)) for p, g in zip(pred, gold))
    fn = sum(len(set(g) - set(p)) for p, g in zip(pred, gold)); return 2 * tp / max(2 * tp + fp + fn, 1)
def lr(X, Y):
    keep = Y.sum(0) > 1
    m = OneVsRestClassifier(LogisticRegression(max_iter=3000, C=4), n_jobs=8).fit(X, Y[:, keep])
    def f(A):
        P = np.zeros((len(A), Y.shape[1])); P[:, keep] = m.predict_proba(A); return P
    return f
def mlp(X, Y, epochs=60, multi=True):
    torch.manual_seed(0)
    m = nn.Sequential(nn.Linear(1024, 512), nn.ReLU(), nn.Dropout(0.3), nn.Linear(512, Y.shape[1]))
    opt = torch.optim.AdamW(m.parameters(), lr=1e-3, weight_decay=1e-4)
    lossf = nn.BCEWithLogitsLoss() if multi else nn.CrossEntropyLoss()
    Xt, Yt = torch.tensor(X), torch.tensor(Y.astype(np.float32))
    for _ in range(epochs):
        perm = torch.randperm(len(Xt))
        for i in range(0, len(Xt), 64):
            b = perm[i:i + 64]; opt.zero_grad(); lossf(m(Xt[b]), Yt[b]).backward(); opt.step()
    m.eval()
    return lambda A: (torch.sigmoid if multi else (lambda t: torch.softmax(t, 1)))(m(torch.tensor(A))).detach().numpy()
out = {}
for name in ('lr', 'mlp'):
    res = [{'id': q['id']} for q in evq]
    for k, labs in MULTI.items():
        Y = np.array([[1 if l in get(r, k) else 0 for l in labs] for r in rows])
        fit = lr if name == 'lr' else mlp
        pd = fit(V[~dev], Y[~dev])(V[dev]); gd = [get(r, k) for r, d in zip(rows, dev) if d]
        th = max(np.arange(0.05, 0.7, 0.05), key=lambda t: f1([[labs[i] for i in np.where(x >= t)[0]] for x in pd], gd))
        P = fit(V, Y)(Vev)
        for i, x in enumerate(P): res[i][k] = [labs[j] for j in np.where(x >= th)[0]][:2]
    for k, labs in SINGLE.items():
        y = np.array([labs.index(sget(r, k)) if sget(r, k) in labs else 0 for r in rows])
        if name == 'lr':
            m = LogisticRegression(max_iter=3000, C=4).fit(V, y); pr = m.predict(Vev)
        else:
            Y = np.eye(len(labs))[y]; pr = mlp(V, Y, multi=False)(Vev).argmax(1)
        for i, v in enumerate(pr): res[i][k] = labs[int(v)]
    for r in res:
        r['fiction'] = {'True': True, 'False': False}.get(r['fiction'])
        r['exclude'] = {'field': r.pop('ex_field'), 'subfield': r.pop('ex_subfield'), 'genre': r.pop('ex_genre')}
    json.dump(res, open(os.path.join(SP, f'facets-{name}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
    print(name, 'done', res[50])
