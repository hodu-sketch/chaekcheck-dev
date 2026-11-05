"""KURE-v1 (float32, as the book vectors) query vectors of the 50 eval questions -> qvec.npy (row i = question id i+1)."""
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
model = SentenceTransformer("nlpai-lab/KURE-v1", device="cpu")
vecs = model.encode([q["q"] for q in questions], normalize_embeddings=True, batch_size=8)
np.save(os.path.join(HERE, "qvec.npy"), vecs.astype(np.float32))
print(vecs.shape)
