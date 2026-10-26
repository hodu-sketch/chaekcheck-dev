"""Plan A: the relation of a follow-up to the earlier question (user 2026-10-26 "A·B를 다 잰 다음에 한 번에 바꾸자").
Pairs from rel/pair-<rel>-<1..4>.jsonl (Sonnet, 7 relations x 400), test rel/pair-<rel>-t.jsonl (7 x 100, first
questions never in training). Input = the KURE-v1 vectors of q2, q1, "q1 q2" and q1*q2 (4,096 numbers), MLP
4096 -> 512 -> 7, three seeds averaged, per size 100..400 a relation (argv). Also the "drop the earlier question?"
call of the rules (now, strict) against the classifier's new. Writes rel/rel-steps.json and, for the largest size,
rel/rel-mlp.pt (seed 0) and rel/pred-rel.json."""
import glob, json, os, sys
import numpy as np
import torch, torch.nn as nn
torch.set_num_threads(12)
ML = r"C:\Users\yujun\Documents\Claude\Projects\chaekcheck-data\ml"
os.environ["HF_HOME"] = os.path.join(ML, "hf"); os.environ["HF_HUB_OFFLINE"] = "1"
HERE = os.path.dirname(os.path.abspath(__file__)); RD = os.path.join(HERE, '..', 'rel')
REL = ['refers', 'ask_one', 'continue', 'shift', 'feedback', 'new', 'none']
def load(p): return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]
train = {r: [x for b in range(1, 5) if os.path.exists(os.path.join(RD, f'pair-{r}-{b}.jsonl')) for x in load(os.path.join(RD, f'pair-{r}-{b}.jsonl'))] for r in REL}
test = [x for r in REL for x in load(os.path.join(RD, f'pair-{r}-t.jsonl'))]
print({r: len(v) for r, v in train.items()}, 'test', len(test))
from sentence_transformers import SentenceTransformer
km = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu")
cache_p = os.path.join(HERE, 'kure-rel.npz')
cache = dict(np.load(cache_p, allow_pickle=True)['d'].item()) if os.path.exists(cache_p) else {}
def texts(x): return [x['q2'], x['q1'], x['q1'] + ' ' + x['q2']]
allt = list(dict.fromkeys(t for x in test + [y for v in train.values() for y in v] for t in texts(x)))
new = [t for t in allt if t not in cache]
if new:
    for t, v in zip(new, km.encode(new, normalize_embeddings=True, batch_size=64).astype(np.float32)): cache[t] = v
    np.savez(cache_p, d=np.array(cache, dtype=object))
def feat(x):
    a, b, c = (cache[t] for t in texts(x)); return np.concatenate([a, b, c, a * b])
XT = np.array([feat(x) for x in test], dtype=np.float32); YT = np.array([REL.index(x['rel']) for x in test])
def mlp(X, y, seed):
    torch.manual_seed(seed)
    m = nn.Sequential(nn.Linear(X.shape[1], 512), nn.ReLU(), nn.Dropout(0.3), nn.Linear(512, len(REL)))
    opt = torch.optim.AdamW(m.parameters(), lr=1e-3, weight_decay=1e-4); lossf = nn.CrossEntropyLoss()
    Xt, Yt = torch.tensor(X), torch.tensor(y)
    for _ in range(60):
        perm = torch.randperm(len(Xt))
        for i in range(0, len(Xt), 64):
            bb = perm[i:i + 64]; opt.zero_grad(); lossf(m(Xt[bb]), Yt[bb]).backward(); opt.step()
    m.eval(); return m
out_p = os.path.join(RD, 'rel-steps.json'); out = json.load(open(out_p)) if os.path.exists(out_p) else {}
sizes = [int(a) for a in sys.argv[1:]] or [100, 200, 300, 400]
for n in sizes:
    if any(len(v) < n for v in train.values()): print('not enough for', n); continue
    sub = [x for r in REL for x in train[r][:n]]
    X = np.array([feat(x) for x in sub], dtype=np.float32); y = np.array([REL.index(x['rel']) for x in sub])
    runs = []
    for seed in (0, 1, 2):
        m = mlp(X, y, seed)
        with torch.no_grad(): pred = m(torch.tensor(XT)).argmax(1).numpy()
        per = {r: float(np.mean(pred[YT == k] == k)) for k, r in enumerate(REL)}
        runs.append({'all': float(np.mean(pred == YT)), **per})
        if seed == 0 and n == max(sizes):
            torch.save({'state_dict': m.state_dict(), 'rels': REL}, os.path.join(RD, 'rel-mlp.pt'))
            json.dump({x['id']: REL[int(p)] for x, p in zip(test, pred)}, open(os.path.join(RD, 'pred-rel.json'), 'w'))
            conf = np.zeros((len(REL), len(REL)), int)
            for g, p in zip(YT, pred): conf[g, p] += 1
            print('confusion (rows gold):', REL); print(conf)
    res = {k: float(np.mean([r[k] for r in runs])) for k in runs[0]}; res['all_runs'] = [r['all'] for r in runs]
    out[str(n * len(REL))] = res; json.dump(out, open(out_p, 'w'), indent=1)
    print(n, 'per relation:', 'all %.1f' % (100 * res['all']), ' '.join('%s %.0f' % (r, 100 * res[r]) for r in REL), flush=True)
