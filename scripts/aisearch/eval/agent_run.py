"""AI search plan step 3 (user 2026-10-26): runs agent_questions.json against a local backend and keeps every answer.
  python agent_run.py <label> <backend log> [port]   -> <out dir>/run-<label>.json
The backend's second step is set when it starts (CHAEKCHECK_AISEARCH_PICK_MODE, ..._PICK_SEARCH_AGAIN_WHEN). Each
request's time, tokens and agent path come from the backend's "ai search:" log line. When the app asks back, a
simulated user (Haiku, the dev .env key) picks the choice nearest the hidden want, or types the want when none is
near, and that is asked as the next question."""
import json, os, re, sys, time, urllib.error, urllib.request, uuid

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
OUT = os.environ.get('AGENT_EVAL_OUT', r'C:\Users\yujun\Documents\Claude\Projects\chaekcheck-dev\data\aisearch-eval-2026-10-25\agent')
QS = json.load(open(os.path.join(HERE, 'agent_questions.json'), encoding='utf-8'))
CLEAR = json.load(open(os.path.join(os.path.dirname(OUT), 'eval_questions.json'), encoding='utf-8'))
LINE = re.compile(r'ai search: first (.*?), keyword (\S+), pick (\S+), candidates (\d+) .*?cards (\d+), (\d+) ms, tokens (\d+)/(\d+)(.*)$')
XSRF = str(uuid.uuid4()); N = [0]; IP0 = 100 + int(time.time()) % 100


def key():
    for line in open(os.path.join(ROOT, '.env'), encoding='utf-8'):
        if line.startswith('CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY='):
            return line.split('=', 1)[1].strip().strip('"')
    raise SystemExit('no key in .env')


class Log:
    def __init__(self, path):
        self.path = path; self.pos = os.path.getsize(path)

    def lines(self):
        time.sleep(0.4)
        with open(self.path, 'rb') as f:
            f.seek(self.pos); data = f.read(); self.pos = f.tell()
        return [m.groups() for m in (LINE.search(l) for l in data.decode('utf-8', 'replace').splitlines()) if m]


def ask(port, log, q, history):
    for attempt in range(3):
        try:
            return ask_once(port, log, q, history)
        except urllib.error.HTTPError as e:
            print('http', e.code, q, e.read().decode('utf-8', 'replace')[:300], flush=True)
            if attempt == 2: raise
            time.sleep(3)


