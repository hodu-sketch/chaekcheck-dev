"""Thinking on/off on the 57 eval questions by question type (user 2026-10-26 「생각을 끄면 부정어/조건 등이 안 될까봐」):
the runs f-on, f-on-r2, f-off, f-off-r2 in agent57/, judged by agent57-JUDGE_RULES.md. Per way and type: right %,
right and wrong cards a question, no-card questions, first text and output tokens (both runs together)."""
import json, os, statistics

_REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
_PROJECTS = os.path.dirname(_REPO)  # the folder that holds this repo and chaekcheck-data
OUT = os.path.join(_REPO, r'data\aisearch-eval-2026-10-25\agent57')
Q = {'k%02d' % x['id']: x for x in json.load(open(os.path.join(os.path.dirname(OUT), 'eval_questions.json'), encoding='utf-8'))}
J = json.load(open(os.path.join(OUT, 'judged.json'), encoding='utf-8'))
WAYS = {'생각 켬': ['f-on', 'f-on-r2'], '생각 끔+캐시': ['f-off', 'f-off-r2']}
TYPES = ['부정어', '조건', '두주제', '비슷한책', '뜻맞추기', '기존', '전체']


def rows(labels, kind):
    out = []
    for label in labels:
        run = json.load(open(os.path.join(OUT, f'run-{label}.json'), encoding='utf-8'))
        for k, item in run.items():
            if kind != '전체' and Q[k]['type'] != kind:
                continue
            last = item['turns'][-1][-1]
            out.append((k, [J[f"{k}|{c['bookId']}"] for c in last['cards'] if f"{k}|{c['bookId']}" in J], last))
    return out


print('| 종류 | 질문 수 | ' + ' | '.join(f'{w} 맞음 / 맞는 카드 / 틀린 카드 / 0장' for w in WAYS) + ' |')
print('|---|---|' + '---|' * len(WAYS))
for kind in TYPES:
    cells, n = [], 0
    for way, labels in WAYS.items():
        r = rows(labels, kind); n = len(r) // len(labels)
        g = [x for _, s, _ in r for x in s]
        cells.append('%.1f%% / %.1f / %.2f / %.1f' % (100 * sum(x >= 2 for x in g) / max(len(g), 1),
                                                   sum(sum(x >= 2 for x in s) for _, s, _ in r) / len(r),
                                                   sum(sum(x <= 1 for x in s) for _, s, _ in r) / len(r),
                                                   sum(1 for _, _, last in r if not last['cards']) / len(labels)))
    print(f'| {kind} | {n} | ' + ' | '.join(cells) + ' |')
for way, labels in WAYS.items():
    r = rows(labels, '전체')
    ft = [last['firstText'] for _, _, last in r if last.get('firstText') is not None]
    print(way, '첫 글 중간 %.1fs, 느린 10%% %.1fs, 전체 중간 %.1fs, 출력 토큰 중간 %d' % (
        statistics.median(ft), sorted(ft)[int(0.9 * (len(ft) - 1))], statistics.median(last['ms'] for _, _, last in r) / 1000,
        statistics.median(last['out'] for _, _, last in r)))
# the negation questions one by one: what each way showed
for k in [k for k in Q if Q[k]['type'] in ('부정어', '조건')]:
    line = [Q[k]['q']]
    for way, labels in WAYS.items():
        for label in labels:
            run = json.load(open(os.path.join(OUT, f'run-{label}.json'), encoding='utf-8'))
            s = [J.get(f"{k}|{c['bookId']}") for c in run[k]['turns'][-1][-1]['cards']]
            line.append(f"{label}:{sum(x is not None and x >= 2 for x in s)}/{len(s)}")
    print(' · '.join(line))
