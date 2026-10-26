"""Second judge (user 2026-10-26 "빠진 측정" 3): 100 (dialogue, card) pairs Sonnet already graded, 25 per score
0-3 where there are enough, for Opus with the same JUDGE_RULES.md, in xj/in-*.json. With argv "score": agreement."""
import glob, json, os, random, sys
from collections import Counter
HERE = os.path.dirname(os.path.abspath(__file__))
T = json.load(open(os.path.join(HERE, 'turn1.json'), encoding='utf-8')); D = {x['id']: x for x in T['dialogues']}; T1 = T['turn1']
J = json.load(open(os.path.join(HERE, 'judged.json'), encoding='utf-8'))
os.makedirs(os.path.join(HERE, 'xj'), exist_ok=True)
if sys.argv[1:] == ['score']:
    X = {}
    for p in glob.glob(os.path.join(HERE, 'xj', 'out-*.json')): X.update(json.load(open(p, encoding='utf-8')))
    ks = [k for k in X if k in J]; same = sum(X[k] == J[k] for k in ks); near = sum(abs(X[k] - J[k]) <= 1 for k in ks)
    ok = sum((X[k] >= 2) == (J[k] >= 2) for k in ks)
    print('pairs', len(ks), 'same score %d%%' % (100 * same / len(ks)), 'within 1 %d%%' % (100 * near / len(ks)), 'same 맞음/틀림 %d%%' % (100 * ok / len(ks)))
    print('sonnet x opus', sorted(Counter((J[k], X[k]) for k in ks).items()))
    sys.exit()
cards = {}
for p in glob.glob(os.path.join(HERE, 'turn2-*.json')):
    for i, a in json.load(open(p, encoding='utf-8')).items():
        for c in a['cards']: cards[f"{i}|{c['bookId']}"] = c
by = {s: sorted(k for k in J if J[k] == s and k in cards) for s in range(4)}
rnd = random.Random(7); pick = []
for s in range(4): pick += rnd.sample(by[s], min(25, len(by[s])))
rest = [k for k in J if k in cards and k not in pick]; pick += rnd.sample(rest, 100 - len(pick)); rnd.shuffle(pick)
items = []
for k in pick:
    i = k.split('|')[0]; x = D[i]; c = cards[k]
    items.append({'key': k, 'q1': x['q1'], 'shown': [f"{n + 1}. {s['title']} / {s.get('author') or ''}" for n, s in enumerate(T1[i]['cards'])],
                  'q2': x['q2'], 'want': '', 'book': {'title': c['title'], 'author': c.get('author'), 'line': c.get('summary') or ''}})
for n, i in enumerate(range(0, 100, 25), 1):
    json.dump(items[i:i + 25], open(os.path.join(HERE, 'xj', f'in-{n:02d}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
print('sonnet scores in sample', Counter(J[k] for k in pick))
