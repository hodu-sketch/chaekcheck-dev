"""AI search 2nd-step rule test (docs/reports/ai-search-rules-2026-10-27.md): runs rules_suite.json against a local
backend and keeps every answer with its head and card texts.
  python rules_run.py <label> <backend log> [port]     -> <out>/run-<label>.json
  python rules_run.py score                            -> the rule-by-rule table of every run in <out>
  python rules_run.py prep                             -> jd/in-NN.json for Sonnet (rules-JUDGE_RULES.md)
Earlier turns that ask back are answered by the simulated user (agent_run.simulated_user); a case with "typed" answers
the first ask-back with that text instead. History turns carry the ask-back (askQuestion, askChoices) as the web does."""
import glob, json, os, re, subprocess, sys, time, urllib.error, urllib.request, uuid

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.environ.get('RULES_OUT', r'C:\Users\yujun\Documents\Claude\Projects\chaekcheck-dev\data\aisearch-eval-2026-10-25\rules')
SUITE = json.load(open(os.path.join(HERE, 'rules_suite.json'), encoding='utf-8'))
EVAL = json.load(open(os.path.join(os.path.dirname(OUT), 'eval_questions.json'), encoding='utf-8'))
CASES = SUITE['cases'] + [{'id': 'q%02d' % x['id'], 'rule': 'clear', 'turns': [x['q']], 'want': x['q'], 'expect_ask': False}
                          for x in EVAL if x['id'] in SUITE['clear_ids']]
LINE = re.compile(r'ai search: first .*?cards (\d+), (\d+) ms, tokens (\d+)/(\d+)(.*)$')
BANNED = ['책 정보만으로는', '알기 어려', '확인하기 어려', '알 수 없어', '정보가 없', '후보', '목록']
TIME_WORDS = ['최근', '요즘', '신간', '최신', '이후', '년대', '나온', '올해']
# the publish time brought up unasked (P12); years of what a book is about are fine
YEAR = re.compile(r'(19|20)\d\d년(에)?\s*(나온|출간|발간|기준)|올해|최신|신작')
NUMBER = re.compile(r'\d+\s*번(?!째)')
INVITE = ['적어', '말씀', '알려', '써 주', '쓰셔도', '입력']
XSRF = str(uuid.uuid4()); N = [0]; IP0 = 100 + int(time.time()) % 100


