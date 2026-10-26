"""How long Haiku takes to its first text with the 2nd-step prompt, by prompt shape (user 2026-10-27 「haiku가 첫 응답을
하기까지의 시간을 줄이고 싶어」). Builds the pick prompt like Prompts.java (system text taken from the source), with 30
candidates from the local search_book, and streams each shape a few times in turn; the stream is closed once the
head's first character comes, so little output is paid for.
  python ttft_probe.py [rounds]     -> prints the table, writes ttft-probe.json next to the eval data
Needs CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY in ../../../.env and the local db."""
import json, os, re, statistics, subprocess, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
PROMPTS = os.path.join(ROOT, 'backend', 'src', 'main', 'java', 'com', 'chaekcheck', 'aisearch', 'internal', 'flow', 'Prompts.java')
OUT = r'C:\Users\yujun\Documents\Claude\Projects\chaekcheck-dev\data\aisearch-eval-2026-10-25\ttft-probe.json'


def key():
    for line in open(os.path.join(ROOT, '.env'), encoding='utf-8'):
        if line.startswith('CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY='):
            return line.split('=', 1)[1].strip().strip('"')
    raise SystemExit('no key')


def text_block(src, name):
    # a Java text block constant: its lines, common indentation removed
    m = re.search(name + r' = (?:PICK_SYSTEM\.replace\("- 정해진 JSON 형식으로만 답한다\.", )?"""\n(.*?)"""', src, re.S)
    lines = m.group(1).split('\n')
    indent = min(len(l) - len(l.lstrip('\t')) for l in lines if l.strip())
    return '\n'.join(l[indent:] for l in lines).rstrip('\n')


src = open(PROMPTS, encoding='utf-8').read()
PICK_SYSTEM = text_block(src, 'PICK_SYSTEM')
ASK_TAIL = text_block(src, 'PICK_ASK_SYSTEM')
PICK_ASK_SYSTEM = PICK_SYSTEM.replace('- 정해진 JSON 형식으로만 답한다.', ASK_TAIL)
PICK_SCHEMA = json.loads(re.search(r'PICK_SCHEMA = """\n(.*?)"""', src, re.S).group(1).replace('\n', '').replace('\t', ''))
ASK_SCHEMA = json.loads(re.search(r'PICK_ASK_SCHEMA = """\n(.*?)"""', src, re.S).group(1).replace('\n', '').replace('\t', ''))


def books(word, n=30):
    sql = ("select title, coalesce(author,''), line from aisearch.search_book where line is not null and search_text like "
           f"'%{word}%' order by cardinality(library_ids) desc limit {n};")
    out = subprocess.run(['wsl', '-d', 'Ubuntu', '--', 'docker', 'exec', 'chaekcheck-db-1', 'psql', '-U', 'chaekcheck', '-d', 'chaekcheck',
                          '-tA', '-F', '\t', '-c', sql], capture_output=True).stdout.decode('utf-8')
    return [l.split('\t') for l in out.splitlines() if l.count('\t') == 2]


def user_message(question, rows, line_chars=None, labels=True, year=True):
    parts = ['올해: 2026년', '질문: ' + question, '고를 수 있는 책:']
    for i, (title, author, line) in enumerate(rows, 1):
        line = line if line_chars is None else line[:line_chars]
        if labels:
            p = f'{i}. 제목: {title} / 저자: {author or "(모름)"}' + (' / 출간: 2019년' if year else '') + f' / 한 줄: {line}'
        else:
            p = f'{i}. {title} / {author or "-"}' + (' / 2019' if year else '') + f' / {line}'
        parts.append(p)
    return '\n'.join(parts)


