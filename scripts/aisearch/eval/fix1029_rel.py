"""Route fix 2026-10-29: the relation network (rel_train.py) retrained with the same recipe (399 pairs a relation from
rel/pair-<rel>-1..4.jsonl, input [q2, q1, "q1 q2", q2*q1] KURE vectors, MLP 4096 -> 512 -> 7, AdamW 1e-3, 60 epochs,
seeds 0/1/2) plus FIX/rel-add.jsonl (asking about the shown books without a number -> ask_one; site and chat
follow-ups -> none; one-word topics -> new). `base` = no added pairs. Reports the 700 test pairs (fp32 vectors, as
rel_train.py) and saves the seed 0 model.
usage: fix1029_rel.py base|add OUT_PT   (env WORK = the training layout with clf/rel_train.py, clf/kure-rel.npz, rel/;
FIX; THREADS, default 4)"""
import json, os, sys
WORK = os.environ['WORK']; FIX = os.environ['FIX']; MODE, OUT_PT = sys.argv[1], sys.argv[2]
g = {'__file__': os.path.join(WORK, 'clf', 'rel_train.py'), '__name__': 'fix1029'}
src = open(os.path.join(WORK, 'clf', 'rel_train.py'), encoding='utf-8').read().split("\nout_p = ")[0]
exec(src, g)
import numpy as np, torch
torch.set_num_threads(int(os.environ.get('THREADS', '4')))
REL, train, cache, km, feat, mlp, XT, YT = (g[k] for k in ('REL', 'train', 'cache', 'km', 'feat', 'mlp', 'XT', 'YT'))
add = [json.loads(l) for l in open(os.path.join(FIX, 'rel-add.jsonl'), encoding='utf-8') if l.strip()] if MODE == 'add' else []
new = [t for x in add for t in g['texts'](x) if t not in cache]
if new:
    new = list(dict.fromkeys(new))
    for t, v in zip(new, km.encode(new, normalize_embeddings=True, batch_size=64).astype(np.float32)): cache[t] = v
n = 399
sub = [x for r in REL for x in train[r][:n]] + add
X = np.array([feat(x) for x in sub], dtype=np.float32); y = np.array([REL.index(x['rel']) for x in sub])
runs = []
for seed in (0, 1, 2):
    m = mlp(X, y, seed)
    with torch.no_grad(): pred = m(torch.tensor(XT)).argmax(1).numpy()
    runs.append({'all': float(np.mean(pred == YT)), **{r: float(np.mean(pred[YT == k] == k)) for k, r in enumerate(REL)}})
    if seed == 0: torch.save({'state_dict': m.state_dict(), 'rels': REL}, OUT_PT)
    print('seed', seed, 'all %.1f' % (100 * runs[-1]['all']), flush=True)
res = {k: float(np.mean([r[k] for r in runs])) for k in runs[0]}; res['all_runs'] = [r['all'] for r in runs]
res['n_train'] = len(sub); res['n_added'] = len(add)
json.dump(res, open(OUT_PT.replace('.pt', '.json'), 'w'), indent=1)
print(MODE, 'mean all %.1f ' % (100 * res['all']) + ' '.join('%s %.0f' % (r, 100 * res[r]) for r in REL))
