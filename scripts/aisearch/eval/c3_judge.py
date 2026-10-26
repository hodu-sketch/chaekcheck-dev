"""3-turn test: the third-turn cards of every run-*.json for Sonnet (JUDGE_RULES.md, 25 a file in jd/), and with
argv "score" the scores per run and kind. Verdicts are cached by dialogue|book; the earlier turns differ between runs,
so the key also holds the run label."""
import glob, json, os, sys
from collections import Counter
HERE = os.path.dirname(os.path.abspath(__file__))
D = {x['id']: x for x in json.load(open(os.path.join(HERE, 'dialogues.json'), encoding='utf-8'))}
R = {os.path.basename(p)[4:-5]: json.load(open(p, encoding='utf-8')) for p in sorted(glob.glob(os.path.join(HERE, 'run-*.json')))}
jp = os.path.join(HERE, 'judged.json'); J = json.load(open(jp, encoding='utf-8')) if os.path.exists(jp) else {}
for p in glob.glob(os.path.join(HERE, 'jd', 'out-*.json')): J.update(json.load(open(p, encoding='utf-8')))
json.dump(J, open(jp, 'w', encoding='utf-8'), ensure_ascii=False)
if sys.argv[1:] == ['score']:
    for r, run in R.items():
        row = {}
        for k in ('combine', 'last_only', 'refers_books'):
            ids = [i for i in run if D[i]['kind'] == k]
            if not ids: continue
            g = [J[f"{r}|{i}|{c['bookId']}"] for i in ids for c in run[i][2]['cards'] if f"{r}|{i}|{c['bookId']}" in J]
            row[k] = '맞음 %.0f%% 아주 %.0f%% 틀린/질문 %.2f 빈 %d/%d' % (100 * sum(x >= 2 for x in g) / max(len(g), 1),
                100 * sum(x == 3 for x in g) / max(len(g), 1), sum(x <= 1 for x in g) / len(ids), sum(1 for i in ids if not run[i][2]['cards']), len(ids))
        print(r, row)
    sys.exit()
todo = []
for r, run in R.items():
    for i, t in run.items():
        x = D[i]
        for c in t[2]['cards']:
            k = f"{r}|{i}|{c['bookId']}"
            if k in J: continue
            todo.append({'key': k, 'q1': x['q1'], 'shown1': [s['title'] for s in t[0]['cards']], 'q2': x['q2'],
                         'shown2': [f"{n + 1}. {s['title']} / {s.get('author') or ''}" for n, s in enumerate(t[1]['cards'])],
                         'q3': x['q3'], 'want': x['want'], 'book': {'title': c['title'], 'author': c.get('author'), 'line': c.get('summary') or ''}})
for p in glob.glob(os.path.join(HERE, 'jd', 'in-*.json')): os.remove(p)
for n, i in enumerate(range(0, len(todo), 25), 1):
    json.dump(todo[i:i + 25], open(os.path.join(HERE, 'jd', f'in-{n:02d}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
print('pairs', len(todo), 'files', (len(todo) + 24) // 25)
