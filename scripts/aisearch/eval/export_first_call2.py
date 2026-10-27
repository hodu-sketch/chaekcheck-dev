"""The ML first call for production, v1.16.1 on (run with ROUTE_PT=route5b-mlp.pt, router B of route5b.py: the 5 ways
plus 300 short genre questions; the steps below are export_first_call.py's) (user 2026-10-26 "1차를 5갈래 MLP로
바꿔요(M4 방식)"):
1. trains the shelf heads as predict_eval.py does (all 6,298 shelf questions, threshold per slot on dev) and takes the
   5-way router of route5.py (route/route5-mlp.pt, 600 per class);
2. checks them on production's question vectors (int8 KURE-v1, kure-query-int8-ext.onnx as the backend runs it)
   against the float32 vectors they were trained on: router on its 600 test questions, shelves on q-25..30;
3. writes first-call-model.bin (little-endian: b"CCFC1\\n", int32 header length, UTF-8 JSON header, then per head
   W1 [512 x 1024], b1 [512], W2 [n x 512], b2 [n] as float32) and book-profiles.tsv (book id, field, subfield,
   fiction, genre|.., country, audience, level, form|..) into argv[1].
usage: export_first_call.py OUT_DIR"""
import glob, json, os, struct, sys
import numpy as np
import torch, torch.nn as nn
HERE = os.path.dirname(os.path.abspath(__file__)); SP = os.path.join(HERE, '..')
OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True)
ML = r"C:\Users\yujun\Documents\Claude\Projects\chaekcheck-data\ml"
src = open(os.path.join(HERE, 'predict_eval.py'), encoding='utf-8').read().split("\nout = {}\n")[0]
src = src.replace('Vev = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu").encode([q[\'q\'] for q in evq], normalize_embeddings=True).astype(np.float32)', 'km = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu")')
exec(src)

# int8 encoder, as OnnxQuestionEncoder
import onnxruntime as ort
from tokenizers import Tokenizer
KD = os.path.join(ML, 'kure-onnx')
tok = Tokenizer.from_file(os.path.join(KD, 'tokenizer-cut.json')); tok.enable_truncation(256)
sess = ort.InferenceSession(os.path.join(KD, 'kure-query-int8-ext.onnx'), providers=['CPUExecutionProvider'])
def int8(texts):
    out = []
    for t in texts:
        e = tok.encode(t)
        v = sess.run(None, {'input_ids': np.array([e.ids], dtype=np.int64), 'attention_mask': np.array([e.attention_mask], dtype=np.int64)})[0][0]
        out.append(v / np.linalg.norm(v))
    return np.array(out, dtype=np.float32)
def fp32(texts): return km.encode(texts, normalize_embeddings=True, batch_size=64).astype(np.float32)

def mlp_model(X, Y, multi=True, epochs=60):
    torch.manual_seed(0)
    m = nn.Sequential(nn.Linear(1024, 512), nn.ReLU(), nn.Dropout(0.3), nn.Linear(512, Y.shape[1]))
    opt = torch.optim.AdamW(m.parameters(), lr=1e-3, weight_decay=1e-4)
    lossf = nn.BCEWithLogitsLoss() if multi else nn.CrossEntropyLoss()
    Xt, Yt = torch.tensor(X), torch.tensor(Y.astype(np.float32))
    for _ in range(epochs):
        perm = torch.randperm(len(Xt))
        for i in range(0, len(Xt), 64):
            b = perm[i:i + 64]; opt.zero_grad(); lossf(m(Xt[b]), Yt[b]).backward(); opt.step()
    m.eval(); return m
def run(m, A, multi):
    with torch.no_grad():
        z = m(torch.tensor(A)); return (torch.sigmoid(z) if multi else torch.softmax(z, 1)).numpy()

