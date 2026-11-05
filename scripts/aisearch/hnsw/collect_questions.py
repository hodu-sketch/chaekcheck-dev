"""Collect realistic AI-search questions for the HNSW benchmark (docs/features/aisearch/reports/2026-10-29-hnsw.md).

Walks the eval JSON files and keeps every string under a key that holds a user turn (q, question, turns, text, typed,
want is skipped: it is hidden from the app). Writes one question per line, unique, in file order.
usage: python collect_questions.py OUT.txt [json files...]
"""
import json, sys

KEYS = {'q', 'question', 'questions', 'turns', 'typed', 'ask', 'say', 'user'}
out_path, files = sys.argv[1], sys.argv[2:]
seen, out = set(), []

def walk(node, key=None):
    if isinstance(node, dict):
        for k, v in node.items():
            walk(v, k)
    elif isinstance(node, list):
        for v in node:
            walk(v, key)
    elif isinstance(node, str) and key in KEYS:
        s = node.strip()
        if 2 <= len(s) <= 200 and s not in seen:
            seen.add(s); out.append(s)

for f in files:
    walk(json.load(open(f, encoding='utf-8')))
open(out_path, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print(len(out), 'questions')
