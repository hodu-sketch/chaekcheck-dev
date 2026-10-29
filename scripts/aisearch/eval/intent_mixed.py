"""Mixed ways after intent_compare.py's first run (user 2026-10-29): each part does what it is good at, on the same held-out
test sentences. 최신: the rule finds the word, the vector says whether it means 「newly published」. 다 같이: the vector says
「all」 unless a turning-down word is there. 아까: order words (처음, 두번째, 끝) by rule, named topics by the vector, one
part of the reply at a time. A나 B: joining words and list words (둘다, 섞어, 아무거나, 중에, 아님, 든) by rule, typos left
to the vector search of each part.
  venv python intent_mixed.py"""
import json, os, re
import numpy as np

import intent_compare as c

S = c.S
for p in S['recency']['proto']:
    p['_text'] = p['q']
for p in S['all_choices']['proto']:
    p['_text'] = p['answer']
# 최신
rows = []
for t in S['recency']['test']:
    rule = c.rule_recent(t['q']) is not None
    since = bool(c.SINCE.search(t['q']))
    vec = c.knn(t['q'], S['recency']['proto'], lambda p: p['label']) == 'recent'
    rows.append((t['label'] == 'recent', since or (rule and vec)))
print('최신 섞어(규칙이 찾고 벡터가 뜻 확인): 맞음', sum(g == p for g, p in rows), '/', len(rows),
      '놓침', sum(g and not p for g, p in rows), '잘못 걸림', sum(p and not g for g, p in rows))

# 다 같이
rows = []
for t in S['all_choices']['test']:
    v = c.knn(t['answer'], S['all_choices']['proto'], lambda p: p['label'])
    pred = (v == 'all' or c.rule_all(t['answer'])) and not c.NOT_ALL.search(t['answer']) and v not in ('reject', 'other')
    rows.append((t['label'] == 'all', pred, t['answer']))
print('다 같이 섞어(벡터 + 거절 말 막기): 맞음', sum(g == p for g, p, _ in rows), '/', len(rows),
      '놓침', [a for g, p, a in rows if g and not p], '잘못 걸림', [a for g, p, a in rows if p and not g])

# 아까
ORDER = [(re.compile(r'(맨\s*)?(처음|첫\s*번째|첫번째|첫\s*거|1번)'), 0), (re.compile(r'두\s*번째|둘째|2번'), 1),
         (re.compile(r'세\s*번째|셋째|3번'), 2), (re.compile(r'(맨\s*)?(끝|마지막)'), -1)]
GROUP = [(re.compile(r'앞에?\s*(둘|두\s*개)|처음\s*(둘|두\s*개)'), [0, 1]), (re.compile(r'전부|다\s*같이|다\s*합|셋\s*다|모두'), 'all')]


def back(q, topics):
    for p, idx in GROUP:
        if p.search(q):
            return list(range(len(topics))) if idx == 'all' else idx
    picked = set()
    for p, i in ORDER:
        if p.search(q):
            picked.add(i % len(topics))
    rest = re.sub('|'.join(p.pattern for p, _ in ORDER), ' ', q)
    parts = c.rule_parts(rest) or [rest]
    for part in parts:
        if not c.words(part):
            continue
        sims = [float(c.vec(part) @ c.vec(t)) for t in topics]
        if max(sims) >= 0.3:
            picked.add(int(np.argmax(sims)))
    if not picked:
        return c.vec_back(q, topics, 0.04, 0.2)
    return sorted(picked)


ok = [sorted(back(t['q'], t['topics'])) == sorted(t['expected']) for t in S['back_to']['test']]
print('아까 섞어(순서 말은 규칙, 이름은 조각마다 벡터): 맞음', sum(ok), '/', len(ok))
for t, k in zip(S['back_to']['test'], ok):
    if not k:
        print('   틀림', t['q'], t['expected'], '->', back(t['q'], t['topics']))

# A나 B
LIST = re.compile(r'둘\s*다|셋\s*다|섞어|아무거나|중에|중에서|다\s*(보여|줘|해)?$|이든|든$|아님|아니면')
JUNK = {'아무거나', '아무거', '암거', '하나', '하나만', '둘다', '셋다', '다', '걍', '쪽', '중에', '섞어서', '섞어', 'ㅇㅇ', '같이',
        '보여줘', '추천', '추천해줘', '이든', '아님', '아니면', '또는', '좀', '책', '만화책'}


def parts2(q):
    p = c.rule_parts(q)
    if p and not any(w in JUNK for w in p):
        return p
    if not LIST.search(q):
        return []
    ws = []
    for w in re.split(r'\s+', q.strip()):
        w = re.sub(r'(이든|든|이나|나|랑|이랑|하고|아님|,)$', '', w)
        if w and w not in JUNK and not LIST.fullmatch(w) and c.words(w) and not re.fullmatch(r'[ㄱ-ㅎ]+', w):
            ws.append(w)
    return ws if len(ws) >= 2 else []


ok = []
for t in S['ab_split']['test']:
    k = c.parts_ok(parts2(t['q']), t['parts'])
    ok.append((k, bool(t['parts'])))
    if not k:
        print('   A나 B 틀림', t['q'], t['parts'], '->', parts2(t['q']))
print('A나 B 섞어(조사 + 목록 말): 맞음', sum(k for k, _ in ok), '/', len(ok), '(여러 주제', sum(k for k, m in ok if m), '/',
      sum(m for _, m in ok), ', 한 주제', sum(k for k, m in ok if not m), '/', sum(not m for _, m in ok), ')')