def ask(port, log, q, history):
    N[0] += 1
    body = json.dumps({'question': q, 'turnNo': len(history) + 1, 'history': history, 'residenceRegionIds': [1114]}).encode()
    req = urllib.request.Request(f'http://localhost:{port}/api/v1/ai-searches/stream', body,
                                 {'Content-Type': 'application/json', 'X-XSRF-TOKEN': XSRF, 'Cookie': 'XSRF-TOKEN=' + XSRF,
                                  'X-Real-IP': '10.%d.%d.%d' % (IP0, N[0] // 200, N[0] % 200 + 1)})
    name, t0, seen, head, cards, texts, asked, result = None, time.time(), None, '', [], {}, None, None
    with urllib.request.urlopen(req, timeout=120) as r:
        for raw in r:
            line = raw.decode('utf-8').rstrip('\r\n')
            if line.startswith('event:'):
                name = line[6:].strip()
            elif line.startswith('data:'):
                d = json.loads(line[5:])
                if seen is None and name in ('head-delta', 'card', 'ask'):
                    seen = round(time.time() - t0, 2)
                if name == 'head-delta': head += d['text']
                elif name == 'card': cards.append({'bookId': d['bookId'], 'title': d['title'], 'author': d.get('author'), 'summary': d.get('aiSummary')})
                elif name == 'card-delta': texts[d['bookId']] = texts.get(d['bookId'], '') + d['text']
                elif name == 'ask': asked = d
                elif name == 'done': result = d['result']
    time.sleep(0.4)
    logged = log.lines()
    g = logged[-1] if logged else None
    for c in cards:
        c['text'] = texts.get(c['bookId'], '')
    return {'q': q, 'firstText': seen, 'wall': round(time.time() - t0, 2), 'result': result, 'head': head, 'cards': cards,
            'ask': asked, 'ms': int(g[1]) if g else None, 'in': int(g[2]) if g else None, 'out': int(g[3]) if g else None}


class Log:
    def __init__(self, path):
        self.path = path; self.pos = os.path.getsize(path)

    def lines(self):
        with open(self.path, 'rb') as f:
            f.seek(self.pos); data = f.read(); self.pos = f.tell()
        return [m.groups() for m in (LINE.search(l) for l in data.decode('utf-8', 'replace').splitlines()) if m]


def turn_of(q, step):
    if step['ask']:
        return {'question': q, 'bookIds': [], 'askQuestion': step['ask']['question'], 'askChoices': step['ask']['choices']}
    return {'question': q, 'bookIds': [c['bookId'] for c in step['cards']]}


def run_case(port, log, case):
    from agent_run import simulated_user
    history, steps = [], []
    for i, q in enumerate(case['turns']):
        step = ask(port, log, q, history); steps.append(step)
        last = i == len(case['turns']) - 1
        if step['ask'] and (not last or case.get('typed') or (case['expect_ask'] is False and case['want'])):
            reply = case['typed'] if case.get('typed') and last else simulated_user(case['want'] if last else q, step['ask'])
            history = history + [turn_of(q, step)]
            step2 = ask(port, log, reply, history); step2['reply'] = reply; steps.append(step2)
            history = history + [turn_of(reply, step2)]
        else:
            history = history + [turn_of(q, step)]
    return steps


def main_run(label, logpath, port):
    log = Log(logpath); out = {}
    os.makedirs(OUT, exist_ok=True)
    for case in CASES:
        for attempt in range(2):
            try:
                out[case['id']] = {'rule': case['rule'], 'steps': run_case(port, log, case)}
                break
            except urllib.error.HTTPError as e:
                print('http', e.code, case['id'], flush=True); time.sleep(3)
        json.dump(out, open(os.path.join(OUT, f'run-{label}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
        print(label, case['id'], 'ask' if any(s['ask'] for s in out.get(case['id'], {}).get('steps', [])) else '', flush=True)


def lines_of(ids):
    if not ids:
        return {}
    sql = 'select book_id, line from aisearch.search_book where book_id in (%s)' % ','.join(str(i) for i in ids)
    text = subprocess.run(['wsl', '-d', 'Ubuntu', '--', 'docker', 'exec', 'chaekcheck-db-1', 'psql', '-U', 'chaekcheck', '-d',
                           'chaekcheck', '-tA', '-F', '\t', '-c', sql], capture_output=True).stdout.decode('utf-8', 'replace')
    return {int(l.split('\t')[0]): l.split('\t', 1)[1] for l in text.splitlines() if '\t' in l}


def judged_step(case, steps):
    """The step the card judge looks at: the last one (after the reply when the last turn asked back)."""
    return steps[-1]


def checks(case, steps, lines):
    """Automatic rule checks of one case: (asked as expected, banned words, years unasked, numbers, copied, invite)."""
    c = {x['id']: x for x in CASES}[case]
    # the last turn's own step: the typed answer for a typed case, else the last step that is not a simulated reply
    last_turn_step = steps[-1] if c.get('typed') else [s for s in steps if s.get('reply') is None][-1]
    asked = bool(last_turn_step and last_turn_step['ask'])
    shown = [s for s in steps if not s['ask']]
    words = ' '.join(s['head'] + ' ' + ' '.join(x['text'] for x in s['cards']) for s in shown)
    question = ' '.join(c['turns']) + ' ' + (c.get('typed') or '')
    copied = 0
    for s in shown:
        for x in s['cards']:
            line = lines.get(x['bookId'], '') or ''
            if any(line[i:i + 13] in x['text'] for i in range(0, max(0, len(line) - 12))):
                copied += 1
    invite = [any(w in s['ask']['question'] for w in INVITE) for s in steps if s['ask']]
    return {'asked': asked, 'ok_ask': None if c['expect_ask'] is None else asked == c['expect_ask'],
            'banned': sum(words.count(b) for b in BANNED), 'number': len(NUMBER.findall(words)),
            'year': 0 if any(w in question for w in TIME_WORDS) else len(YEAR.findall(words)), 'copied': copied,
            'invite': invite, 'first_ask': bool(steps[0]['ask']) if c.get('typed') else None}


def score():
    runs = {os.path.basename(p)[4:-5]: json.load(open(p, encoding='utf-8')) for p in sorted(glob.glob(os.path.join(OUT, 'run-*.json')))}
    jp = os.path.join(OUT, 'judged.json'); J = json.load(open(jp, encoding='utf-8')) if os.path.exists(jp) else {}
    for p in glob.glob(os.path.join(OUT, 'jd', 'out-*.json')): J.update(json.load(open(p, encoding='utf-8')))
    json.dump(J, open(jp, 'w', encoding='utf-8'), ensure_ascii=False)
    ids = {x['bookId'] for r in runs.values() for v in r.values() for s in v['steps'] for x in s['cards']}
    lines = lines_of(sorted(ids))
    rules = []
    for c in CASES:
        if c['rule'] not in rules: rules.append(c['rule'])
    ways = {}
    for label in runs: ways.setdefault(label[:-3] if label.endswith('-r2') else label, []).append(label)
    print('| 규칙 묶음 | 수 | ' + ' | '.join(ways) + ' |'); print('|---|---|' + '---|' * len(ways))
    for rule in rules + ['전체']:
        cells = []
        for way, labels in ways.items():
            ok, n, g, good, bad, extra = 0, 0, [], 0, 0, []
            for label in labels:
                for cid, v in runs[label].items():
                    if rule != '전체' and v['rule'] != rule: continue
                    ch = checks(cid, v['steps'], lines); n += 1
                    if ch['ok_ask'] is not None: ok += ch['ok_ask']
                    st = judged_step(cid, v['steps'])
                    sc = [J[f"{cid}|{x['bookId']}"] for x in st['cards'] if f"{cid}|{x['bookId']}" in J]
                    g += sc; good += sum(x >= 2 for x in sc); bad += sum(x <= 1 for x in sc)
                    extra.append(ch)
            expect_n = sum(1 for e in extra if e['ok_ask'] is not None)
            cell = '되묻기 %d/%d' % (ok, expect_n) if expect_n else ''
            if g: cell += ' · 맞음 %.0f%% · 맞는 %.1f · 틀린 %.2f' % (100 * sum(x >= 2 for x in g) / len(g), good / n, bad / n)
            cells.append(cell)
        print(f'| {rule} | {n // max(1, len(labels))} | ' + ' | '.join(cells) + ' |')
    print()
    for way, labels in ways.items():
        ex = [checks(cid, v['steps'], lines) for label in labels for cid, v in runs[label].items()]
        inv = [x for e in ex for x in e['invite']]
        steps = [s for label in labels for v in runs[label].values() for s in v['steps']]
        ft = sorted(s['firstText'] for s in steps if s['firstText'] is not None)
        print(f"{way}: 금지 표현 {sum(e['banned'] for e in ex)}, 책 번호 {sum(e['number'] for e in ex)}, 묻지 않은 연도 {sum(e['year'] for e in ex)}, "
              f"한 줄 베낌 카드 {sum(e['copied'] for e in ex)}, 직접 적으라는 말 {sum(inv)}/{len(inv)}, "
              f"첫 글 중간 {ft[len(ft) // 2]:.1f}s 느린10% {ft[int(0.9 * (len(ft) - 1))]:.1f}s, 출력 토큰 중간 {sorted(s['out'] or 0 for s in steps)[len(steps) // 2]}")


def prep():
    runs = [json.load(open(p, encoding='utf-8')) for p in sorted(glob.glob(os.path.join(OUT, 'run-*.json')))]
    jp = os.path.join(OUT, 'judged.json'); J = json.load(open(jp, encoding='utf-8')) if os.path.exists(jp) else {}
    for p in glob.glob(os.path.join(OUT, 'jd', 'out-*.json')): J.update(json.load(open(p, encoding='utf-8')))
    json.dump(J, open(jp, 'w', encoding='utf-8'), ensure_ascii=False)
    by_id = {c['id']: c for c in CASES}
    todo, seen = [], set()
    for run in runs:
        for cid, v in run.items():
            c = by_id[cid]
            if not c['want']: continue
            st = judged_step(cid, v['steps'])
            talk = [s['q'] if s.get('reply') is None else s['reply'] for s in v['steps']]
            for x in st['cards']:
                key = f"{cid}|{x['bookId']}"
                if key in J or key in seen: continue
                seen.add(key)
                todo.append({'key': key, 'talk': talk, 'want': c['want'], 'book': {'title': x['title'], 'author': x.get('author'), 'line': x.get('summary') or ''}})
    os.makedirs(os.path.join(OUT, 'jd'), exist_ok=True)
    for p in glob.glob(os.path.join(OUT, 'jd', 'in-*.json')): os.remove(p)
    for n, i in enumerate(range(0, len(todo), 30), 1):
        json.dump(todo[i:i + 30], open(os.path.join(OUT, 'jd', f'in-{n:02d}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
    print('pairs', len(todo), 'files', (len(todo) + 29) // 30)


if __name__ == '__main__':
    sys.path.insert(0, HERE)
    if sys.argv[1] == 'score': score()
    elif sys.argv[1] == 'prep': prep()
    else: main_run(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 8097)
