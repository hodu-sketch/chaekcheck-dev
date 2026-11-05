"""Routing v2: one 4-way classifier (not_book / title_like / refers_shown / book) on the KURE vector.
Training = the 6,298 shelf-training questions (route from their flags) + the routing training questions
(route/t-*.jsonl, balanced). Dev = every 10th routing training question (threshold), test = the same 400 (route/all.json).
Safety: a non-book route is taken only if its probability >= tau (picked on dev so that book questions are almost never
blocked), else book. Variant +title: title_like kept only when the question matches a search book title/author the way
production TitleMatch.close does. Writes route/pred-v2-*.json and route/v2-scores.json."""
import glob, json, os, re, unicodedata
import numpy as np
import torch, torch.nn as nn
from sklearn.linear_model import LogisticRegression
torch.set_num_threads(12)
_REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
_PROJECTS = os.path.dirname(_REPO)  # the folder that holds this repo and chaekcheck-data
ML = os.path.join(_PROJECTS, r"chaekcheck-data\ml")
os.environ["HF_HOME"] = os.path.join(ML, "hf"); os.environ["HF_HUB_OFFLINE"] = "1"
HERE = os.path.dirname(os.path.abspath(__file__)); SP = os.path.join(HERE, '..')
def load(p): return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]
ROUTES = ['not_book', 'title_like', 'refers_shown', 'book']
test6 = [r for k in range(25, 31) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl'))]
seen = {r['q'] for r in test6}
gen = [r for k in list(range(1, 25)) + list(range(31, 71)) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) if r['q'] not in seen]
z = np.load(os.path.join(HERE, 'kure-split.npz'))
Vg = np.vstack([z['tr'], np.load(os.path.join(HERE, 'kure-new.npz'))['v'][:len(gen) - z['tr'].shape[0]]]).astype(np.float32)
yg = [0 if r.get('notBook') else 1 if r.get('titleLike') else 2 if r.get('refersToShown') else 3 for r in gen]
R = json.load(open(os.path.join(SP, 'route', 'all.json'), encoding='utf-8'))
testq = {r['q'] for r in R}
tr = [r for p in sorted(glob.glob(os.path.join(SP, 'route', 't-*.jsonl'))) for r in load(p)]
tr = [r for r in tr if r['q'] not in testq]
print('routing training', len(tr), {t: sum(r['type'] == t for r in tr) for t in ('not_book', 'title_like', 'refers_shown', 'hard_book')})
from sentence_transformers import SentenceTransformer
km = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu")
Vt = km.encode([r['q'] for r in tr], normalize_embeddings=True, batch_size=64).astype(np.float32)
VR = km.encode([r['q'] for r in R], normalize_embeddings=True, batch_size=64).astype(np.float32)
yt = [ROUTES.index('book' if r['type'] == 'hard_book' else r['type']) for r in tr]
dev = np.array([i % 10 == 0 for i in range(len(tr))])
def mlp(X, y, epochs=60):
    torch.manual_seed(0)
    m = nn.Sequential(nn.Linear(1024, 512), nn.ReLU(), nn.Dropout(0.3), nn.Linear(512, 4))
    opt = torch.optim.AdamW(m.parameters(), lr=1e-3, weight_decay=1e-4); lossf = nn.CrossEntropyLoss()
    Xt, Yt = torch.tensor(X), torch.tensor(y)
    for _ in range(epochs):
        perm = torch.randperm(len(Xt))
        for i in range(0, len(Xt), 64):
            b = perm[i:i + 64]; opt.zero_grad(); lossf(m(Xt[b]), Yt[b]).backward(); opt.step()
    m.eval(); return lambda A: torch.softmax(m(torch.tensor(A)), 1).detach().numpy(), m
def lr(X, y):
    m = LogisticRegression(max_iter=3000, C=4).fit(X, y); return lambda A: m.predict_proba(A), m
def letters(s): return re.sub(r'[\W_]', '', unicodedata.normalize('NFKC', s).lower())
books = []
for l in open(os.path.join(SP, 'search_books.tsv'), encoding='utf-8'):
    p = l.rstrip('\n').split('\t')
    if len(p) >= 3:
        books.append((letters(re.split(r'[(\[.:]', p[1], maxsplit=1)[0]), letters(p[2])))
def title_close(q):
    key = letters(q)
    if len(key) < 2: return False
    for t, a in books:
        if len(t) >= 2 and (t == key or (t.startswith(key) and len(key) * 2 >= len(t)) or (key.startswith(t) and a and key[len(t):] in a)): return True
        if a and key in a: return True
    return False
def route(P, tau, title_check=False, qs=None):
    out = []
    for i, p in enumerate(P):
        k = int(np.argmax(p))
        r = ROUTES[k] if k == 3 or p[k] >= tau else 'book'
        if title_check and r == 'title_like' and not title_close(qs[i]): r = 'book'
        out.append(r)
    return out
gold = ['book' if r['type'] == 'hard_book' else r['type'] for r in R]
res, keep = {}, {}
for name, fit in (('MLP', mlp), ('LR', lr)):
    X_dev_fit = np.vstack([Vg, Vt[~dev]]); y_dev_fit = np.array(yg + [y for y, d in zip(yt, dev) if not d])
    f, _ = fit(X_dev_fit, y_dev_fit)
    Pd = f(Vt[dev]); gd = [ROUTES[y] for y, d in zip(yt, dev) if d]
    best = None
    for tau in np.arange(0.3, 0.96, 0.05):
        rd = route(Pd, tau)
        blocked = sum(1 for g, r in zip(gd, rd) if g != 'not_book' and r == 'not_book')
        acc = np.mean([g == r for g, r in zip(gd, rd)])
        if blocked <= 1 and (best is None or acc > best[1]): best = (tau, acc)
    tau = best[0] if best else 0.9
    f, model = fit(np.vstack([Vg, Vt]), np.array(yg + yt))
    P = f(VR)
    for variant, tc in (('', False), ('+title', True)):
        rr = route(P, tau, tc, [r['q'] for r in R])
        acc = {t: np.mean([p == t for p, g in zip(rr, gold) if g == t]) for t in ROUTES}
        tot = np.mean([p == g for p, g in zip(rr, gold)])
        blocked = sum(1 for p, g in zip(rr, gold) if g != 'not_book' and p == 'not_book')
        res[name + variant] = {'tau': float(tau), 'all': float(tot), **{t: float(v) for t, v in acc.items()}, 'blocked_real': blocked}
        print(f"{name+variant:10} tau={tau:.2f} 전체 {tot*100:5.1f}%  " + '  '.join(f'{t} {acc[t]*100:5.1f}%' for t in ROUTES) + f"  | 막음 {blocked}")
        json.dump({r['id']: x for r, x in zip(R, rr)}, open(os.path.join(SP, 'route', f'pred-v2-{name}{variant}.json'), 'w'), indent=0)
    if name == 'MLP':
        torch.save({'state_dict': model.state_dict(), 'routes': ROUTES, 'tau': float(tau)}, os.path.join(SP, 'route', 'route-v2-mlp.pt'))
json.dump(res, open(os.path.join(SP, 'route', 'v2-scores.json'), 'w'), indent=1)
