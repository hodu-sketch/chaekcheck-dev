"""AI search first-text timing (docs/features/aisearch/reports/2026-10-27-latency.md): reads the rule test runs (rules_run.py, which
keeps the log line's step times) and compares the ways step by step.
  python timing_compare.py t-base t-fast      -> the table, and <out>/timing-<ways>.json"""
import glob, json, os, statistics, sys

OUT = os.environ.get('RULES_OUT', r'C:\Users\yujun\Documents\Claude\Projects\chaekcheck-dev\data\aisearch-eval-2026-10-25\rules')
ASK_WORDS = 4


def steps_of(way):
    rows = []
    for p in sorted(glob.glob(os.path.join(OUT, f'run-{way}.json')) + glob.glob(os.path.join(OUT, f'run-{way}-r2.json'))):
        for cid, v in json.load(open(p, encoding='utf-8')).items():
            for i, s in enumerate(v['steps']):
                s = dict(s, case=cid, follow=i > 0)
                rows.append(s)
    return rows


def kind(s):
    if s['follow']:
        return '이어지는 질문'
    return f"첫 질문 {ASK_WORDS}낱말 이하" if len(s['q'].split()) <= ASK_WORDS else f"첫 질문 {ASK_WORDS + 1}낱말 이상"


def med(xs):
    xs = sorted(x for x in xs if x is not None)
    return (round(statistics.median(xs)), round(xs[int(0.9 * (len(xs) - 1))]), len(xs)) if xs else (None, None, 0)


def span(s, a, b):
    t = s.get('steps') or {}
    return t[b] - t[a] if a in t and b in t else None


def summary(rows):
    first = [s for s in rows if s.get('steps', {}).get('llm-first') is not None]
    return {
        'requests': len(rows),
        'first text (client, ms)': med([s['firstText'] * 1000 if s['firstText'] is not None else None for s in rows]),
        'llm-first (server, ms)': med([s['steps'].get('llm-first') for s in first]),
        'cards (server, ms)': med([s['steps'].get('cards') for s in first]),
        'encode': med([s['steps'].get('encode') for s in first]),
        'ml step (encode..nearest)': med([s['steps'].get('nearest') for s in first]),
        'keyword (neighbours..keyword)': med([span(s, 'neighbours', 'keyword') for s in first]),
        'covers etc. (mix..cards)': med([span(s, 'mix', 'cards') for s in first]),
        'haiku first token (cards..llm-first)': med([span(s, 'cards', 'llm-first') for s in first]),
        # the early keyword search leaves no time between the neighbours and the keyword marks
        'keyword search already done': f"{sum(1 for s in first if span(s, 'neighbours', 'keyword') == 0)}/{len(first)}",
        'asked back': f"{sum(1 for s in rows if s['ask'])}/{len(rows)}",
    }


def main(ways):
    res = {}
    for way in ways:
        rows = steps_of(way)
        res[way] = {'all': summary(rows)}
        for k in sorted({kind(s) for s in rows}):
            res[way][k] = summary([s for s in rows if kind(s) == k])
    keys = list(res[ways[0]]['all'])
    for group in res[ways[0]]:
        print(f'\n## {group}\n| | ' + ' | '.join(ways) + ' |\n|---|' + '---|' * len(ways))
        for k in keys:
            cells = []
            for w in ways:
                v = res[w].get(group, {}).get(k)
                cells.append(f'{v[0]} / {v[1]} (n {v[2]})' if isinstance(v, tuple) else str(v))
            print(f'| {k} | ' + ' | '.join(cells) + ' |')
    json.dump(res, open(os.path.join(OUT, f"timing-{'-vs-'.join(ways)}.json"), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main(sys.argv[1:])
