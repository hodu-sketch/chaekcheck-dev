"""Follow-up test 2: the (dialogue, card) pairs of the three modes for Sonnet (fu/JUDGE_RULES.md format, 25 a file in
jd/), and with argv "score" the scores per mode and relation. ask_one and none are not graded by card: none counts
the answers without cards, ask_one is left out (a question about one shown book, answered with cards either way)."""
import glob, json, os, sys
from collections import defaultdict
HERE = os.path.dirname(os.path.abspath(__file__))
T = json.load(open(os.path.join(HERE, 'turn1.json'), encoding='utf-8')); D = T['dialogues']; T1 = T['turn1']
# every turn2-<mode>[-rN].json; repeat runs reuse the verdicts already given to the same (dialogue, book) pair
T2 = {os.path.basename(p)[6:-5]: json.load(open(p, encoding='utf-8')) for p in sorted(glob.glob(os.path.join(HERE, 'turn2-*.json')))}
jp = os.path.join(HERE, 'judged.json'); J = json.load(open(jp, encoding='utf-8')) if os.path.exists(jp) else {}
for p in glob.glob(os.path.join(HERE, 'jd', 'out-*.json')): J.update(json.load(open(p, encoding='utf-8')))
json.dump(J, open(jp, 'w', encoding='utf-8'), ensure_ascii=False)
if sys.argv[1:] == ['score']:
    rels = ['refers', 'continue', 'shift', 'feedback', 'new']
    for m in T2:
        row, allg, n = {}, [], 0
        for r in rels:
            ids = [x['id'] for x in D if x['rel'] == r]
            g = [J[f"{i}|{c['bookId']}"] for i in ids for c in T2[m][i]['cards'] if f"{i}|{c['bookId']}" in J]
            allg += g; n += len(ids)
            empty = sum(1 for i in ids if not T2[m][i]['cards'])
            row[r] = '맞음 %.0f%% 틀린 %.2f 빈 %d/%d' % (100 * sum(x >= 2 for x in g) / max(len(g), 1), sum(x <= 1 for x in g) / len(ids), empty, len(ids))
        none = [x['id'] for x in D if x['rel'] == 'none']
        row['none'] = '0장 %d/%d' % (sum(1 for i in none if not T2[m][i]['cards']), len(none))
        print(m, '전체 맞음 %.1f%% 아주 %.1f%% 틀린/질문 %.2f' % (100 * sum(x >= 2 for x in allg) / len(allg), 100 * sum(x == 3 for x in allg) / len(allg), sum(x <= 1 for x in allg) / n), row)
    sys.exit()
todo, seen = [], set()
for m in T2:
    for x in D:
        if x['rel'] in ('ask_one', 'none'): continue
        for c in T2[m][x['id']]['cards']:
            k = f"{x['id']}|{c['bookId']}"
            if k in J or k in seen: continue
            seen.add(k)
            todo.append({'key': k, 'q1': x['q1'], 'shown': [f"{n + 1}. {s['title']} / {s.get('author') or ''}" for n, s in enumerate(T1[x['id']]['cards'])],
                         'q2': x['q2'], 'want': '', 'book': {'title': c['title'], 'author': c.get('author'), 'line': c.get('summary') or ''}})
os.makedirs(os.path.join(HERE, 'jd'), exist_ok=True)
for p in glob.glob(os.path.join(HERE, 'jd', 'in-*.json')): os.remove(p)
for n, i in enumerate(range(0, len(todo), 25), 1):
    json.dump(todo[i:i + 25], open(os.path.join(HERE, 'jd', f'in-{n:02d}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
print('pairs', len(todo), 'files', (len(todo) + 24) // 25)
