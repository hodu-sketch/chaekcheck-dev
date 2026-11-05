"""AI search: words that point at the dialogue (plan decision 2026-10-29, A–E). Runs talk_refs_suite.json against a local
backend turn by turn (the scripted questions, also after an ask-back: history turns carry askQuestion/askChoices as the
web does), checks what can be checked by code, and prepares one judge item per turn for Sonnet (talk-refs-JUDGE_RULES.md).
  python talk_refs_run.py run <label> <backend log> [port]   -> <out>/run-<label>.json
  python talk_refs_run.py prod <label> <case id,...>          -> the same on production, those cases only (100 a day per IP)
  python talk_refs_run.py prep                                -> <out>/jd/in-NN.json (turns not judged yet)
  python talk_refs_run.py score                               -> table per group and per way"""
import glob, json, os, sys

import rules_run as r

HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
_PROJECTS = os.path.dirname(_REPO)  # the folder that holds this repo and chaekcheck-data
OUT = os.environ.get('TALK_OUT', os.path.join(_REPO, r'data\aisearch-eval-2026-10-25\talk-refs'))
SUITE = json.load(open(os.path.join(HERE, 'talk_refs_suite.json'), encoding='utf-8'))
FIXED_ASK = '어떤 책을 찾으세요? 이렇게 물어보셔도 돼요.'


def run(label, logpath, port, only=None):
    log = r.Log(logpath) if logpath else None
    os.makedirs(OUT, exist_ok=True)
    out = {}
    for case in SUITE['cases']:
        if only and case['id'] not in only:
            continue
        history, steps = [], []
        for t in case['turns']:
            s = r.ask(port, log, t['q'], history)
            steps.append({k: s[k] for k in ('q', 'firstText', 'result', 'head', 'ask', 'ms', 'steps')} |
                         {'cards': [{'bookId': c['bookId'], 'title': c['title'], 'author': c.get('author'), 'text': c['text']}
                                    for c in s['cards']]})
            history = history + [r.turn_of(t['q'], s)]
        out[case['id']] = steps
        print(label, case['id'], [('ask' if s['ask'] else s['result']) for s in steps], flush=True)
        json.dump(out, open(os.path.join(OUT, f'run-{label}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)


def auto(case, steps):
    """Checks by code, per turn: ask as expected, not the fixed ask, new books when asked for, no 「목록」."""
    res = []
    for i, (t, s) in enumerate(zip(case['turns'], steps)):
        asked = bool(s['ask'])
        prev = {c['bookId'] for c in steps[i - 1]['cards']} if i else set()
        shown = [c['bookId'] for c in s['cards']]
        text = (s['head'] or '') + ' ' + ' '.join(c['text'] for c in s['cards']) + ' ' + (s['ask']['question'] if s['ask'] else '')
        res.append({'ask_ok': None if t.get('ask') is None else asked == t['ask'],
                    'fixed_ask': bool(s['ask']) and s['ask']['question'].startswith(FIXED_ASK),
                    'new_ok': None if not t.get('new_books') or not shown else not (set(shown) & prev),
                    'list_word': '목록' in text, 'cards': len(shown), 'no_match': s['result'] == 'no_match'})
    return res


def runs():
    return {os.path.basename(p)[4:-5]: json.load(open(p, encoding='utf-8')) for p in sorted(glob.glob(os.path.join(OUT, 'run-*.json')))}


def judged():
    jp = os.path.join(OUT, 'judged.json')
    J = json.load(open(jp, encoding='utf-8')) if os.path.exists(jp) else {}
    for p in glob.glob(os.path.join(OUT, 'jd', 'out-*.json')):
        J.update(json.load(open(p, encoding='utf-8')))
    json.dump(J, open(jp, 'w', encoding='utf-8'), ensure_ascii=False)
    return J


def show(s):
    if s['ask']:
        return {'되물음': s['ask']['question'], '고를 답': s['ask']['choices']}
    return {'머리말': s['head'], '결과': s['result'],
            '카드': [{'제목': c['title'], '저자': c['author'], '글': c['text']} for c in s['cards']]}


def prep():
    J = judged()
    cases = {c['id']: c for c in SUITE['cases']}
    todo = []
    for label, run_ in runs().items():
        for cid, steps in run_.items():
            c = cases[cid]
            for i, (t, s) in enumerate(zip(c['turns'], steps)):
                key = f'{label}|{cid}|{i}'
                if key in J:
                    continue
                talk = [{'사용자': c['turns'][j]['q'], '답': show(steps[j])} for j in range(i)]
                todo.append({'key': key, 'talk': talk, 'question': t['q'], 'answer': show(s), 'want': t['want'],
                             'check': t.get('check', '')})
    os.makedirs(os.path.join(OUT, 'jd'), exist_ok=True)
    for p in glob.glob(os.path.join(OUT, 'jd', 'in-*.json')):
        os.remove(p)
    for n, i in enumerate(range(0, len(todo), 20), 1):
        json.dump(todo[i:i + 20], open(os.path.join(OUT, 'jd', f'in-{n:02d}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
    print('turns', len(todo), 'files', (len(todo) + 19) // 20)


def score():
    J = judged()
    cases = {c['id']: c for c in SUITE['cases']}
    groups = list(dict.fromkeys(c['group'] for c in SUITE['cases']))
    R = runs()
    ways = {}
    for label in R:
        ways.setdefault(label[:-3] if label.endswith('-r2') else label, []).append(label)
    print('| 묶음 | ' + ' | '.join(ways) + ' |\n|---|' + '---|' * len(ways))
    for g in groups + ['전체']:
        cells = []
        for way, labels in ways.items():
            ok = n = askok = askn = fixed = listw = good = bad = 0
            for label in labels:
                for cid, steps in R[label].items():
                    c = cases[cid]
                    if g != '전체' and c['group'] != g:
                        continue
                    for i, a in enumerate(auto(c, steps)):
                        if i == 0 and len(c['turns']) > 1 and c['turns'][0].get('check') is None:
                            pass
                        j = J.get(f'{label}|{cid}|{i}')
                        if j is not None and (i > 0 or cid == 'orig'):
                            n += 1; ok += j['pass']
                            good += sum(x >= 2 for x in j.get('cards', [])); bad += sum(x <= 1 for x in j.get('cards', []))
                        if a['ask_ok'] is not None:
                            askn += 1; askok += a['ask_ok']
                        fixed += a['fixed_ask']; listw += a['list_word']
            cells.append(f'통과 {ok}/{n} · 되묻기 기대대로 {askok}/{askn} · 고정 되묻기 {fixed} · 「목록」 {listw} · 맞는/틀린 카드 {good}/{bad}')
        print(f'| {g} | ' + ' | '.join(cells) + ' |')


if __name__ == '__main__':
    sys.path.insert(0, HERE)
    if sys.argv[1] == 'run':
        run(sys.argv[2], sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 8097)
    elif sys.argv[1] == 'prod':
        run(sys.argv[2], None, 'prod', set(sys.argv[3].split(',')))
    elif sys.argv[1] == 'prep':
        prep()
    else:
        score()
