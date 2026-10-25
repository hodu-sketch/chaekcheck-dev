"""Step by step: the first 2,306 training questions, then +1,000 aimed questions at a time (q-31.., in file order), each
step scored on the same 600 test questions. KURE + logistic regression, threshold per slot from dev (q-24) at each step.
The vectors of the new questions are made once and kept in kure-new.npz."""
import json, os
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.multiclass import OneVsRestClassifier
ML = r"C:\Users\yujun\Documents\Claude\Projects\chaekcheck-data\ml"
os.environ["HF_HOME"] = os.path.join(ML, "hf"); os.environ["HF_HUB_OFFLINE"] = "1"
HERE = os.path.dirname(os.path.abspath(__file__)); SP = os.path.join(HERE, '..')
T = json.load(open(os.path.join(SP, 'taxonomy2.json'), encoding='utf-8'))
def load(p): return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]
test = [r for k in range(25, 31) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl'))]
seen = {r['q'] for r in test}
base = [r for k in range(1, 25) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) if r['q'] not in seen]
extra = [r for k in range(31, 71) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) if r['q'] not in seen]
z = np.load(os.path.join(HERE, 'kure-split.npz')); Vb, Vte = z['tr'], z['te']
cache = os.path.join(HERE, 'kure-new.npz')
if os.path.exists(cache) and np.load(cache)['v'].shape[0] >= len(extra):
    Ve = np.load(cache)['v'][:len(extra)]
else:
    from sentence_transformers import SentenceTransformer
    Ve = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu").encode([r['q'] for r in extra], normalize_embeddings=True, batch_size=32)
    np.savez(cache, v=Ve)
print('base', len(base), 'extra', len(extra), 'test', len(test), flush=True)
LABELS = {'field': list(T['field']), 'subfield': [s for v in T['field'].values() for s in v], 'genre': T['genre'], 'mood': T['mood']}
def f1(pred, gold):
    tp = sum(len(set(p) & set(g)) for p, g in zip(pred, gold)); fp = sum(len(set(p) - set(g)) for p, g in zip(pred, gold))
    fn = sum(len(set(g) - set(p)) for p, g in zip(pred, gold)); return 2 * tp / max(2 * tp + fp + fn, 1)
def Y(rows, k, labs): return np.array([[1 if l in (r.get(k) or []) else 0 for l in labs] for r in rows])
def lr(X, Yt):
    keep = Yt.sum(0) > 1
    m = OneVsRestClassifier(LogisticRegression(max_iter=3000, C=4), n_jobs=8).fit(X, Yt[:, keep])
    def f(A):
        P = np.zeros((len(A), Yt.shape[1])); P[:, keep] = m.predict_proba(A); return P
    return f
out = {}
for add in (0, 1000, 2000, 2994, len(extra)):
    rows = base + extra[:add]; V = np.vstack([Vb, Ve[:add]]) if add else Vb
    dev = np.array([2300 < r['seed'] <= 2400 for r in rows])
    line = []
    for k, labs in LABELS.items():
        p_dev = lr(V[~dev], Y([r for r, d in zip(rows, dev) if not d], k, labs))(V[dev])
        gdev = [r.get(k) or [] for r, d in zip(rows, dev) if d]
        th = max(np.arange(0.05, 0.7, 0.05), key=lambda t: f1([[labs[i] for i in np.where(x >= t)[0]] for x in p_dev], gdev))
        p = lr(V, Y(rows, k, labs))(Vte)
        s = f1([[labs[i] for i in np.where(x >= th)[0]] for x in p], [r.get(k) or [] for r in test])
        out[f'{len(rows)}|{k}'] = s; line.append(f'{k} {s*100:.1f}%')
    print(f'{len(rows):5} training questions: ' + '  '.join(line), flush=True)
json.dump(out, open(os.path.join(HERE, 'steps.json'), 'w'), indent=1)
