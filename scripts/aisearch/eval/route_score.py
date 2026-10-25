"""Routing accuracy: production first-call Haiku (haiku-*.jsonl) vs LR / MLP (pred-*.json) on the 400 routing questions.
Haiku's route: notBook, then titleLike, then refersToShown, else book (the order AiSearchFlow checks)."""
import json, glob
from collections import Counter
R = json.load(open('all.json', encoding='utf-8'))
gold = {r['id']: ('book' if r['type'] == 'hard_book' else r['type']) for r in R}
H = {}
for p in glob.glob('haiku-*.jsonl'):
    for l in open(p, encoding='utf-8'):
        if l.strip():
            o = json.loads(l)
            H[o['id']] = 'not_book' if o.get('notBook') else 'title_like' if o.get('titleLike') else 'refers_shown' if o.get('refersToShown') else 'book'
preds = {'Haiku (운영 1차)': H, 'LR': {k: v['route'] for k, v in json.load(open('pred-lr.json')).items()},
         'MLP': {k: v['route'] for k, v in json.load(open('pred-mlp.json')).items()}}
types = ['not_book', 'title_like', 'refers_shown', 'book']
res = {}
for name, P in preds.items():
    miss = [i for i in gold if i not in P]
    acc = {t: sum(P.get(i) == t for i in gold if gold[i] == t) / sum(1 for i in gold if gold[i] == t) for t in types}
    allacc = sum(P.get(i) == gold[i] for i in gold) / len(gold)
    blocked = sum(1 for i in gold if gold[i] != 'not_book' and P.get(i) == 'not_book')
    conf = Counter((gold[i], P.get(i)) for i in gold if P.get(i) != gold[i])
    res[name] = {'acc': acc, 'all': allacc, 'blocked_real_questions': blocked, 'missing': len(miss), 'errors': conf.most_common(6)}
    print(f"{name:16} 전체 {allacc*100:5.1f}%  " + '  '.join(f'{t} {acc[t]*100:5.1f}%' for t in types) + f"  | 진짜 질문을 책 찾기 아님으로 막음 {blocked}  누락 {len(miss)}")
    print('   틀린 곳 (정답, 판단):', conf.most_common(6))
json.dump(res, open('scores.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
