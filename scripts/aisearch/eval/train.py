"""Train on Sonnet-labelled questions (qgen/q-01..24), test on held-out q-25..30 (600) and the 57 eval questions
(gold: qlabel/sonnet-E1). Questions in the test set are removed from training (no leak). Same scores as pilot.py,
plus the label sets each model predicts, saved for the head-to-head with Haiku (clf/pred-*.json)."""
import glob, json, os, time
import numpy as np
_REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
_PROJECTS = os.path.dirname(_REPO)  # the folder that holds this repo and chaekcheck-data
ML = os.path.join(_PROJECTS, r"chaekcheck-data\ml")
os.environ["HF_HOME"] = os.path.join(ML, "hf"); os.environ["HF_HUB_OFFLINE"] = "1"
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.multiclass import OneVsRestClassifier
from sklearn.preprocessing import MultiLabelBinarizer
HERE = os.path.dirname(os.path.abspath(__file__)); SP = os.path.join(HERE, '..')
def load(p): return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]
test = [r for k in range(25, 31) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl'))]
evq = {f"e{q['id']}": q['q'] for q in json.load(open(os.path.join(SP, 'eval_questions.json'), encoding='utf-8'))}
ev = [dict(r, q=evq[r['id']]) for r in load(os.path.join(SP, 'qlabel', 'sonnet-E1.jsonl'))]
seen = {r['q'] for r in test} | {r['q'] for r in ev}
train = [r for k in range(1, 25) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl')) if r['q'] not in seen]
print('train', len(train), 'test', len(test), 'eval57', len(ev))
SINGLE = ['notBook', 'titleLike', 'refersToShown', 'wants_exclusion', 'fiction', 'audience', 'level']
MULTI = ['field', 'subfield', 'genre', 'mood']
def sval(r, k):
    if k == 'wants_exclusion': return str(any(v for v in (r.get('exclude') or {}).values()))
    return str(r.get(k))
def enc(rows):
    from sentence_transformers import SentenceTransformer
    global KM
    if 'KM' not in globals(): KM = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu")
    return KM.encode([r['q'] for r in rows], normalize_embeddings=True, batch_size=32)
cache = os.path.join(HERE, 'kure-split.npz')
if os.path.exists(cache) and np.load(cache)['tr'].shape[0] == len(train):
    z = np.load(cache); Vtr, Vte, Vev = z['tr'], z['te'], z['ev']
else:
    Vtr, Vte, Vev = enc(train), enc(test), enc(ev); np.savez(cache, tr=Vtr, te=Vte, ev=Vev)
tf = TfidfVectorizer(analyzer='char_wb', ngram_range=(2, 4), min_df=2, sublinear_tf=True).fit([r['q'] for r in train])
X = {'TF-IDF': (tf.transform([r['q'] for r in train]), tf.transform([r['q'] for r in test]), tf.transform([r['q'] for r in ev])),
     'KURE': (Vtr, Vte, Vev)}
def f1(pred, gold):
    tp = sum(len(set(p) & set(g)) for p, g in zip(pred, gold)); fp = sum(len(set(p) - set(g)) for p, g in zip(pred, gold))
    fn = sum(len(set(g) - set(p)) for p, g in zip(pred, gold)); return 2 * tp / max(2 * tp + fp + fn, 1)
res, preds = {}, {}
for name, (Xtr, Xte, Xev) in X.items():
    t0 = time.perf_counter()
    P = {'test': [{} for _ in test], 'ev': [{} for _ in ev]}
    for k in SINGLE:
        y = [sval(r, k) for r in train]
        m = LogisticRegression(max_iter=3000, C=4).fit(Xtr, y)
        for split, Xs in (('test', Xte), ('ev', Xev)):
            for i, v in enumerate(m.predict(Xs)): P[split][i][k] = v
    for k in MULTI:
        mlb = MultiLabelBinarizer().fit([r.get(k) or [] for r in train + test + ev])
        Y = mlb.transform([r.get(k) or [] for r in train]); keep = Y.sum(0) > 1
        m = OneVsRestClassifier(LogisticRegression(max_iter=3000, C=4)).fit(Xtr, Y[:, keep])
        cls = mlb.classes_[keep]
        for split, Xs in (('test', Xte), ('ev', Xev)):
            pr = m.predict_proba(Xs)
            for i, row in enumerate(pr): P[split][i][k] = [c for c, p in zip(cls, row) if p >= 0.35]
    dt = time.perf_counter() - t0
    preds[name] = P
    for split, rows in (('test', test), ('ev', ev)):
        out = {}
        for k in SINGLE: out[k] = float(np.mean([P[split][i][k] == sval(r, k) for i, r in enumerate(rows)]))
        for k in MULTI: out[k] = f1([P[split][i][k] for i in range(len(rows))], [r.get(k) or [] for r in rows])
        res[(name, split)] = out
    print(name, f'train+predict {dt:.1f}s')
base = {}
for split, rows in (('test', test), ('ev', ev)):
    out = {}
    for k in SINGLE:
        common = max(set(sval(r, k) for r in train), key=[sval(r, k) for r in train].count)
        out[k] = float(np.mean([sval(r, k) == common for r in rows]))
    for k in MULTI: out[k] = 0.0
    res[('always most common', split)] = out
for split in ('test', 'ev'):
    print(f"\n== {split} ({len(test) if split=='test' else len(ev)} questions)")
    names = ['always most common', 'TF-IDF', 'KURE']
    print(f"{'slot':16}" + ''.join(f'{n:>20}' for n in names))
    for k in SINGLE + MULTI:
        print(f'{k:16}' + ''.join(f"{res[(n, split)][k]*100:19.1f}%" for n in names))
json.dump({f'{a}|{b}': v for (a, b), v in res.items()}, open(os.path.join(HERE, 'train-results.json'), 'w'), indent=1)
json.dump(preds, open(os.path.join(HERE, 'pred-classifiers.json'), 'w', encoding='utf-8'), ensure_ascii=False)
