"""Reference outputs of first-call-model.bin for the backend's FirstCallModelFileTests: 12 questions as int8 KURE-v1
vectors, each with the route and the field/subfield labels numpy computes from the file. usage: fixture.py MODEL_DIR"""
import json, os, struct, sys
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer
D = sys.argv[1]
b = open(os.path.join(D, 'first-call-model.bin'), 'rb').read()
n = struct.unpack('<i', b[6:10])[0]; H = json.loads(b[10:10 + n].decode('utf-8')); off = 10 + n
dim, hid = H['dim'], H['hidden']; W = {}
for h in H['heads']:
    o = len(h['labels']); sizes = [hid * dim, hid, o * hid, o]; arrs = []
    for s in sizes:
        arrs.append(np.frombuffer(b, '<f4', s, off)); off += 4 * s
    W[h['name']] = (h, arrs[0].reshape(hid, dim), arrs[1], arrs[2].reshape(o, hid), arrs[3])
def out(name, v):
    h, w1, b1, w2, b2 = W[name]; z = w2 @ np.maximum(0, w1 @ v + b1) + b2
    if h['kind'] == 'multi': return 1 / (1 + np.exp(-z))
    e = np.exp(z - z.max()); return e / e.sum()
KD = r"C:\Users\yujun\Documents\Claude\Projects\chaekcheck-data\ml\kure-onnx"
tok = Tokenizer.from_file(os.path.join(KD, 'tokenizer-cut.json')); tok.enable_truncation(256)
sess = ort.InferenceSession(os.path.join(KD, 'kure-query-int8-ext.onnx'), providers=['CPUExecutionProvider'])
qs = ["다이어트 식단을 자세히 다룬 책", "책첵은 무료야?", "오늘 점심 뭐 먹지", "채식주의자", "이 중에 소설만", "다른 지역 도서관도 가입돼?",
      "아이랑 읽을 공룡 그림책", "소설 말고 경제 공부 입문서", "고마워 잘 읽을게", "유발 하라리처럼 큰 흐름 보는 역사책", "대출 연장 어떻게 해", "반전 있는 추리소설"]
rows = []
for q in qs:
    e = tok.encode(q)
    v = sess.run(None, {'input_ids': np.array([e.ids], dtype=np.int64), 'attention_mask': np.array([e.attention_mask], dtype=np.int64)})[0][0]
    v = (v / np.linalg.norm(v)).astype(np.float32)
    h = W['route'][0]; p = out('route', v); k = int(np.argmax(p)); last = len(p) - 1
    route = h['labels'][k if k == last or p[k] >= h['tau'] else last]
    labs = {}
    for name in ('field', 'subfield'):
        hh = W[name][0]; pp = out(name, v)
        labs[name] = [hh['labels'][i] for i in range(len(pp)) if pp[i] >= hh['threshold']][:2]
    rows.append({'question': q, 'vector': [float(x) for x in v], 'route': route, 'routeP': [float(x) for x in p], **labs})
    print(q, route, labs)
json.dump(rows, open(os.path.join(D, 'first-call-fixture.json'), 'w', encoding='utf-8'), ensure_ascii=False)
