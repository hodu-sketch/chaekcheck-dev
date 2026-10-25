"""bge-reranker-v2-m3 (float32, torch) speed on this PC with 2 threads, like the embedding server's 2 cores:
time per question for 22 / 50 / 170 (question, shelf description) pairs, and KURE-v1 for one question."""
import json, os, time, sys
ML = r"C:\Users\yujun\Documents\Claude\Projects\chaekcheck-data\ml"
os.environ["HF_HOME"] = os.path.join(ML, "hf"); os.environ["HF_HUB_OFFLINE"] = "1"
import torch
torch.set_num_threads(2)
from transformers import AutoTokenizer, AutoModelForSequenceClassification
from sentence_transformers import SentenceTransformer
HERE = os.path.dirname(os.path.abspath(__file__))
T = json.load(open(os.path.join(HERE, '..', 'taxonomy2.json'), encoding='utf-8'))
labels = [f for f in T['field']] + [s for v in T['field'].values() for s in v] + T['genre'] + T['country'] + T['audience'] + T['level'] + T['form'] + T['mood']
print('labels', len(labels))
qs = ["인간관계에 지쳐서 마음이 편해지는 책, 소설 말고", "주식 처음 시작하는 사람한테 맞는 책", "일본 추리소설 중에 반전 있는 거", "초등학생이 읽을 공룡 도감"]
tok = AutoTokenizer.from_pretrained("BAAI/bge-reranker-v2-m3")
m = AutoModelForSequenceClassification.from_pretrained("BAAI/bge-reranker-v2-m3").eval()
def run(n):
    ts = []
    for q in qs:
        pairs = [[q, l + " 책"] for l in labels[:n]]
        t = time.perf_counter()
        with torch.no_grad():
            enc = tok(pairs, padding=True, truncation=True, max_length=64, return_tensors='pt')
            m(**enc).logits
        ts.append(time.perf_counter() - t)
    return sorted(ts)[len(ts) // 2], enc['input_ids'].shape
run(5)
for n in (22, 50, len(labels)):
    med, shape = run(n)
    print(f'reranker {n} pairs: median {med*1000:.0f} ms, batch shape {tuple(shape)}')
k = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu"); k.encode(qs[:1])
ts = []
for q in qs:
    t = time.perf_counter(); k.encode([q]); ts.append(time.perf_counter() - t)
print(f'KURE one question: median {sorted(ts)[2]*1000:.0f} ms')
