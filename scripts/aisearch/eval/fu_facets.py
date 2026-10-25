"""Follow-up test: KURE vectors and MLP shelves (same models as predict_eval.py) for each follow-up's current question
alone (q2) and for the previous question + current question joined (cat), plus the routing MLP (route-v2-mlp.pt) on q2.
Writes fu/facets.json {id: {"q2": facets, "cat": facets, "route": …, "route_p": …}} and fu/vec.npz."""
import json, os
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__)); SP = os.path.join(HERE, '..')
src = open(os.path.join(HERE, 'predict_eval.py'), encoding='utf-8').read().split("\nout = {}\n")[0]
src = src.replace('Vev = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu").encode([q[\'q\'] for q in evq], normalize_embeddings=True).astype(np.float32)', 'km = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu")')
exec(src)
FU = json.load(open(os.path.join(SP, 'fu', 'all.json'), encoding='utf-8'))
B = {b['id']: b for b in json.load(open(os.path.join(SP, 'fu', 'base.json'), encoding='utf-8'))}
texts = {'q2': [f['q2'] for f in FU], 'cat': [B[f['base']]['q1'] + ' ' + f['q2'] for f in FU]}
VV = {k: km.encode(v, normalize_embeddings=True, batch_size=64).astype(np.float32) for k, v in texts.items()}
np.savez(os.path.join(SP, 'fu', 'vec.npz'), ids=np.array([f['id'] for f in FU]), **VV)
res = {f['id']: {} for f in FU}
RR = {kind: [{} for _ in FU] for kind in VV}
for k, labs in MULTI.items():
    Y = np.array([[1 if l in get(r, k) else 0 for l in labs] for r in rows])
    pd = mlp(V[~dev], Y[~dev])(V[dev]); gd = [get(r, k) for r, d in zip(rows, dev) if d]
    th = max(np.arange(0.05, 0.7, 0.05), key=lambda t: f1([[labs[i] for i in np.where(x >= t)[0]] for x in pd], gd))
    f = mlp(V, Y)
    for kind, Vev in VV.items():
        for i, x in enumerate(f(Vev)): RR[kind][i][k] = [labs[j] for j in np.where(x >= th)[0]][:2]
for k, labs in SINGLE.items():
    y = np.array([labs.index(sget(r, k)) if sget(r, k) in labs else 0 for r in rows])
    f = mlp(V, np.eye(len(labs))[y], multi=False)
    for kind, Vev in VV.items():
        for i, v in enumerate(f(Vev).argmax(1)): RR[kind][i][k] = labs[int(v)]
for kind in VV:
    for fu, r in zip(FU, RR[kind]):
        r['fiction'] = {'True': True, 'False': False}.get(r['fiction'])
        r['exclude'] = {'field': r.pop('ex_field'), 'subfield': r.pop('ex_subfield'), 'genre': r.pop('ex_genre')}
        res[fu['id']][kind] = r
ck = torch.load(os.path.join(SP, 'route', 'route-v2-mlp.pt'))
m = nn.Sequential(nn.Linear(1024, 512), nn.ReLU(), nn.Dropout(0.3), nn.Linear(512, 4)); m.load_state_dict(ck['state_dict']); m.eval()
P = torch.softmax(m(torch.tensor(VV['q2'])), 1).detach().numpy()
for f, p in zip(FU, P):
    k = int(np.argmax(p)); res[f['id']]['route'] = ck['routes'][k] if k == 3 or p[k] >= ck['tau'] else 'book'
    res[f['id']]['route_p'] = [round(float(x), 3) for x in p]
json.dump(res, open(os.path.join(SP, 'fu', 'facets.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
from collections import Counter
print(Counter((f['kind'], res[f['id']]['route']) for f in FU))