def call(system, schema, user, cache=True):
    body = {'model': 'claude-haiku-5-5', 'max_tokens': 3072, 'stream': True, 'thinking': {'type': 'disabled'},
            'system': [{'type': 'text', 'text': system, **({'cache_control': {'type': 'ephemeral'}} if cache else {})}],
            'messages': [{'role': 'user', 'content': user}],
            'output_config': {'effort': 'low', 'format': {'type': 'json_schema', 'schema': schema}}}
    req = urllib.request.Request('https://api.anthropic.com/v1/messages', json.dumps(body).encode(),
                                 {'content-type': 'application/json', 'x-api-key': KEY, 'anthropic-version': '2026-10-21'})
    t0 = time.time(); first = head = None; text = ''; usage = {}
    with urllib.request.urlopen(req, timeout=60) as r:
        for raw in r:
            line = raw.decode('utf-8').strip()
            if not line.startswith('data:'):
                continue
            ev = json.loads(line[5:])
            if ev['type'] == 'message_start':
                usage = ev['message']['usage']
            if ev['type'] == 'content_block_delta' and ev['delta'].get('type') == 'text_delta':
                if first is None:
                    first = time.time() - t0
                text += ev['delta']['text']
                # the head's first character: text after "head":" that is not the closing quote
                m = re.search(r'"head"\s*:\s*"(.)', text)
                if m and m.group(1) != '"':
                    head = time.time() - t0
                    break
                if re.search(r'"head"\s*:\s*""', text):   # asked back or empty head: the ask question is the first text
                    head = time.time() - t0
                    break
    return {'first': first, 'head': head, 'in': usage.get('input_tokens', 0) + usage.get('cache_read_input_tokens', 0)
            + usage.get('cache_creation_input_tokens', 0), 'cached': usage.get('cache_read_input_tokens', 0)}


KEY = key()
QUESTIONS = [('잠들기 전에 읽기 좋은 과학책', '과학'), ('기후 위기를 다룬 책', '기후'), ('따뜻한 위로가 되는 에세이', '에세이')]
SHAPES = {
    'A 지금 그대로(되묻기 지시문)': dict(system=PICK_ASK_SYSTEM, schema=ASK_SCHEMA),
    'B 되묻기 없는 지시문': dict(system=PICK_SYSTEM, schema=PICK_SCHEMA),
    'C 한 줄 80자까지': dict(system=PICK_ASK_SYSTEM, schema=ASK_SCHEMA, line_chars=80),
    'D 후보 20권': dict(system=PICK_ASK_SYSTEM, schema=ASK_SCHEMA, n=20),
    'E 항목 표시·연도 빼기': dict(system=PICK_ASK_SYSTEM, schema=ASK_SCHEMA, labels=False, year=False),
    'F 프롬프트 캐싱 끔': dict(system=PICK_ASK_SYSTEM, schema=ASK_SCHEMA, cache=False),
}
rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 5
rows = {w: books(w) for _, w in QUESTIONS}
res = {s: [] for s in SHAPES}
for r in range(rounds):
    for q, w in QUESTIONS:
        for name, sh in SHAPES.items():
            user = user_message(q, rows[w][:sh.get('n', 30)], sh.get('line_chars'), sh.get('labels', True), sh.get('year', True))
            try:
                res[name].append(call(sh['system'], sh['schema'], user, sh.get('cache', True)))
            except Exception as e:
                print('fail', name, e, flush=True)
    print('round', r + 1, flush=True)
print('| 모양 | 입력 토큰 | 캐시로 읽음 | 첫 글자 중간 | 머리말 첫 글자 중간 | 머리말 느린 10% |')
print('|---|---|---|---|---|---|')
for name, xs in res.items():
    xs = [x for x in xs if x['first'] is not None and x['head'] is not None]
    hs = sorted(x['head'] for x in xs)
    print(f"| {name} | {statistics.median(x['in'] for x in xs):.0f} | {statistics.median(x['cached'] for x in xs):.0f} | "
          f"{statistics.median(x['first'] for x in xs):.2f}초 | {statistics.median(hs):.2f}초 | {hs[int(0.9 * (len(hs) - 1))]:.2f}초 |")
json.dump(res, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
