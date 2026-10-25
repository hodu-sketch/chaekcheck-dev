"""The small net (KURE 1,024 -> 512 -> shelves) at each training size, same 600 test questions. One net per size and slot:
trained on the training questions minus the 100 dev questions (q-24), threshold picked on dev, that same net scored on test.
Sizes from argv, e.g. 2306 3306 4306 5300 6300 (base 2,306 first, then the aimed questions in file order)."""
import json, os, sys, time
import numpy as np
import torch, torch.nn as nn
torch.set_num_threads(12)
ML = r"C:\Users\yujun\Documents\Claude\Projects\chaekcheck-data\ml"
os.environ["HF_HOME"] = os.path.join(ML, "hf"); os.environ["HF_HUB_OFFLINE"] = "1"
HERE = os.path.dirname(os.path.abspath(__file__)); SP = os.path.join(HERE, '..')
T = json.load(open(os.path.join(SP, 'taxonomy2.json'), encoding='utf-8'))
def load(p): return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]
test = [r for k in range(25, 31) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl'))]
seen = {r['q'] for r in test}
base = [r for k in range(1, 25) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) if r['q'] not in seen]
extra = [r for k in range(31, 71) if os.path.exists(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) if r['q'] not in seen]
z = np.load(os.path.join(HERE, 'kure-split.npz')); Vb, Vte = z['tr'], z['te']
cache = os.path.join(HERE, 'kure-new.npz'); have = np.load(cache)['v'] if os.path.exists(cache) else np.zeros((0, 1024), np.float32)
if have.shape[0] < len(extra):
    from sentence_transformers import SentenceTransformer
    more = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu").encode([r['q'] for r in extra[have.shape[0]:]], normalize_embeddings=True, batch_size=32)
    have = np.vstack([have, more]); np.savez(cache, v=have)
Ve = have[:len(extra)]
print('base', len(base), 'extra', len(extra), flush=True)
LABELS = {'field': list(T['field']), 'subfield': [s for v in T['field'].values() for s in v], 'genre': T['genre'], 'mood': T['mood']}
def f1(pred, gold):
    tp = sum(len(set(p) & set(g)) for p, g in zip(pred, gold)); fp = sum(len(set(p) - set(g)) for p, g in zip(pred, gold))
    fn = sum(len(set(g) - set(p)) for p, g in zip(pred, gold)); return 2 * tp / max(2 * tp + fp + fn, 1)
def Y(rows, k, labs): return np.array([[1.0 if l in (r.get(k) or []) else 0.0 for l in labs] for r in rows], dtype=np.float32)
def net(X, Yt, epochs=60):
    torch.manual_seed(0)
    m = nn.Sequential(nn.Linear(1024, 512), nn.ReLU(), nn.Dropout(0.3), nn.Linear(512, Yt.shape[1]))
    opt = torch.optim.AdamW(m.parameters(), lr=1e-3, weight_decay=1e-4); lossf = nn.BCEWithLogitsLoss()
    Xt, Yt = torch.tensor(X), torch.tensor(Yt)
    for _ in range(epochs):
        perm = torch.randperm(len(Xt))
        for i in range(0, len(Xt), 64):
            b = perm[i:i + 64]; opt.zero_grad(); lossf(m(Xt[b]), Yt[b]).backward(); opt.step()
    m.eval(); return lambda A: torch.sigmoid(m(torch.tensor(A))).detach().numpy()
out = json.load(open(os.path.join(HERE, 'mlp-steps.json'))) if os.path.exists(os.path.join(HERE, 'mlp-steps.json')) else {}
for n in [int(a) for a in sys.argv[1:]]:
    add = n - len(base)
    if add > len(extra): print(n, 'not enough questions yet'); continue
    rows = base + extra[:add]; V = np.vstack([Vb, Ve[:add]]) if add else Vb
    dev = np.array([2300 < r['seed'] <= 2400 for r in rows])
    line = []; t0 = time.perf_counter()
    for k, labs in LABELS.items():
        f = net(V[~dev], Y([r for r, d in zip(rows, dev) if not d], k, labs))
        gdev = [r.get(k) or [] for r, d in zip(rows, dev) if d]; p_dev = f(V[dev])
        th = max(np.arange(0.05, 0.7, 0.05), key=lambda t: f1([[labs[i] for i in np.where(x >= t)[0]] for x in p_dev], gdev))
        s = f1([[labs[i] for i in np.where(x >= th)[0]] for x in f(Vte)], [r.get(k) or [] for r in test])
        out[f'{n}|{k}'] = s; line.append(f'{k} {s*100:.1f}%')
    json.dump(out, open(os.path.join(HERE, 'mlp-steps.json'), 'w'), indent=1)
    print(f'{n:5} training questions: ' + '  '.join(line) + f'   ({time.perf_counter()-t0:.0f}s)', flush=True)
