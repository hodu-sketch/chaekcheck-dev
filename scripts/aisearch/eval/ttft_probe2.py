"""Where Haiku's fixed ~1.1 s to the first text goes (follow-up of ttft_probe.py): structured output on/off, effort
on/off, and a tiny prompt as the floor."""
import json, re, statistics, sys, time, urllib.request
sys.argv = ['x', '0']
src = open('ttft_probe.py', encoding='utf-8').read().split('KEY = key()')[0]
exec(src)
KEY = key()


def call2(system, user, schema=None, effort=True, cache=True):
    body = {'model': 'claude-haiku-5-5', 'max_tokens': 3072, 'stream': True, 'thinking': {'type': 'disabled'},
            'system': [{'type': 'text', 'text': system, **({'cache_control': {'type': 'ephemeral'}} if cache else {})}],
            'messages': [{'role': 'user', 'content': user}]}
    oc = {}
    if effort: oc['effort'] = 'low'
    if schema: oc['format'] = {'type': 'json_schema', 'schema': schema}
    if oc: body['output_config'] = oc
    req = urllib.request.Request('https://api.anthropic.com/v1/messages', json.dumps(body).encode(),
                                 {'content-type': 'application/json', 'x-api-key': KEY, 'anthropic-version': '2026-10-21'})
    t0 = time.time(); first = None
    with urllib.request.urlopen(req, timeout=60) as r:
        for raw in r:
            line = raw.decode('utf-8').strip()
            if line.startswith('data:'):
                ev = json.loads(line[5:])
                if ev['type'] == 'content_block_delta':
                    first = time.time() - t0
                    break
    return first


QUESTIONS = [('잠들기 전에 읽기 좋은 과학책', '과학'), ('기후 위기를 다룬 책', '기후'), ('따뜻한 위로가 되는 에세이', '에세이')]
rows = {w: books(w) for _, w in QUESTIONS}
noformat_system = PICK_ASK_SYSTEM.replace('- 정해진 JSON 형식으로만 답한다.', '- 다음 JSON 하나로만 답한다: {"ask":{"question":"","choices":[]},"head":"","picks":[{"no":1,"fit":3,"text":""}]}')
SH = {
    'A 지금 그대로(형식 강제 + effort low)': lambda u: call2(PICK_ASK_SYSTEM, u, ASK_SCHEMA),
    'G 형식 강제 없음(지시문으로 JSON)': lambda u: call2(noformat_system, u, None),
    'H effort 칸 없음': lambda u: call2(PICK_ASK_SYSTEM, u, ASK_SCHEMA, effort=False),
    'T 아주 짧은 프롬프트(바닥값)': lambda u: call2('짧게 답한다.', '안녕', None, cache=False),
    'U 아주 짧은 프롬프트 + 형식 강제': lambda u: call2('짧게 답한다.', '안녕', {'type': 'object', 'properties': {'head': {'type': 'string'}}, 'required': ['head'], 'additionalProperties': False}, cache=False),
}
res = {k: [] for k in SH}
for r in range(5):
    for q, w in QUESTIONS:
        u = user_message(q, rows[w])
        for k, f in SH.items():
            try:
                res[k].append(f(u))
            except Exception as e:
                print('fail', k, e, flush=True)
    print('round', r + 1, flush=True)
print('| 모양 | 첫 글자 중간 | 느린 10% |'); print('|---|---|---|')
for k, xs in res.items():
    xs = sorted(x for x in xs if x)
    print(f'| {k} | {statistics.median(xs):.2f}초 | {xs[int(0.9 * (len(xs) - 1))]:.2f}초 |')
json.dump(res, open(OUT.replace('ttft-probe', 'ttft-probe2'), 'w', encoding='utf-8'), indent=0)
