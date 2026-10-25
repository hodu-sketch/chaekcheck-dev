"""3,000 more seeds (files 31-60, seeds 3001-6000) aimed at the weak shelves found by clf/weak.py: every genre gets about
80 more questions, every small shelf at least 15 more (the lower its training count and test F1, the more), the rest to
moods and forms. Same question types as qgen_seeds.py."""
import json, os, random
HERE = os.path.dirname(os.path.abspath(__file__))
T = json.load(open(os.path.join(HERE, 'taxonomy2.json'), encoding='utf-8'))
W = json.load(open(os.path.join(HERE, 'clf', 'weak.json'), encoding='utf-8'))
random.seed(20261026)
TYPES = [('single', 20), ('multi', 18), ('exclude', 8), ('include_exclude', 10), ('negation', 8), ('indirect', 18),
         ('conflict', 4), ('vague', 4), ('similar_book', 10)]
pool = [t for t, w in TYPES for _ in range(w)]
fiction_subs = {'소설': '', '어린이': '어린이 소설', '청소년': '청소년 소설', '만화': '만화·웹툰'}
gplan = []
for g in T['genre']:
    for _ in range(80):
        f = random.choice(['소설'] * 6 + ['어린이', '청소년', '만화'])
        gplan.append({'field': f, 'subfield': fiction_subs[f], 'genre': [g]})
subs = [(f, s) for f, v in T['field'].items() for s in v]
need = {s: 15 + int(30 * (1 - W.get(f'subfield:{s}', {}).get('f1', 0))) + max(0, 40 - W.get(f'subfield:{s}', {}).get('train', 0)) for _, s in subs}
splan = [{'field': f, 'subfield': s} for f, s in subs for _ in range(need[s])]
random.shuffle(splan)
plan = gplan + splan[:2600 - len(gplan)]
while len(plan) < 3000:
    f, s = random.choice(subs)
    d = {'field': f, 'subfield': s}
    d['mood' if random.random() < .6 else 'form'] = [random.choice(T['mood'] if random.random() < .6 else T['form'])]
    plan.append(d)
random.shuffle(plan)
seeds = []
for i, d in enumerate(plan):
    t = random.choice(pool); d = dict(d, seed=3001 + i, type=t)
    if 'genre' in d and random.random() < .5: d['country'] = random.choice(T['country'])
    for k, p in (('audience', .3), ('level', .25)):
        if random.random() < p: d[k] = random.choice(T[k])
    if 'mood' not in d and random.random() < .3: d['mood'] = [random.choice(T['mood'])]
    if t in ('exclude', 'include_exclude', 'negation'):
        d['exclude_hint'] = random.choice(['fiction', 'field', 'genre', 'mood', 'translated'])
    seeds.append(d)
for n in range(30):
    with open(os.path.join(HERE, 'qgen', f'seed-{n + 31:02d}.jsonl'), 'w', encoding='utf-8') as fh:
        for s in seeds[n * 100:(n + 1) * 100]:
            fh.write(json.dumps(s, ensure_ascii=False) + '\n')
from collections import Counter
print('genre seeds', sum('genre' in s for s in seeds), 'subfield seeds', sum(bool(s.get('subfield')) for s in seeds))
print(Counter(s['type'] for s in seeds))