def ask_once(port, log, q, history):
    N[0] += 1
    body = json.dumps({'question': q, 'turnNo': len(history) + 1, 'history': history, 'residenceRegionIds': [1114]}).encode()
    req = urllib.request.Request(f'http://localhost:{port}/api/v1/ai-searches/stream', body,
                                 {'Content-Type': 'application/json', 'X-XSRF-TOKEN': XSRF, 'Cookie': 'XSRF-TOKEN=' + XSRF,
                                  'X-Real-IP': '10.%d.%d.%d' % (IP0, N[0] // 200, N[0] % 200 + 1)})
    ev, name, t0, seen = [], None, time.time(), None
    with urllib.request.urlopen(req, timeout=120) as r:
        for raw in r:
            line = raw.decode('utf-8').rstrip('\r\n')
            if line.startswith('event:'): name = line[6:].strip()
            elif line.startswith('data:'):
                ev.append((name, json.loads(line[5:])))
                if seen is None and name in ('head-delta', 'card', 'ask'): seen = round(time.time() - t0, 2)
    wall = round(time.time() - t0, 2)
    done = [d for n, d in ev if n == 'done']; asks = [d for n, d in ev if n == 'ask']
    logged = log.lines()
    g = logged[-1] if logged else None
    return {'q': q, 'wall': wall, 'firstText': seen, 'result': done[-1]['result'] if done else None,
            'head': ''.join(d['text'] for n, d in ev if n == 'head-delta'),
            'cards': [{'bookId': c['bookId'], 'title': c['title'], 'author': c.get('author'), 'summary': c.get('aiSummary')}
                      for n, c in ev if n == 'card'],
            'ask': asks[-1] if asks else None,
            'ms': int(g[5]) if g else None, 'in': int(g[6]) if g else None, 'out': int(g[7]) if g else None,
            'agent': (re.search(r'agent (\S+)', g[8]).group(1) if g and 'agent ' in g[8] else None),
            'first': g[0] if g else None, 'candidates': int(g[3]) if g else None}


def simulated_user(want, a):
    choices = '\n'.join(f'{i + 1}. {c}' for i, c in enumerate(a['choices']))
    prompt = (f'당신은 책을 찾는 사람이다. 실제로 원하는 책: {want}\n앱이 되물었다: {a["question"]}\n고를 답:\n{choices}\n'
              '원하는 책에 가장 가까운 답의 번호 하나만 숫자로 답한다. 어느 것도 가깝지 않으면 0.')
    body = json.dumps({'model': 'claude-haiku-5-5', 'max_tokens': 256, 'messages': [{'role': 'user', 'content': prompt}]}).encode()
    req = urllib.request.Request('https://api.anthropic.com/v1/messages', body,
                                 {'content-type': 'application/json', 'x-api-key': KEY, 'anthropic-version': '2023-06-01'})
    content = json.load(urllib.request.urlopen(req, timeout=60))['content']
    text = ''.join(c.get('text', '') for c in content if c.get('type') == 'text')
    m = re.search(r'\d+', text); n = int(m.group()) if m else 0
    return a['choices'][n - 1] if 1 <= n <= len(a['choices']) else want


def turn(port, log, q, history, want):
    """One question, plus the simulated user's answer when the app asks back (once; a second ask stops there)."""
    steps = [ask(port, log, q, history)]
    if steps[0]['ask'] and want:
        reply = simulated_user(want, steps[0]['ask'])
        steps.append(ask(port, log, reply, history + [{'question': q, 'bookIds': []}]))
        steps[-1]['reply'] = reply
    return steps


def after(history, steps):
    for s in steps:
        history = history + [{'question': s['q'], 'bookIds': [c['bookId'] for c in s['cards']]}]
    return history


def save(out, label):
    os.makedirs(OUT, exist_ok=True)
    json.dump(out, open(os.path.join(OUT, f'run-{label}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)


def main():
    label, logpath = sys.argv[1], sys.argv[2]; port = int(sys.argv[3]) if len(sys.argv) > 3 else 8097
    log = Log(logpath); out = {}
    for x in QS['vague']:
        out[x['id']] = {'set': 'vague', 'turns': [turn(port, log, x['q'], [], x['want'])]}
        save(out, label)
    for x in QS['vague2']:
        t1 = turn(port, log, x['q1'], [], None)
        out[x['id']] = {'set': 'vague2', 'turns': [t1, turn(port, log, x['q2'], after([], t1), x['want'])]}
        save(out, label)
    for x in QS['combine']:
        h, turns = [], []
        for i, q in enumerate(x['q']):
            t = turn(port, log, q, h, x['want'] if i == 2 else q); turns.append(t); h = after(h, t)
        out[x['id']] = {'set': 'combine', 'turns': turns}
        save(out, label)
    for x in QS['rare']:
        out[x['id']] = {'set': 'rare', 'turns': [turn(port, log, x['q'], [], x['q'])]}
        save(out, label)
    for x in CLEAR:
        if x['id'] in QS['clear_ids']:
            out['k%02d' % x['id']] = {'set': 'clear', 'turns': [turn(port, log, x['q'], [], x['q'])]}
            save(out, label)
    for k, v in out.items():
        last = v['turns'][-1][-1]
        print(label, k, last['result'], len(last['cards']), 'ask' if any(s['ask'] for t in v['turns'] for s in t) else '', flush=True)
    os.makedirs(OUT, exist_ok=True)
    json.dump(out, open(os.path.join(OUT, f'run-{label}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)


KEY = key()
if __name__ == '__main__':
    main()
