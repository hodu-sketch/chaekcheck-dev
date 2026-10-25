"""5-way routing (not_book / title_like / refers_shown / site / book): route_steps.py plus a 책첵-question class.
site = route/s-site-1..6 + the not_book training questions Sonnet relabelled as site (relabel-tr*.json);
extra book questions that look like site questions = s-site_hard-1..3. Test (never trained) = the 400 of all.json
(not_book relabelled by relabel-test.json) + s-site-7 (100 site) + s-site_hard-7 (100 book) = 600.
Sizes (argv) = questions per class; site_hard gets half. "blocked" = book questions sent to not_book or site (no search).
MLP x3 seeds averaged, LR once. Writes route/route5.json and route/route5-mlp.pt (largest size, seed 0)."""
import glob, json, os, random, sys
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__)); SP = os.path.join(HERE, '..'); RD = os.path.join(SP, 'route')
src = open(os.path.join(HERE, 'route_v2.py'), encoding='utf-8').read()
exec(src.split("\ngold = ")[0].replace("Vt = km.encode", "_unused = None and km.encode").replace("yt = [ROUTES", "_y0 = [ROUTES").replace("dev = np.array", "_d0 = np.array").replace("VR = km.encode", "_vr = None and km.encode"))
R5 = ['not_book', 'title_like', 'refers_shown', 'site', 'book']
yg5 = [R5.index(ROUTES[y]) for y in yg]
rel_tr = set(json.load(open(os.path.join(RD, 'relabel-tr1.json')))) | set(json.load(open(os.path.join(RD, 'relabel-tr2.json'))))
rel_te = set(json.load(open(os.path.join(RD, 'relabel-test.json'))))
def typ(r, rel):
    t = r['type']
    if t == 'not_book' and r['id'] in rel: return 'site'
    return {'hard_book': 'book', 'site_hard': 'book'}.get(t, t)
test = [dict(r, g=typ(r, rel_te)) for r in R] + [dict(r, g='site') for r in load(os.path.join(RD, 's-site-7.jsonl'))] + [dict(r, g='book', hard=1) for r in load(os.path.join(RD, 's-site_hard-7.jsonl'))]
testq = {r['q'] for r in test}
pool = {}
for p in sorted(glob.glob(os.path.join(RD, 't-*.jsonl'))):
    for r in load(p):
        if r['q'] not in testq: pool.setdefault(typ(r, rel_tr), []).append(r)
for k in range(1, 7):
    pool.setdefault('site', []).extend(r for r in load(os.path.join(RD, f's-site-{k}.jsonl')) if r['q'] not in testq)
hard = [r for k in range(1, 4) for r in load(os.path.join(RD, f's-site_hard-{k}.jsonl')) if r['q'] not in testq]
for t in pool: random.Random(0).shuffle(pool[t])
random.Random(0).shuffle(hard)
print('pool', {t: len(v) for t, v in pool.items()}, 'site_hard', len(hard), 'test', len(test))
cache_p = os.path.join(HERE, 'kure-route.npz')
cache = dict(np.load(cache_p, allow_pickle=True)['d'].item()) if os.path.exists(cache_p) else {}
allq = [r['q'] for v in pool.values() for r in v] + [r['q'] for r in hard] + [r['q'] for r in test]
new = [q for q in dict.fromkeys(allq) if q not in cache]
if new:
    for q, v in zip(new, km.encode(new, normalize_embeddings=True, batch_size=64).astype(np.float32)): cache[q] = v
    np.savez(cache_p, d=np.array(cache, dtype=object))
VT = np.array([cache[r['q']] for r in test]); gold = [r['g'] for r in test]
def route5(P, tau):
    out = []
    for p in P:
        k = int(np.argmax(p)); out.append(R5[k] if k == 4 or p[k] >= tau else 'book')
    return out
