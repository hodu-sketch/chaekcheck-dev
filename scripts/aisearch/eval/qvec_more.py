"""KURE-v1 vectors of the imagined blurbs (hyde.json -> qvec-hyde.npy) and of "question + keywords" (qvec-qkw.npy),
row i = question id i+1."""
import json
import os

import numpy as np

_REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
_PROJECTS = os.path.dirname(_REPO)  # the folder that holds this repo and chaekcheck-data
ML = os.path.join(_PROJECTS, r"chaekcheck-data\ml")
HERE = os.environ.get("AISEARCH_EVAL_DIR") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "data", "aisearch-eval")
os.environ["HF_HOME"] = os.path.join(ML, "hf")
os.environ["HF_HUB_OFFLINE"] = "1"

from sentence_transformers import SentenceTransformer  # noqa: E402

questions = json.load(open(os.path.join(HERE, "eval_questions.json"), encoding="utf-8"))
hyde = {x["id"]: x["text"] for x in json.load(open(os.path.join(HERE, "hyde.json"), encoding="utf-8"))}
kw = {x["id"]: x["out"] for x in json.load(open(os.path.join(HERE, "kw-base.json"), encoding="utf-8"))}
kw.update({x["id"]: x["out"] for x in json.load(open(os.path.join(HERE, "kw-sim.json"), encoding="utf-8"))})
model = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu")
texts_h = [hyde[q["id"]] for q in questions]
texts_k = [q["q"] + " " + " ".join(kw[q["id"]]["keywords"]) for q in questions]
np.save(os.path.join(HERE, "qvec-hyde.npy"), model.encode(texts_h, normalize_embeddings=True, batch_size=8).astype(np.float32))
np.save(os.path.join(HERE, "qvec-qkw.npy"), model.encode(texts_k, normalize_embeddings=True, batch_size=8).astype(np.float32))
print(len(texts_h), texts_k[0])
