"""The user's dialogue 2026-10-26: ML/LLM basics with principles, then 「수학적인 것도 좀 있었으면 해」. Prints the second
turn's cards and their texts, REPEATS times. usage: math_check.py LABEL REPEATS -> math-LABEL.json"""
import json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
_src = open(os.path.join(HERE, 'fu2_run_dialogues.py'), encoding='utf-8').read(); exec(_src[:_src.index('D = dialogues()')])
Q = ['머신러닝, LLM에 대한 기초적인 지식. 원리에 대해서 설명이 가능하고 입문서이지만 전문적이었으면 해', '수학적인 것도 좀 있었으면 해']
out = []
for r in range(int(sys.argv[2])):
    hist, turns = [], []
    for n, q in enumerate(Q, 1):
        a = ask(q, hist, n); turns.append(a); hist.append({'question': q, 'bookIds': [c['bookId'] for c in a['cards']]})
    out.append(turns)
    print('==', r + 1, turns[1]['head'])
    for c in turns[1]['cards']: print('  -', c['title'], '|', c['text'])
json.dump(out, open(os.path.join(HERE, f'math-{sys.argv[1]}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
