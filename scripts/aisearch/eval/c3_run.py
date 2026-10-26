"""3-turn test (user 2026-10-26 「앞 주제 둘 합치기」): 50 dialogues (combine 25, last_only 15, refers_books 10),
asked turn by turn against the local backend on 8095 with the history so far. usage: c3_run.py LABEL -> run-LABEL.json"""
import json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
FU2 = HERE
_src = open(os.path.join(FU2, 'fu2_run_dialogues.py'), encoding='utf-8').read()
exec(_src[:_src.index('D = dialogues()')])
D = [x for x in json.load(open(os.path.join(HERE, 'dialogues.json'), encoding='utf-8')) if len(sys.argv) < 3 or x['kind'] in sys.argv[2:]]
out = {}
for x in D:
    hist, turns = [], []
    for n, q in enumerate((x['q1'], x['q2'], x['q3']), 1):
        a = ask(q, hist, n); turns.append(a)
        hist.append({'question': q, 'bookIds': [c['bookId'] for c in a['cards']]})
    out[x['id']] = turns; print(sys.argv[1], x['id'], [len(t['cards']) for t in turns], flush=True)
json.dump(out, open(os.path.join(HERE, f'run-{sys.argv[1]}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
