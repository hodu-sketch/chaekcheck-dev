"""bge-reranker-v2-m3 used as is (no fine-tuning), for comparison only: score every (question, shelf text) pair for
field 22 / subfield 99 / genre 14 / mood 12, keep shelves whose sigmoid score passes a threshold. The threshold per
slot is chosen on 100 dev questions (q-24, part of the training files); scored on the 200 test questions q-25, q-26
and the 57 eval questions. Same micro F1 as clf/train.py."""
import json, os, time
import numpy as np
ML = r"C:\Users\yujun\Documents\Claude\Projects\chaekcheck-data\ml"
os.environ["HF_HOME"] = os.path.join(ML, "hf"); os.environ["HF_HUB_OFFLINE"] = "1"
import torch
torch.set_num_threads(16)
from transformers import AutoTokenizer, AutoModelForSequenceClassification
HERE = os.path.dirname(os.path.abspath(__file__)); SP = os.path.join(HERE, '..')
T = json.load(open(os.path.join(SP, 'taxonomy2.json'), encoding='utf-8'))
def load(p): return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]
dev = load(os.path.join(SP, 'qgen', 'q-24.jsonl'))
test = load(os.path.join(SP, 'qgen', 'q-25.jsonl')) + load(os.path.join(SP, 'qgen', 'q-26.jsonl'))
evq = {f"e{q['id']}": q['q'] for q in json.load(open(os.path.join(SP, 'eval_questions.json'), encoding='utf-8'))}
ev = [dict(r, q=evq[r['id']]) for r in load(os.path.join(SP, 'qlabel', 'sonnet-E1.jsonl'))]
LAB = {'field': [(f, f + ' 분야의 책') for f in T['field'] if f != '기타'],
       'subfield': [(s, s + ' 책') for v in T['field'].values() for s in v],
       'genre': [(g, g + ' 장르의 소설') for g in T['genre']],
       'mood': [(m, m + '이 느껴지는 책') for m in T['mood']]}
tok = AutoTokenizer.from_pretrained("BAAI/bge-reranker-v2-m3")
model = AutoModelForSequenceClassification.from_pretrained("BAAI/bge-reranker-v2-m3").eval()
def scores(rows):
    out = []; t0 = time.perf_counter()
    for r in rows:
        d = {}
        for k, labs in LAB.items():
            with torch.no_grad():
                e = tok([[r['q'], t] for _, t in labs], padding=True, truncation=True, max_length=64, return_tensors='pt')
                d[k] = torch.sigmoid(model(**e).logits.view(-1)).numpy()
        out.append(d)
    return out, (time.perf_counter() - t0) / len(rows)
def f1(pred, gold):
    tp = sum(len(set(p) & set(g)) for p, g in zip(pred, gold)); fp = sum(len(set(p) - set(g)) for p, g in zip(pred, gold))
    fn = sum(len(set(g) - set(p)) for p, g in zip(pred, gold)); return 2 * tp / max(2 * tp + fp + fn, 1)
def pick(sc, k, th): return [[LAB[k][i][0] for i, s in enumerate(d[k]) if s >= th] for d in sc]
Sd, sec = scores(dev); print(f'dev scored, {sec:.2f}s per question (16 threads, {sum(len(v) for v in LAB.values())} pairs)')
TH = {k: max(np.arange(0.02, 0.98, 0.02), key=lambda th: f1(pick(Sd, k, th), [r.get(k) or [] for r in dev])) for k in LAB}
print('thresholds', {k: round(float(v), 2) for k, v in TH.items()})
res = {}
for name, rows in (('test', test), ('ev', ev)):
    S, sec = scores(rows)
    res[name] = {k: f1(pick(S, k, TH[k]), [r.get(k) or [] for r in rows]) for k in LAB}
    print(name, len(rows), {k: f'{v*100:.1f}%' for k, v in res[name].items()}, f'{sec:.2f}s/question')
json.dump({'thresholds': {k: float(v) for k, v in TH.items()}, 'res': res}, open(os.path.join(HERE, 'zeroshot.json'), 'w'), indent=1)
