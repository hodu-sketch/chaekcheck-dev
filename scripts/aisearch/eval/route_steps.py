"""Routing learning curve: the route_v2 4-way classifier trained with growing amounts of routing training questions,
same 400 test questions. Sizes = routing questions per type (argv, e.g. 100 200 300 400 600). For each size the
questions are a fixed random sample of every t-<type>-*.jsonl batch (seed 0), so a bigger size always contains the
smaller one. Plus the 6,298 shelf-training questions every time. MLP is run with 3 seeds and averaged (LR once).
tau is picked on dev (every 10th sampled routing question) as in route_v2. Appends to route/steps.json."""
import glob, json, os, random, sys
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__)); SP = os.path.join(HERE, '..')
src = open(os.path.join(HERE, 'route_v2.py'), encoding='utf-8').read()
exec(src.split("\ngold = ")[0].replace("Vt = km.encode", "_unused = None and km.encode").replace("yt = [ROUTES", "_y0 = [ROUTES").replace("dev = np.array", "_d0 = np.array"))
cache_p = os.path.join(HERE, 'kure-route.npz')
cache = dict(np.load(cache_p, allow_pickle=True)['d'].item()) if os.path.exists(cache_p) else {}
new = [r['q'] for r in tr if r['q'] not in cache]
if new:
    for q, v in zip(new, km.encode(new, normalize_embeddings=True, batch_size=64).astype(np.float32)): cache[q] = v
    np.savez(cache_p, d=np.array(cache, dtype=object))
by = {}
for r in tr: by.setdefault(r['type'], []).append(r)
for t in by: random.Random(0).shuffle(by[t])
print('available per type', {t: len(v) for t, v in by.items()})
gold = ['book' if r['type'] == 'hard_book' else r['type'] for r in R]
out_p = os.path.join(SP, 'route', 'steps.json')
out = json.load(open(out_p)) if os.path.exists(out_p) else {}
def run(fit, sub, seed=None):
    import torch
    if seed is not None: torch.manual_seed(seed)
    X = np.array([cache[r['q']] for r in sub]); y = [ROUTES.index('book' if r['type'] == 'hard_book' else r['type']) for r in sub]
    dv = np.array([i % 10 == 0 for i in range(len(sub))])
    f, _ = fit(np.vstack([Vg, X[~dv]]), np.array(yg + [a for a, d in zip(y, dv) if not d]))
    Pd = f(X[dv]); gd = [ROUTES[a] for a, d in zip(y, dv) if d]; best = None
    for tau in np.arange(0.3, 0.96, 0.05):
        rd = route(Pd, tau)
        blocked = sum(1 for g, r in zip(gd, rd) if g != 'not_book' and r == 'not_book')
        acc = np.mean([g == r for g, r in zip(gd, rd)])
        if blocked <= 1 and (best is None or acc > best[1]): best = (tau, acc)
    tau = best[0] if best else 0.9
    f, _ = fit(np.vstack([Vg, X]), np.array(yg + y))
    rr = route(f(VR), tau)
    s = {t: float(np.mean([p == t for p, g in zip(rr, gold) if g == t])) for t in ROUTES}
    s['all'] = float(np.mean([p == g for p, g in zip(rr, gold)]))
    s['blocked_real'] = sum(1 for p, g in zip(rr, gold) if g != 'not_book' and p == 'not_book'); s['tau'] = float(tau)
    return s
for n in [int(a) for a in sys.argv[1:]]:
    if any(len(v) < n for v in by.values()): print('not enough for', n); continue
    sub = [r for i in range(n) for t in ('not_book', 'title_like', 'refers_shown', 'hard_book') for r in [by[t][i]]]
    res = {}
    runs = []
    for seed in (0, 1, 2):
        def mlp_s(X, y, seed=seed):
            import torch
            torch.manual_seed(seed)
            m = nn.Sequential(nn.Linear(1024, 512), nn.ReLU(), nn.Dropout(0.3), nn.Linear(512, 4))
            opt = torch.optim.AdamW(m.parameters(), lr=1e-3, weight_decay=1e-4); lossf = nn.CrossEntropyLoss()
            Xt, Yt = torch.tensor(X), torch.tensor(y)
            for _ in range(60):
                perm = torch.randperm(len(Xt))
                for i in range(0, len(Xt), 64):
                    b = perm[i:i + 64]; opt.zero_grad(); lossf(m(Xt[b]), Yt[b]).backward(); opt.step()
            m.eval(); return lambda A: torch.softmax(m(torch.tensor(A)), 1).detach().numpy(), m
        runs.append(run(mlp_s, sub))
    res['MLP'] = {k: float(np.mean([x[k] for x in runs])) for k in runs[0]}
    res['MLP']['all_runs'] = [x['all'] for x in runs]
    res['LR'] = run(lr, sub)
    out[str(n * 4)] = res
    json.dump(out, open(out_p, 'w'), indent=1)
    for k, s in res.items():
        print(f"{n*4:5} {k:4} all {s['all']*100:5.1f}  " + '  '.join(f"{t} {s[t]*100:5.1f}" for t in ROUTES) + f"  blocked {s['blocked_real']:.1f}  tau {s['tau']:.2f}", flush=True)
