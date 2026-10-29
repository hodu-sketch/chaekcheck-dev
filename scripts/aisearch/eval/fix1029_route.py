"""Route fix 2026-10-29: router B (route5b.py) retrained with the same recipe (600 per way + site_hard + 300 short,
the 6,298 shelf questions, MLP 1024 -> 512 -> 5, AdamW 1e-3, 60 epochs, seeds 0/1/2, tau on dev) plus FIX/route-add.jsonl
(site usage, greetings/thanks/feelings/other-service requests, one-word book topics, questions about the shown books).
`base` = no added questions (route5b.py again, to compare on the same machine and thread count).
usage: fix1029_route.py base|add OUT_PT   (env WORK = the training layout: clf/ with route5b.py, route_v2.py and the KURE
caches, route/, qgen/, search_books.tsv; FIX = the folder with route-add.jsonl (ADD = another file name there; RELABEL = a json list of
not_book training ids that become site); THREADS, default 4)"""
import json, os, sys
WORK = os.environ['WORK']; FIX = os.environ['FIX']; MODE, OUT_PT = sys.argv[1], sys.argv[2]
g = {'__file__': os.path.join(WORK, 'clf', 'route5b.py'), '__name__': 'fix1029'}
src = open(os.path.join(WORK, 'clf', 'route5b.py'), encoding='utf-8').read().split("\nout_p = ")[0]
sys.argv = [sys.argv[0]]
exec(src, g)
import numpy as np, torch
torch.set_num_threads(int(os.environ.get('THREADS', '4')))
pool, hard, short, cache, km, run, mlp5 = (g[k] for k in ('pool', 'hard', 'short', 'cache', 'km', 'run', 'mlp5'))
add = [json.loads(l) for l in open(os.path.join(FIX, os.environ.get('ADD', 'route-add.jsonl')), encoding='utf-8') if l.strip()] if MODE == 'add' else []
G = {'hard_book': 'book'}
testq = g['testq']
assert not [r['q'] for r in add if r['q'] in testq], 'added question in the test'
newq = [r['q'] for r in add if r['q'] not in cache]
if newq:
    for q, v in zip(newq, km.encode(newq, normalize_embeddings=True, batch_size=64).astype(np.float32)): cache[q] = v
n = 600
sub = []
for i in range(n):
    for t in ('not_book', 'title_like', 'refers_shown', 'site', 'book'):
        if i < len(pool[t]): sub.append(dict(pool[t][i], g=t))
    if i % 2 == 0 and i // 2 < len(hard): sub.append(dict(hard[i // 2], g='book'))
sub += [dict(r, g='book') for r in short]
# RELABEL = a json list of training ids (route/t-not_book-*) about the 책첵 account (login, password, leaving) that
# move from not_book to site (user 2026-10-29: login questions are site questions)
relabel = set(json.load(open(os.path.join(FIX, os.environ['RELABEL'])))) if os.environ.get('RELABEL') else set()
moved = 0
for r in sub:
    if r.get('id') in relabel: r['g'] = 'site'; moved += 1
print('relabelled to site', moved, 'of', len(relabel))
# the added questions spread through the list, so every tenth (dev, for tau) holds some of them
for j, r in enumerate(add):
    sub.insert(1 + j * (len(sub) // max(1, len(add))), dict(r, g=G.get(r['type'], r['type'])))
runs = []
for seed in (0, 1, 2):
    s, model, rr = run(mlp5, sub, seed); runs.append(s)
    if seed == 0: keep = (model, s['tau'], rr)
    # also at the fixed production tau (FIXTAU, default 0.55): the dev-picked tau swings 0.30..0.90 between seeds
    with torch.no_grad(): P = torch.softmax(model(torch.tensor(g['VT'])), 1).numpy()
    rf = g['route5'](P, float(os.environ.get('FIXTAU', '0.55')))
    s['old600_fixtau'] = float(np.mean([p == q for p, q, r in zip(rf, g['gold'], g['test']) if not r.get('short')]))
    torch.save({'state_dict': model.state_dict(), 'routes': g['R5'], 'tau': s['tau']}, OUT_PT.replace('.pt', f'-s{seed}.pt'))
    print('seed', seed, 'old600 %.1f (fixed tau %.1f) all700 %.1f blocked %d tau %.2f' % (100 * s['all_old600'], 100 * s['old600_fixtau'], 100 * s['all'], s['blocked_real'], s['tau']), flush=True)
res = {k: float(np.mean([x[k] for x in runs])) for k in runs[0]}
res['old600_runs'] = [x['all_old600'] for x in runs]; res['n_train'] = len(sub) + len(g['yg5']); res['n_added'] = len(add); res['n_relabelled'] = moved
torch.save({'state_dict': keep[0].state_dict(), 'routes': g['R5'], 'tau': keep[1]}, OUT_PT)
json.dump(res, open(OUT_PT.replace('.pt', '.json'), 'w'), indent=1)
print(MODE, 'mean old600 %.1f (fixed tau %.1f)  ' % (100 * res['all_old600'], 100 * res['old600_fixtau']) + '  '.join('%s %.1f' % (t, 100 * res[t]) for t in g['R5']) + '  short %.1f blocked %.1f' % (100 * res['short_ok'], res['blocked_real']))
