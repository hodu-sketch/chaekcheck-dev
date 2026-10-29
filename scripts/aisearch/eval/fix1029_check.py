"""Route fix 2026-10-29: measures a model folder the way the backend runs it (first-call-model.bin's route head and
follow-up-model.bin read from the CCFC1 files, numpy forward pass = FirstCallModel.outputs, question vectors from the
folder's int8 KURE ONNX = the embedding server). Measures
  - the 600 route test questions (route/all.json with relabel-test.json + s-site-7 + s-site_hard-7, as route5b.py's old600),
  - the 700 relation test pairs (rel/pair-*-t.jsonl),
  - checks.json (misread list and held-out list; a follow-up item has "earlier" and goes through MlFirstCall.plan's
    relation switch).
usage: fix1029_check.py MODEL_DIR OUT_JSON   (env WORK = the training layout with route/ and rel/, FIX = the folder
with checks.json, route-add.jsonl, rel-add.jsonl; ENC = the encoder folder when MODEL_DIR has only the model files)"""
import json, os, struct, sys
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

MD, OUT = sys.argv[1], sys.argv[2]
WORK = os.environ['WORK']; FIX = os.environ['FIX']
def load(p): return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]

def read_model(p):
    with open(p, 'rb') as f:
        assert f.read(6) == b'CCFC1\n'
        n = struct.unpack('<i', f.read(4))[0]; h = json.loads(f.read(n).decode('utf-8'))
        dim, hid = h['dim'], h['hidden']; heads = {}
        for hd in h['heads']:
            o = len(hd['labels'])
            W1 = np.frombuffer(f.read(hid * dim * 4), '<f4').reshape(hid, dim); b1 = np.frombuffer(f.read(hid * 4), '<f4')
            W2 = np.frombuffer(f.read(o * hid * 4), '<f4').reshape(o, hid); b2 = np.frombuffer(f.read(o * 4), '<f4')
            heads[hd['name']] = dict(hd, W1=W1, b1=b1, W2=W2, b2=b2)
    return heads
def probs(hd, X):
    z = np.maximum(0, X.astype(np.float64) @ hd['W1'].T.astype(np.float64) + hd['b1']) @ hd['W2'].T.astype(np.float64) + hd['b2']
    z = np.exp(z - z.max(1, keepdims=True)); return z / z.sum(1, keepdims=True)
FC = read_model(os.path.join(MD, 'first-call-model.bin'))['route']
FU = read_model(os.path.join(MD, 'follow-up-model.bin'))['relation']
WAYS = FC['labels']; TAU = FC['tau']
def route(X):
    out = []
    for p in probs(FC, X):
        k = int(np.argmax(p)); out.append(WAYS[k] if k == len(WAYS) - 1 or p[k] >= TAU else WAYS[-1])
    return out

ENC = os.environ.get('ENC', MD)
tok = Tokenizer.from_file(os.path.join(ENC, 'tokenizer-cut.json')); tok.enable_truncation(256)
so = ort.SessionOptions(); so.intra_op_num_threads = 4
sess = ort.InferenceSession(os.path.join(ENC, 'kure-query-int8-ext.onnx'), so, providers=['CPUExecutionProvider'])
cache_p = os.path.join(WORK, 'int8-cache.npz')
cache = dict(np.load(cache_p, allow_pickle=True)['d'].item()) if os.path.exists(cache_p) else {}
def enc(texts):
    new = [t for t in dict.fromkeys(texts) if t not in cache]
    for t in new:
        e = tok.encode(t)
        v = sess.run(None, {'input_ids': np.array([e.ids], dtype=np.int64), 'attention_mask': np.array([e.attention_mask], dtype=np.int64)})[0][0]
        cache[t] = (v / np.linalg.norm(v)).astype(np.float32)
    if new: np.savez(cache_p, d=np.array(cache, dtype=object))
    return np.array([cache[t] for t in texts], dtype=np.float32)

REL = FU['labels']
def plan(q, earlier):
    """MlFirstCall.plan's way (pointsBack false)."""
    v = enc([q]); r = route(v)[0].upper()
    if earlier is None: return r, 'first'
    a, b, c = enc([q, earlier, earlier + ' ' + q])
    rel = REL[int(np.argmax(probs(FU, np.concatenate([a, b, c, a * b])[None])[0]))]
    if rel in ('refers', 'ask_one'): return 'REFERS_SHOWN', rel
    if rel in ('continue', 'feedback', 'shift'): return 'BOOK', rel
    if rel == 'new': return ('BOOK' if r == 'REFERS_SHOWN' else r), rel
    return ('NOT_BOOK' if r in ('BOOK', 'REFERS_SHOWN') else r), rel

