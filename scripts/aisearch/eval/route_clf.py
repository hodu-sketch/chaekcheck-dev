"""Routing (책 찾기 아님 / 제목만 / 앞 책 가리킴 / 보통 검색) by LR and MLP on the KURE vector, trained on the 6,298
training questions (their notBook / titleLike / refersToShown), applied to the 400 routing test questions (route/all.json).
A question is routed to the first yes among notBook, titleLike, refersToShown (as the production flow checks them),
else 보통 검색. Threshold 0.5. Writes route/pred-lr.json, route/pred-mlp.json."""
import json, os
import numpy as np
import torch, torch.nn as nn
from sklearn.linear_model import LogisticRegression
torch.set_num_threads(12)
ML = r"C:\Users\yujun\Documents\Claude\Projects\chaekcheck-data\ml"
os.environ["HF_HOME"] = os.path.join(ML, "hf"); os.environ["HF_HUB_OFFLINE"] = "1"
HERE = os.path.dirname(os.path.abspath(__file__)); SP = os.path.join(HERE, '..')
def load(p): return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]
test = [r for k in range(25, 31) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl'))]
seen = {r['q'] for r in test}
rows = [r for k in list(range(1, 25)) + list(range(31, 71)) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) if r['q'] not in seen]
z = np.load(os.path.join(HERE, 'kure-split.npz'))
V = np.vstack([z['tr'], np.load(os.path.join(HERE, 'kure-new.npz'))['v'][:len(rows) - z['tr'].shape[0]]]).astype(np.float32)
assert V.shape[0] == len(rows)
R = json.load(open(os.path.join(SP, 'route', 'all.json'), encoding='utf-8'))
from sentence_transformers import SentenceTransformer
VR = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu").encode([r['q'] for r in R], normalize_embeddings=True).astype(np.float32)
KEYS = ['notBook', 'titleLike', 'refersToShown']
Y = np.array([[1.0 if r.get(k) else 0.0 for k in KEYS] for r in rows], dtype=np.float32)
print('training yes counts', dict(zip(KEYS, Y.sum(0).astype(int).tolist())), 'of', len(rows))
def mlp(X, Yt, epochs=60):
    torch.manual_seed(0)
    m = nn.Sequential(nn.Linear(1024, 512), nn.ReLU(), nn.Dropout(0.3), nn.Linear(512, Yt.shape[1]))
    opt = torch.optim.AdamW(m.parameters(), lr=1e-3, weight_decay=1e-4); lossf = nn.BCEWithLogitsLoss()
    Xt, Yt = torch.tensor(X), torch.tensor(Yt)
    for _ in range(epochs):
        perm = torch.randperm(len(Xt))
        for i in range(0, len(Xt), 64):
            b = perm[i:i + 64]; opt.zero_grad(); lossf(m(Xt[b]), Yt[b]).backward(); opt.step()
    m.eval(); return torch.sigmoid(m(torch.tensor(VR))).detach().numpy()
P = {'lr': np.stack([LogisticRegression(max_iter=3000, C=4).fit(V, Y[:, j]).predict_proba(VR)[:, 1] for j in range(3)], 1),
     'mlp': mlp(V, Y)}
for name, p in P.items():
    out = {}
    for r, x in zip(R, p):
        route = 'not_book' if x[0] >= .5 else 'title_like' if x[1] >= .5 else 'refers_shown' if x[2] >= .5 else 'book'
        out[r['id']] = {'route': route, 'p': [round(float(v), 3) for v in x]}
    json.dump(out, open(os.path.join(SP, 'route', f'pred-{name}.json'), 'w', encoding='utf-8'), indent=0)
    print(name, 'done')
