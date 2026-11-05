"""Learning examples for docs/features/foryou/8-methods-explained.md section D (2026-10-29): the KURE book vectors
(book-vectors.bin, int8 x scale) and the Haiku profile field (profiles-detailed.jsonl, 22 fields) with one
StratifiedGroupKFold split (same work = same group). Logistic regression, MLP, kNN majority baseline, ECE with the
reliability table, temperature scaling, isotonic calibration, selective prediction. Not the I-11 result: one split,
no tuning. Needs numpy and scikit-learn (run: <venv>/python ml_basics_demo.py)."""
import json, sys, time
from collections import defaultdict
from pathlib import Path
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.neural_network import MLPClassifier
from sklearn.isotonic import IsotonicRegression
from sklearn.model_selection import StratifiedGroupKFold
from sklearn.metrics import accuracy_score, f1_score

DATA = Path('C:/Users/yujun/Documents/Claude/Projects/chaekcheck-data/aisearch/100k')
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'crawl'))
from foryou_neighbours import work_key, first_author

# 1. vectors: "CCBV1\n", count, dim (int32), ids (int64), scales (float32), rows (int8)
raw = (DATA / 'book-vectors.bin').read_bytes()
count, dim = np.frombuffer(raw, '<i4', 2, 6)
at = 14
ids = np.frombuffer(raw, '<i8', count, at); at += 8 * count
scales = np.frombuffer(raw, '<f4', count, at); at += 4 * count
rows = np.frombuffer(raw, 'i1', count * dim, at).reshape(count, dim)
row_of = {int(b): i for i, b in enumerate(ids)}

# 2. labels and same-work groups
field = {}
for line in open(DATA / 'profiles-detailed.jsonl', encoding='utf-8'):
    d = json.loads(line)
    if d.get('field') and int(d['id']) in row_of:
        field[int(d['id'])] = d['field']
meta = {}
for line in list(open(DATA / 'targets.tsv', encoding='utf-8'))[1:]:
    p = line.rstrip('\n').split('\t')
    meta[int(p[1])] = (p[2], p[3])
books = sorted(field)
X = rows[[row_of[b] for b in books]].astype(np.float32) * scales[[row_of[b] for b in books]][:, None]
y = np.array([field[b] for b in books])
gid = {}
groups = np.array([gid.setdefault((work_key(meta[b][0]), first_author(meta[b][1])) if b in meta else ('id', b), len(gid))
                   for b in books])
print(f'books {len(books)}, fields {len(set(y))}, groups {len(gid)}')

train, test = next(StratifiedGroupKFold(n_splits=5, shuffle=True, random_state=0).split(X, y, groups))
# a third of the training books (by group) for calibration
cut = np.isin(groups[train], np.unique(groups[train])[::3])
fit, cal = train[~cut], train[cut]
print(f'fit {len(fit)}, calibrate {len(cal)}, test {len(test)}')


def report(name, proba, classes):
    pred = classes[proba.argmax(1)]
    print(f'{name}: accuracy {accuracy_score(y[test], pred):.3f}, macro-F1 {f1_score(y[test], pred, average="macro"):.3f}')
    return pred


def ece_table(name, proba, classes, bins=10):
    conf = proba.max(1)
    right = classes[proba.argmax(1)] == y[test]
    edges = np.linspace(0, 1, bins + 1)
    total = 0.0
    print(f'{name} reliability (bin: books, mean confidence, accuracy)')
    for lo, hi in zip(edges[:-1], edges[1:]):
        inside = (conf > lo) & (conf <= hi)
        if inside.sum() == 0:
            continue
        c, a = conf[inside].mean(), right[inside].mean()
        total += inside.mean() * abs(a - c)
        print(f'  {lo:.1f}-{hi:.1f}: {inside.sum():6d}  {c:.3f}  {a:.3f}')
    print(f'  ECE {total:.4f}')
    return total


# 3. kNN majority baseline: the 20 nearest training books (cosine = dot, vectors have length 1)
t0 = time.perf_counter()
classes = np.array(sorted(set(y)))
index = {c: i for i, c in enumerate(classes)}
ytr = np.array([index[c] for c in y[train]])
votes = np.zeros((len(test), len(classes)))
for s in range(0, len(test), 2000):
    sim = X[test[s:s + 2000]] @ X[train].T
    near = np.argpartition(-sim, 20, axis=1)[:, :20]
    for k, row in enumerate(near):
        np.add.at(votes[s + k], ytr[row], 1)
report('kNN 20 majority', votes / 20, classes)
print(f'  ({time.perf_counter() - t0:.0f}s)')

# 4. logistic regression (softmax), balanced class weights
t0 = time.perf_counter()
lr = LogisticRegression(max_iter=1000, C=1.0, class_weight='balanced')
lr.fit(X[fit], y[fit])
p_lr = lr.predict_proba(X[test])
report('logistic regression (balanced)', p_lr, lr.classes_)
print(f'  ({time.perf_counter() - t0:.0f}s train)')
ece_table('logistic regression', p_lr, lr.classes_)

# 5. temperature scaling: one T on the calibration books, minimising log loss
z_cal = lr.decision_function(X[cal])
y_cal = np.array([list(lr.classes_).index(c) for c in y[cal]])


def log_loss_at(T):
    z = z_cal / T
    z = z - z.max(1, keepdims=True)
    logp = z - np.log(np.exp(z).sum(1, keepdims=True))
    return -logp[np.arange(len(y_cal)), y_cal].mean()


Ts = np.arange(0.3, 3.01, 0.05)
T = Ts[np.argmin([log_loss_at(t) for t in Ts])]
z = lr.decision_function(X[test]) / T
p_t = np.exp(z - z.max(1, keepdims=True)); p_t /= p_t.sum(1, keepdims=True)
print(f'temperature T = {T:.2f}')
ece_table('after temperature', p_t, lr.classes_)

# 6. isotonic on the top probability: confidence -> accuracy, a non-decreasing step function
conf_cal = lr.predict_proba(X[cal]).max(1)
right_cal = lr.classes_[lr.predict_proba(X[cal]).argmax(1)] == y[cal]
iso = IsotonicRegression(out_of_bounds='clip').fit(conf_cal, right_cal)
p_iso = p_lr.copy()
top = p_iso.argmax(1)
p_iso[np.arange(len(top)), top] = iso.predict(p_lr.max(1))
ece_table('after isotonic (top probability)', p_iso, lr.classes_)

# 7. selective prediction: accept when the calibrated top probability >= tau
conf = p_t.max(1)
right = lr.classes_[p_t.argmax(1)] == y[test]
print('selective prediction after temperature (tau: coverage, accuracy of accepted)')
for tau in (0.5, 0.6, 0.7, 0.8, 0.9, 0.95):
    acc = conf >= tau
    print(f'  {tau:.2f}: {acc.mean():.3f}  {right[acc].mean():.3f}')

# 8. MLP: one hidden layer of 256, ReLU, Adam, early stopping on 10% of the fit books
t0 = time.perf_counter()
mlp = MLPClassifier(hidden_layer_sizes=(256,), early_stopping=True, max_iter=200, random_state=0)
mlp.fit(X[fit], y[fit])
p_mlp = mlp.predict_proba(X[test])
report('MLP 256', p_mlp, mlp.classes_)
print(f'  ({time.perf_counter() - t0:.0f}s train, {mlp.n_iter_} epochs)')
ece_table('MLP', p_mlp, mlp.classes_)
