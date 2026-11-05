"""AI search plan step 3: the cards of the runs (run-<label>.json) for Sonnet (agent-JUDGE_RULES.md, 30 a file in
jd/in-NN.json), and with argv "score" the table per run and question set. The judged turn is the vague question, the
vague follow-up, the joining question (third turn), or the one rare / clear question; when the app asked back, its
answer after the simulated user's reply is judged and the ask round counts in time and tokens."""
import glob, json, os, sys

_REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
_PROJECTS = os.path.dirname(_REPO)  # the folder that holds this repo and chaekcheck-data
OUT = os.environ.get('AGENT_EVAL_OUT', os.path.join(_REPO, r'data\aisearch-eval-2026-10-25\agent'))
HERE = os.path.dirname(os.path.abspath(__file__))
QS = json.load(open(os.environ.get('AGENT_QS', os.path.join(HERE, 'agent_questions.json')), encoding='utf-8'))
CLEAR = {'k%02d' % x['id']: x['q'] for x in json.load(open(os.path.join(os.path.dirname(OUT), 'eval_questions.json'), encoding='utf-8'))}
WANT, CONTEXT = {}, {}
for x in QS['vague']: WANT[x['id']] = x['want']; CONTEXT[x['id']] = []
for x in QS['vague2']: WANT[x['id']] = x['want']; CONTEXT[x['id']] = [x['q1']]
for x in QS['combine']: WANT[x['id']] = x['want']; CONTEXT[x['id']] = x['q'][:2]
for x in QS['rare']: WANT[x['id']] = x['q']; CONTEXT[x['id']] = []
for k in QS['clear_ids']: WANT['k%02d' % k] = CLEAR['k%02d' % k]; CONTEXT['k%02d' % k] = []
RUNS = {os.path.basename(p)[4:-5]: json.load(open(p, encoding='utf-8')) for p in sorted(glob.glob(os.path.join(OUT, 'run-*.json')))}
jp = os.path.join(OUT, 'judged.json'); J = json.load(open(jp, encoding='utf-8')) if os.path.exists(jp) else {}
for p in glob.glob(os.path.join(OUT, 'jd', 'out-*.json')): J.update(json.load(open(p, encoding='utf-8')))
json.dump(J, open(jp, 'w', encoding='utf-8'), ensure_ascii=False)
SETS = ['vague', 'vague2', 'combine', 'rare', 'clear']


def judged_turn(item):
    return item['turns'][-1]


def stats(run, s):
    """One run's numbers for one question set."""
    ids = [k for k, v in run.items() if v['set'] == s]
    g, good, r = [], [], dict(n=len(ids), empty=0, asked=0, again=0, ms=[], tin=0, tout=0, ft=[])
    for k in ids:
        steps = judged_turn(run[k]); last = steps[-1]
        scores = [J[f"{k}|{c['bookId']}"] for c in last['cards'] if f"{k}|{c['bookId']}" in J]
        g += scores; good.append(sum(x >= 2 for x in scores))
        r['empty'] += not last['cards']; r['asked'] += any(st['ask'] for st in steps)
        r['again'] += any('search_again' in (st['agent'] or '') for st in steps)
        r['ms'].append(sum(st['ms'] or 0 for st in steps)); r['tin'] += sum(st['in'] or 0 for st in steps); r['tout'] += sum(st['out'] or 0 for st in steps)
        if steps[-1].get('firstText') is not None:
            r['ft'].append(sum(st.get('firstText') or 0 for st in steps[:-1]) + steps[-1]['firstText'])
    r.update(g=g, good=sum(good))
    return r