test = [r for k in range(25, 31) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl'))]
TQ = [r['q'] for r in test]
V32, V8 = fp32(TQ), int8(TQ)
print('test shelf questions', len(TQ), 'int8 vs fp32 cosine mean %.4f min %.4f' % (np.mean(np.sum(V32 * V8, 1)), np.min(np.sum(V32 * V8, 1))))
heads, report = [], {}
for k, labs in MULTI.items():
    Y = np.array([[1 if l in get(r, k) else 0 for l in labs] for r in rows])
    md = mlp_model(V[~dev], Y[~dev]); pd = run(md, V[dev], True); gd = [get(r, k) for r, d in zip(rows, dev) if d]
    th = float(max(np.arange(0.05, 0.7, 0.05), key=lambda t: f1([[labs[i] for i in np.where(x >= t)[0]] for x in pd], gd)))
    m = mlp_model(V, Y)
    gold = [get(r, k) for r in test]
    sc = [f1([[labs[i] for i in np.where(x >= th)[0]][:2] for x in run(m, A, True)], gold) for A in (V32, V8)]
    report[k] = sc; heads.append((k, 'multi', labs, th, m)); print(k, 'F1 fp32 %.3f int8 %.3f' % tuple(sc), flush=True)
for k, labs in SINGLE.items():
    y = np.array([labs.index(sget(r, k)) if sget(r, k) in labs else 0 for r in rows])
    m = mlp_model(V, np.eye(len(labs))[y], multi=False)
    gold = [sget(r, k) for r in test]
    sc = [float(np.mean([labs[int(i)] == g for i, g in zip(run(m, A, False).argmax(1), gold)])) for A in (V32, V8)]
    report[k] = sc; heads.append((k, 'single', labs, None, m)); print(k, 'acc fp32 %.3f int8 %.3f' % tuple(sc), flush=True)

# router
ck = torch.load(os.path.join(SP, 'route', os.environ.get('ROUTE_PT', 'route5-mlp.pt')))
rm = nn.Sequential(nn.Linear(1024, 512), nn.ReLU(), nn.Dropout(0.3), nn.Linear(512, 5)); rm.load_state_dict(ck['state_dict']); rm.eval()
R5 = ck['routes']; tau = float(ck['tau'])
RD = os.path.join(SP, 'route')
rel = set(json.load(open(os.path.join(RD, 'relabel-test.json'))))
def typ(r):
    t = r['type']
    if t == 'not_book' and r['id'] in rel: return 'site'
    return {'hard_book': 'book', 'site_hard': 'book'}.get(t, t)
rt = json.load(open(os.path.join(RD, 'all.json'), encoding='utf-8')) + load(os.path.join(RD, 's-site-7.jsonl')) + load(os.path.join(RD, 's-site_hard-7.jsonl'))
gold = [typ(r) for r in rt]
def route(P):
    out = []
    for p in P:
        k = int(np.argmax(p)); out.append(R5[k] if k == 4 or p[k] >= tau else 'book')
    return out
for name, A in (('fp32', fp32([r['q'] for r in rt])), ('int8', int8([r['q'] for r in rt]))):
    rr = route(run(rm, A, False))
    acc = np.mean([a == b for a, b in zip(rr, gold)])
    blocked = sum(1 for p, g in zip(rr, gold) if g in ('book', 'title_like', 'refers_shown') and p in ('not_book', 'site'))
    report['route_' + name] = [float(acc), blocked]
    print('router', name, 'all %.3f blocked %d' % (acc, blocked), flush=True)
heads.insert(0, ('route', 'route', R5, tau, rm))
json.dump(report, open(os.path.join(OUT, 'first-call-check.json'), 'w'), indent=1)

# model file
header = {'dim': 1024, 'hidden': 512, 'heads': []}
blobs = []
for name, kind, labs, th, m in heads:
    W1, b1, W2, b2 = (m[0].weight.detach().numpy(), m[0].bias.detach().numpy(), m[3].weight.detach().numpy(), m[3].bias.detach().numpy())
    h = {'name': name, 'kind': kind, 'labels': list(labs), 'outputs': int(W2.shape[0])}
    if kind == 'route': h['tau'] = th
    if kind == 'multi': h['threshold'] = th; h['max'] = 2
    header['heads'].append(h)
    blobs += [W1, b1, W2, b2]
hb = json.dumps(header, ensure_ascii=False).encode('utf-8')
with open(os.path.join(OUT, 'first-call-model.bin'), 'wb') as f:
    f.write(b'CCFC1\n'); f.write(struct.pack('<i', len(hb))); f.write(hb)
    for a in blobs: f.write(np.ascontiguousarray(a, dtype='<f4').tobytes())
print('model', os.path.getsize(os.path.join(OUT, 'first-call-model.bin')) // 1024, 'KB')

# profiles
n = 0
with open(os.path.join(OUT, 'book-profiles.tsv'), 'w', encoding='utf-8', newline='\n') as f:
    for l in open(os.path.join(r"C:\Users\yujun\Documents\Claude\Projects\chaekcheck-data\aisearch", 'book-profiles-detailed-2026-10-25.jsonl'), encoding='utf-8'):
        if not l.strip(): continue
        o = json.loads(l)
        fic = '' if o.get('fiction') is None else ('1' if o['fiction'] else '0')
        f.write('\t'.join([str(o['id']), o.get('field') or '', o.get('subfield') or '', fic, '|'.join(o.get('genre') or []),
                           o.get('country') or '', o.get('audience') or '', o.get('level') or '', '|'.join(o.get('form') or [])]) + '\n'); n += 1
print('profiles', n)