def mlp5(X, y, seed):
    torch.manual_seed(seed)
    m = nn.Sequential(nn.Linear(1024, 512), nn.ReLU(), nn.Dropout(0.3), nn.Linear(512, 5))
    opt = torch.optim.AdamW(m.parameters(), lr=1e-3, weight_decay=1e-4); lossf = nn.CrossEntropyLoss()
    Xt, Yt = torch.tensor(X), torch.tensor(y)
    for _ in range(60):
        perm = torch.randperm(len(Xt))
        for i in range(0, len(Xt), 64):
            b = perm[i:i + 64]; opt.zero_grad(); lossf(m(Xt[b]), Yt[b]).backward(); opt.step()
    m.eval(); return (lambda A: torch.softmax(m(torch.tensor(A)), 1).detach().numpy()), m
def lr5(X, y, seed):
    m = LogisticRegression(max_iter=3000, C=4).fit(X, y); return (lambda A: m.predict_proba(A)), m
def run(fit, sub, seed):
    X = np.array([cache[r['q']] for r in sub]); y = [R5.index(r['g']) for r in sub]
    dv = np.array([i % 10 == 0 for i in range(len(sub))])
    f, _ = fit(np.vstack([Vg, X[~dv]]), np.array(yg5 + [a for a, d in zip(y, dv) if not d]), seed)
    Pd = f(X[dv]); gd = [R5[a] for a, d in zip(y, dv) if d]; best = None
    for tau in np.arange(0.3, 0.96, 0.05):
        rd = route5(Pd, tau)
        blocked = sum(1 for g, r in zip(gd, rd) if g in ('book', 'title_like', 'refers_shown') and r in ('not_book', 'site'))
        acc = np.mean([g == r for g, r in zip(gd, rd)])
        if blocked <= 1 and (best is None or acc > best[1]): best = (tau, acc)
    tau = best[0] if best else 0.9
    f, model = fit(np.vstack([Vg, X]), np.array(yg5 + y), seed)
    rr = route5(f(VT), tau)
    s = {t: float(np.mean([p == t for p, g in zip(rr, gold) if g == t])) for t in R5}
    s['all'] = float(np.mean([p == g for p, g in zip(rr, gold)]))
    s['site_hard_ok'] = float(np.mean([p == 'book' for p, r in zip(rr, test) if r.get('hard')]))
    s['blocked_real'] = sum(1 for p, g in zip(rr, gold) if g in ('book', 'title_like', 'refers_shown') and p in ('not_book', 'site'))
    s['tau'] = float(tau)
    return s, model, rr
out_p = os.path.join(RD, 'route5.json'); out = json.load(open(out_p)) if os.path.exists(out_p) else {}
for n in [int(a) for a in sys.argv[1:]]:
    sub = []
    for i in range(n):
        for t in ('not_book', 'title_like', 'refers_shown', 'site', 'book'):
            if i < len(pool[t]): sub.append(dict(pool[t][i], g=t))
        if i % 2 == 0 and i // 2 < len(hard): sub.append(dict(hard[i // 2], g='book'))
    runs = []
    for seed in (0, 1, 2):
        s, model, rr = run(mlp5, sub, seed); runs.append(s)
        if seed == 0: keep = (model, s['tau'], rr)
    res = {'MLP': {k: float(np.mean([x[k] for x in runs])) for k in runs[0]}, 'LR': run(lr5, sub, 0)[0], 'n_train': len(sub)}
    res['MLP']['all_runs'] = [x['all'] for x in runs]
    out[str(n)] = res; json.dump(out, open(out_p, 'w'), indent=1)
    torch.save({'state_dict': keep[0].state_dict(), 'routes': R5, 'tau': keep[1]}, os.path.join(RD, 'route5-mlp.pt'))
    json.dump({r['id']: p for r, p in zip(test, keep[2])}, open(os.path.join(RD, 'pred5-MLP.json'), 'w'))
    for k in ('MLP', 'LR'):
        s = res[k]
        print(f"per class {n:4} (train {len(sub)}) {k:3} all {s['all']*100:5.1f}  " + '  '.join(f"{t} {s[t]*100:5.1f}" for t in R5) + f"  site_hard {s['site_hard_ok']*100:5.1f}  blocked {s['blocked_real']:.1f}  tau {s['tau']:.2f}", flush=True)