if sys.argv[1:] == ['avg']:
    # the mean of the runs of one way (label and label-r2), per question set and in all
    ways = {}
    for label in RUNS: ways.setdefault(label[:-3] if label.endswith('-r2') else label, []).append(label)
    print('| 방식 | 묶음 | 돌린 수 | 맞음 | 맞는 카드/질문 | 틀린 카드/질문 | 0장 | 되묻기 | 다시 찾기 | 시간(평균/느린10%) | 첫 글 | 입력/출력 토큰 |')
    print('|---|---|---|---|---|---|---|---|---|---|---|---|')
    for way, labels in ways.items():
        for s in SETS + ['all']:
            rs = [stats(RUNS[l], x) for l in labels for x in (SETS if s == 'all' else [s])]
            per = len(labels); n = sum(r['n'] for r in rs) / per; g = [x for r in rs for x in r['g']]; ms = sorted(m for r in rs for m in r['ms']); ft = [f for r in rs for f in r['ft']]
            print('| %s | %s | %d | %.1f%% | %.1f | %.2f | %.1f | %.1f | %.1f | %.1f / %.1fs | %s | %d / %d |' % (
                way, '전체' if s == 'all' else s, per, 100 * sum(x >= 2 for x in g) / max(len(g), 1), sum(r['good'] for r in rs) / per / n,
                sum(x <= 1 for x in g) / per / n, sum(r['empty'] for r in rs) / per, sum(r['asked'] for r in rs) / per, sum(r['again'] for r in rs) / per,
                sum(ms) / len(ms) / 1000, ms[int(0.9 * (len(ms) - 1))] / 1000, '%.1fs' % (sum(ft) / len(ft)) if ft else '—',
                sum(r['tin'] for r in rs) / per / n, sum(r['tout'] for r in rs) / per / n))
    sys.exit()

if sys.argv[1:] == ['score']:
    for label, run in RUNS.items():
        print(f'\n== {label}')
        total = {'g': [], 'n': 0}
        for s in SETS:
            ids = [k for k, v in run.items() if v['set'] == s]
            g, empty, asked, again, ms, tin, tout, good, ft = [], 0, 0, 0, [], [], [], [], []
            for k in ids:
                steps = judged_turn(run[k]); last = steps[-1]
                scores = [J[f"{k}|{c['bookId']}"] for c in last['cards'] if f"{k}|{c['bookId']}" in J]
                g += scores; good.append(sum(x >= 2 for x in scores))
                empty += not last['cards']; asked += any(st['ask'] for st in steps)
                again += any('search_again' in (st['agent'] or '') for st in steps)
                if steps[-1].get('firstText') is not None: ft.append(sum(st.get('firstText') or 0 for st in steps[:-1]) + steps[-1]['firstText'])
                ms.append(sum(st['ms'] or 0 for st in steps)); tin.append(sum(st['in'] or 0 for st in steps)); tout.append(sum(st['out'] or 0 for st in steps))
            n = len(ids); total['g'] += g; total['n'] += n
            p90 = sorted(ms)[int(0.9 * (n - 1))] / 1000 if n else 0
            print('%-8s n=%2d 맞음 %5.1f%% 맞는카드/질문 %.1f 틀린카드/질문 %.2f 0장 %d 되묻기 %d 다시찾기 %d 시간 %.1fs 느린10%% %.1fs 토큰 %d/%d%s' % (
                s, n, 100 * sum(x >= 2 for x in g) / max(len(g), 1), sum(good) / max(n, 1), sum(x <= 1 for x in g) / max(n, 1),
                empty, asked, again, sum(ms) / max(n, 1) / 1000, p90, sum(tin) / max(n, 1), sum(tout) / max(n, 1),
                ' 첫글 %.1fs' % (sum(ft) / len(ft)) if ft else ''))
        print('전체 맞음 %.1f%% (%d장) 틀린카드/질문 %.2f' % (100 * sum(x >= 2 for x in total['g']) / max(len(total['g']), 1), len(total['g']),
                                                   sum(x <= 1 for x in total['g']) / max(total['n'], 1)))
    sys.exit()

todo, seen = [], set()
for label, run in RUNS.items():
    for k, item in run.items():
        steps = judged_turn(item); last = steps[-1]
        talk = CONTEXT[k] + [st['q'] for st in steps]
        for c in last['cards']:
            key = f"{k}|{c['bookId']}"
            if key in J or key in seen: continue
            seen.add(key)
            todo.append({'key': key, 'talk': talk, 'want': WANT[k],
                         'book': {'title': c['title'], 'author': c.get('author'), 'line': c.get('summary') or ''}})
os.makedirs(os.path.join(OUT, 'jd'), exist_ok=True)
for p in glob.glob(os.path.join(OUT, 'jd', 'in-*.json')): os.remove(p)
for n, i in enumerate(range(0, len(todo), 30), 1):
    json.dump(todo[i:i + 30], open(os.path.join(OUT, 'jd', f'in-{n:02d}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
print('pairs', len(todo), 'files', (len(todo) + 29) // 30)