# 600 route test
RD = os.path.join(WORK, 'route')
rel_te = set(json.load(open(os.path.join(RD, 'relabel-test.json'))))
def typ(r):
    t = r['type']
    if t == 'not_book' and r['id'] in rel_te: return 'site'
    return {'hard_book': 'book', 'site_hard': 'book'}.get(t, t)
rt = json.load(open(os.path.join(RD, 'all.json'), encoding='utf-8')) + load(os.path.join(RD, 's-site-7.jsonl')) + load(os.path.join(RD, 's-site_hard-7.jsonl'))
gold = [typ(r) for r in rt]
pr = route(enc([r['q'] for r in rt]))
acc = float(np.mean([p == g for p, g in zip(pr, gold)]))
blocked = sum(1 for p, g in zip(pr, gold) if g in ('book', 'title_like', 'refers_shown') and p in ('not_book', 'site'))
per = {t: float(np.mean([p == t for p, g in zip(pr, gold) if g == t])) for t in WAYS}
# ALT_SITE (FIX file): test ids whose gold moves from not_book to site under the 2026-10-29 rule (login/account)
alt = set(json.load(open(os.path.join(FIX, os.environ['ALT_SITE'])))) if os.environ.get('ALT_SITE') else set()
gold2 = ['site' if r['id'] in alt else g for r, g in zip(rt, gold)]
acc2 = float(np.mean([p == g for p, g in zip(pr, gold2)]))
res = {'model': MD, 'route600_alt_gold': {'all': acc2, 'moved': sorted(alt)}, 'tau': TAU, 'route600': {'all': acc, 'blocked': blocked, 'per_way': per, 'n': len(rt)},
       'route600_pred': {r['id']: p for r, p in zip(rt, pr)}}
# 700 relation test
RL = os.path.join(WORK, 'rel')
tp = [x for r in REL for x in load(os.path.join(RL, f'pair-{r}-t.jsonl'))]
F = []
for x in tp:
    a, b, c = enc([x['q2'], x['q1'], x['q1'] + ' ' + x['q2']]); F.append(np.concatenate([a, b, c, a * b]))
rp = [REL[int(i)] for i in probs(FU, np.array(F)).argmax(1)]
res['rel700'] = {'all': float(np.mean([p == x['rel'] for p, x in zip(rp, tp)])),
                 'per_rel': {r: float(np.mean([p == r for p, x in zip(rp, tp) if x['rel'] == r])) for r in REL}}
# checks
checks = json.load(open(os.path.join(FIX, 'checks.json'), encoding='utf-8'))
rows = []
for c in checks:
    got, rel = plan(c['q'], c.get('earlier'))
    rows.append(dict(c, got=got, rel=rel, ok=got == c['want']))
for s in ('misread', 'heldout'):
    sub = [r for r in rows if r['set'] == s]
    res[s] = {'ok': sum(r['ok'] for r in sub), 'n': len(sub)}
res['checks'] = rows
# overlap of the new training questions with the tests (exact)
testq = {r['q'] for r in rt} | {c['q'] for c in checks}
add = load(os.path.join(FIX, 'route-add.jsonl')); radd = load(os.path.join(FIX, 'rel-add.jsonl'))
res['overlap_route_add_with_tests'] = [r['q'] for r in add if r['q'] in testq]
res['overlap_rel_add_with_tests'] = [r['q2'] for r in radd if r['q2'] in testq or (r['q1'], r['q2']) in {(x['q1'], x['q2']) for x in tp}]
json.dump(res, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('route600 alt gold %.1f%%' % (100 * acc2))
print('route600 %.1f%% blocked %d tau %.3f | rel700 %.1f%% | misread %d/%d | heldout %d/%d' % (
    100 * acc, blocked, TAU, 100 * res['rel700']['all'], res['misread']['ok'], res['misread']['n'], res['heldout']['ok'], res['heldout']['n']))
for r in rows:
    if not r['ok']: print('  miss', r['set'], r['q'], '|', r.get('earlier'), '| want', r['want'], 'got', r['got'], r['rel'])
print('overlap', res['overlap_route_add_with_tests'], res['overlap_rel_add_with_tests'])
