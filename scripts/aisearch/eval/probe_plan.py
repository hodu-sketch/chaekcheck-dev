import json, os, struct, sys
import numpy as np, onnxruntime as ort
from tokenizers import Tokenizer
D = r"C:\Users\yujun\Documents\Claude\Projects\chaekcheck-data\ml\aisearch-model"
b = open(os.path.join(D, 'first-call-model.bin'), 'rb').read()
n = struct.unpack('<i', b[6:10])[0]; H = json.loads(b[10:10 + n].decode('utf-8')); off = 10 + n; W = {}
dim, hid = H['dim'], H['hidden']
for h in H['heads']:
    o = len(h['labels']); arr = []
    for s in [hid * dim, hid, o * hid, o]:
        arr.append(np.frombuffer(b, '<f4', s, off)); off += 4 * s
    W[h['name']] = (h, arr[0].reshape(hid, dim), arr[1], arr[2].reshape(o, hid), arr[3])
def out(name, v):
    h, w1, b1, w2, b2 = W[name]; z = w2 @ np.maximum(0, w1 @ v + b1) + b2
    if h['kind'] == 'multi': return 1 / (1 + np.exp(-z))
    e = np.exp(z - z.max()); return e / e.sum()
tok = Tokenizer.from_file(os.path.join(D, 'tokenizer-cut.json')); tok.enable_truncation(256)
sess = ort.InferenceSession(os.path.join(D, 'kure-query-int8-ext.onnx'), providers=['CPUExecutionProvider'])
def vec(q):
    e = tok.encode(q); v = sess.run(None, {'input_ids': np.array([e.ids], dtype=np.int64), 'attention_mask': np.array([e.attention_mask], dtype=np.int64)})[0][0]
    return (v / np.linalg.norm(v)).astype(np.float32)
def shelves(v):
    r = {}
    for k in ('field', 'subfield', 'genre', 'form', 'ex_field', 'ex_genre'):
        h = W[k][0]; p = out(k, v); r[k] = [h['labels'][i] for i in range(len(p)) if p[i] >= h['threshold']][:2]
    for k in ('fiction', 'level', 'audience'):
        h = W[k][0]; r[k] = h['labels'][int(np.argmax(out(k, v)))]
    return r
def route(v):
    h = W['route'][0]; p = out('route', v); k = int(np.argmax(p)); last = len(p) - 1
    return h['labels'][k if k == last or p[k] >= h['tau'] else last], [round(float(x), 2) for x in p]
for q in sys.argv[1:]:
    v = vec(q); print(q, route(v), shelves(v))
