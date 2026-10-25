"""Seeds for Sonnet-made training questions: 30 files x 100, each seed = question type + target shelves
from taxonomy2.json, sampled so every subfield, genre, country, audience, level, form and mood appears."""
import json, os, random
HERE = os.path.dirname(os.path.abspath(__file__))
T = json.load(open(os.path.join(HERE, 'taxonomy2.json'), encoding='utf-8'))
random.seed(20261025)
TYPES = [('single', 22), ('multi', 14), ('exclude', 10), ('include_exclude', 10), ('negation', 8), ('indirect', 14),
         ('conflict', 4), ('vague', 6), ('similar_book', 4), ('not_book', 3), ('title_like', 3), ('refers_shown', 2)]
pool = [t for t, w in TYPES for _ in range(w)]
fields = [f for f in T['field'] if f != '기타']
subs = [(f, s) for f in fields for s in T['field'][f]]
def seed(i):
    t = random.choice(pool)
    d = {'seed': i, 'type': t}
    if t in ('not_book', 'title_like', 'refers_shown'):
        return d
    f, s = random.choice(subs) if random.random() < .8 else (random.choice(fields), '')
    d['field'], d['subfield'] = f, s
    if f in ('소설', '시·희곡') or s in ('어린이 소설', '청소년 소설', '동화', '만화·웹툰') or random.random() < .1:
        d['genre'] = random.sample(T['genre'], 1)
        if random.random() < .5: d['country'] = random.choice(T['country'])
    for k, p in (('audience', .35), ('level', .3)):
        if random.random() < p: d[k] = random.choice(T[k])
    for k, p in (('form', .2), ('mood', .35)):
        if random.random() < p: d[k] = random.sample(T[k], 1)
    if t in ('exclude', 'include_exclude', 'negation'):
        d['exclude_hint'] = random.choice(['fiction', 'field', 'genre', 'mood', 'translated'])
    return d
seeds = [seed(i + 1) for i in range(3000)]
for n in range(30):
    with open(os.path.join(HERE, 'qgen', f'seed-{n + 1:02d}.jsonl'), 'w', encoding='utf-8') as fh:
        for s in seeds[n * 100:(n + 1) * 100]:
            fh.write(json.dumps(s, ensure_ascii=False) + '\n')
from collections import Counter
print(Counter(s['type'] for s in seeds))
print('subfields covered', len({s.get('subfield') for s in seeds if s.get('subfield')}), 'of', len(subs))
