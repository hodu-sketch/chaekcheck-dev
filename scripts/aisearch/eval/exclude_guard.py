"""B: the left-out shelves (ex_field, ex_subfield, ex_genre) only when the question has a leave-out word. F1 on the 600
test shelf questions with production's int8 vectors and the exported model, without and with the guard; also how many
questions that ask to leave nothing out get a left-out shelf (the 판타지인데 case)."""
import json, os, re, sys
import numpy as np
sys.argv = [sys.argv[0]] + ["판타지인데"]
src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'probe_plan.py'), encoding='utf-8').read().split('for q in sys.argv')[0]
exec(src)
SP = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
def load(p): return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]
test = [r for k in range(25, 31) for r in load(os.path.join(SP, 'qgen', f'q-{k:02d}.jsonl'))]
CUE = re.compile(r"말고|빼고|빼줘|빼 줘|제외|없는|없이|싫|아닌|아니고|말구|빼면|안 들어간|아니었으면")
def f1(pred, gold):
    tp = sum(len(set(p) & set(g)) for p, g in zip(pred, gold)); fp = sum(len(set(p) - set(g)) for p, g in zip(pred, gold))
    fn = sum(len(set(g) - set(p)) for p, g in zip(pred, gold)); return 2 * tp / max(2 * tp + fp + fn, 1), fp
V = [vec(r['q']) for r in test]
for k in ('ex_field', 'ex_subfield', 'ex_genre'):
    h = W[k][0]
    pred = [[h['labels'][i] for i, x in enumerate(out(k, v)) if x >= h['threshold']][:2] for v in V]
    guarded = [p if CUE.search(r['q']) else [] for p, r in zip(pred, test)]
    gold = [(r.get('exclude') or {}).get(k[3:]) or [] for r in test]
    a, fa = f1(pred, gold); b, fb = f1(guarded, gold)
    print(k, 'F1 %.3f -> %.3f, wrong left-out shelves %d -> %d' % (a, b, fa, fb))
noask = [i for i, r in enumerate(test) if not any((r.get('exclude') or {}).values())]
print('questions asking nothing out:', len(noask), 'with a left-out shelf before:',
      sum(1 for i in noask if any([x >= W[k][0]['threshold'] for x in out(k, V[i])].count(True) for k in ('ex_field', 'ex_subfield', 'ex_genre'))))
print('cue coverage: questions that ask something out and have a cue word:',
      sum(1 for r in test if any((r.get('exclude') or {}).values()) and CUE.search(r['q'])), '/', sum(1 for r in test if any((r.get('exclude') or {}).values())))
