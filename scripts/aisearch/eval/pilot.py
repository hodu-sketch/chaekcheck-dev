"""Pilot: Sonnet-labelled questions -> TF-IDF + logistic regression vs KURE-v1 vector + logistic regression.
5-fold cross-validation over the questions made so far (qgen/q-*.jsonl). Scores per slot:
 - yes/no slots (notBook, titleLike, refersToShown, wants-exclusion): accuracy
 - one-of slots (fiction yes/no/unknown, audience, level): accuracy
 - many-of slots (field, subfield, genre, mood): micro F1 (precision/recall over every label given)"""
import glob, json, os, sys, time
import numpy as np
_REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
_PROJECTS = os.path.dirname(_REPO)  # the folder that holds this repo and chaekcheck-data
ML = os.path.join(_PROJECTS, r"chaekcheck-data\ml")
os.environ["HF_HOME"] = os.path.join(ML, "hf"); os.environ["HF_HUB_OFFLINE"] = "1"
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import KFold
from sklearn.multiclass import OneVsRestClassifier
from sklearn.preprocessing import MultiLabelBinarizer
HERE = os.path.dirname(os.path.abspath(__file__))
rows = [json.loads(l) for p in sorted(glob.glob(os.path.join(HERE, '..', 'qgen', 'q-*.jsonl'))) for l in open(p, encoding='utf-8') if l.strip()]
Q = [r['q'] for r in rows]
print('questions', len(Q))
cache = os.path.join(HERE, 'kure-q.npy')
if os.path.exists(cache) and np.load(cache).shape[0] == len(Q):
    V = np.load(cache)
else:
    from sentence_transformers import SentenceTransformer
    V = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu").encode(Q, normalize_embeddings=True, batch_size=32)
    np.save(cache, V)
tf = TfidfVectorizer(analyzer='char_wb', ngram_range=(2, 4), min_df=2, sublinear_tf=True)
def single(key, f=lambda v: v):
    return np.array([str(f(r.get(key))) for r in rows])
SINGLE = {
    'notBook': single('notBook'), 'titleLike': single('titleLike'), 'refersToShown': single('refersToShown'),
    'wants_exclusion': np.array([str(any(v for v in (r.get('exclude') or {}).values())) for r in rows]),
    'fiction': single('fiction'), 'audience': single('audience'), 'level': single('level')}
MULTI = {k: [r.get(k) or [] for r in rows] for k in ('field', 'subfield', 'genre', 'mood')}
def run(make_x):
    out = {k: [] for k in list(SINGLE) + list(MULTI)}
    for tr, te in KFold(5, shuffle=True, random_state=0).split(Q):
        Xtr, Xte = make_x(tr, te)
        for k, y in SINGLE.items():
            if len(set(y[tr])) < 2:
                out[k].append(np.mean(y[te] == y[tr][0])); continue
            m = LogisticRegression(max_iter=2000, C=4).fit(Xtr, y[tr])
            out[k].append(np.mean(m.predict(Xte) == y[te]))
        for k, ys in MULTI.items():
            mlb = MultiLabelBinarizer().fit(ys); Y = mlb.transform(ys)
            keep = Y[tr].sum(0) > 0
            m = OneVsRestClassifier(LogisticRegression(max_iter=2000, C=4)).fit(Xtr, Y[tr][:, keep])
            P = m.predict_proba(Xte) >= 0.4
            G = Y[te][:, keep]
            tp = (P & (G == 1)).sum(); fp = (P & (G == 0)).sum(); fn = ((~P) & (G == 1)).sum() + Y[te][:, ~keep].sum()
            out[k].append(2 * tp / max(2 * tp + fp + fn, 1))
    return {k: float(np.mean(v)) for k, v in out.items()}
def x_tfidf(tr, te):
    X = tf.fit([Q[i] for i in tr]); return tf.transform([Q[i] for i in tr]), tf.transform([Q[i] for i in te])
def x_kure(tr, te):
    return V[tr], V[te]
def x_major(tr, te):  # always the most common answer
    return np.zeros((len(tr), 1)), np.zeros((len(te), 1))
res = {'always most common': run(x_major), 'TF-IDF': run(x_tfidf), 'KURE': run(x_kure)}
print(f"{'slot':16}" + ''.join(f'{n:>20}' for n in res))
for k in res['KURE']:
    print(f'{k:16}' + ''.join(f'{res[n][k]*100:19.1f}%' for n in res))
json.dump(res, open(os.path.join(HERE, f'pilot-{len(Q)}.json'), 'w'), indent=1)
