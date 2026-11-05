"""Keywords without an LLM: the topic words Haiku gave the 27,227 books (profile 'topics', used by 2+ books: ~10,400)
turned into KURE vectors once; for a question, the topics nearest its KURE vector become keywords, after the question's
own words (max 4 + 4). Writes kw-topics.json {id: {"keywords", "core": [], "topics"}} for the 57 eval questions and
topic-vectors.npz."""
import json, os, re, time
from collections import Counter
import numpy as np
_REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
_PROJECTS = os.path.dirname(_REPO)  # the folder that holds this repo and chaekcheck-data
ML = os.path.join(_PROJECTS, r"chaekcheck-data\ml")
os.environ["HF_HOME"] = os.path.join(ML, "hf"); os.environ["HF_HUB_OFFLINE"] = "1"
from sentence_transformers import SentenceTransformer
HERE = os.path.dirname(os.path.abspath(__file__))
c = Counter()
for l in open(os.path.join(_PROJECTS, r"chaekcheck-data/aisearch/book-profiles-detailed-2026-10-25.jsonl"), encoding="utf-8"):
    for t in json.loads(l).get("topics") or []:
        c[t.strip()] += 1
GENERIC = re.compile("책|도서|독서|추천|리뷰|서평")
vocab = [t for t, n in c.most_common() if n >= 2 and len(t) >= 2 and not GENERIC.search(t)]
m = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu")
cache = os.path.join(HERE, "topic-vectors.npz")
if os.path.exists(cache) and list(np.load(cache, allow_pickle=True)["vocab"]) == vocab:
    TV = np.load(cache, allow_pickle=True)["v"]
else:
    t0 = time.perf_counter(); TV = m.encode(vocab, normalize_embeddings=True, batch_size=64)
    np.savez(cache, v=TV, vocab=np.array(vocab, dtype=object)); print(f"encoded {len(vocab)} topics in {time.perf_counter()-t0:.0f}s")
qs = json.load(open(os.path.join(HERE, "eval_questions.json"), encoding="utf-8"))
QV = m.encode([q["q"] for q in qs], normalize_embeddings=True)
exec(open(os.path.join(HERE, "cands_e2e.py"), encoding="utf-8").read().split("STOP = ")[1].split("def facets")[0].join(["STOP = ", ""]) if False else "")
STOP = set("책 도서 관련 사람 처음 위한 대한 읽는 추천 추천해줘 추천해 알려줘 찾아줘 읽을 읽기 좋은 있어 있나요 있을까요 싶어 싶은 거 것 걸 같은 비슷한 처럼 주세요 해줘 좀 그냥 말고 빼고 없는".split())
END = re.compile("(으로|에서|에게|한테|이랑|하고|까지|부터|처럼|같은|이나|에|의|을|를|이|가|은|는|와|과|로|도|만|요)$")
def words(q):
    out = []
    for t in re.split(r"[\s,.?!·~\"'()]+", q):
        t = END.sub("", t)
        if len(t) >= 2 and t not in STOP and t not in out:
            out.append(t)
    return out
t0 = time.perf_counter()
sims = QV @ TV.T
per_q = (time.perf_counter() - t0) / len(qs) * 1000
out = {}
for i, q in enumerate(qs):
    w = words(q["q"])[:4]
    near = [vocab[j] for j in np.argsort(-sims[i])[:12] if vocab[j] not in w][:4]
    out[q["id"]] = {"keywords": w + near, "core": [], "topics": near}
json.dump(out, open(os.path.join(HERE, "kw-topics.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=0)
print(f"nearest topics {per_q:.2f} ms per question (PC)")
for q in qs[:3] + [x for x in qs if "번아웃" in x["q"]][:1]:
    print(q["q"], "->", out[q["id"]]["keywords"])
