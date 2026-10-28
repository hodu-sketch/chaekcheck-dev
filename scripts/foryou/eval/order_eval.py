"""Ordering variants of 책첵 추천's personal picks on the offline_eval.py tests, plus "how tiring" measures
(user 2026-10-25 "순서 고치기 하고"): mean pairwise cosine of the 20 picks, and the share of picks whose first author
is a base book's or another pick's. Variants the server can run need only the neighbour lists (no vectors there);
`vec_mmr` uses the vectors and is a reference only.

usage: python order_eval.py <scripts/crawl> <data/ml> [vector sets, default foryou-100k/vectors,pool-100000-intro]
(the 30k run of 2026-10-25 used foryou/vectors,pool-30000-intro)
"""
import json, math, random, sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

D = Path(__file__).resolve().parent
sys.path.insert(0, sys.argv[1])
from foryou_neighbours import first_author, same_work  # noqa: E402
import offline_eval as base  # noqa: E402  (reads the TSVs, builds the tests)

ML = Path(sys.argv[2])
vec_ids, vecs = [], []
for name in (sys.argv[3] if len(sys.argv) > 3 else 'foryou-100k/vectors,pool-100000-intro').split(','):
    got = json.load(open(ML / f'{name}.ids.json'))
    vec_ids += got['ids'] if isinstance(got, dict) else got
    vecs.append(np.load(ML / f'{name}.npy'))
V = np.concatenate(vecs)
row = {}
for i, b in enumerate(vec_ids):
    row.setdefault(int(b), i)

neigh, held, key_of, guessed = base.neigh, base.held, base.key_of, base.guessed
K = base.K
TOP_LINKED = 5  # a candidate among the first this many neighbours of a book already picked counts as "too close"


def author(b):
    return first_author(base.books.get(b, ('', '', 0))[1])


def candidates_rr(bases):
    """Round robin over the base books by neighbour rank (the 'sim' order)."""
    out = []
    for rank in range(20):
        for b in bases:
            ns = neigh.get(b, [])
            if rank < len(ns) and ns[rank] not in out:
                out.append(ns[rank])
    return out


def candidates_rrf(bases):
    """Reciprocal rank fusion: a book near several base books comes first."""
    score = defaultdict(float)
    for b in bases:
        for rank, n in enumerate(neigh.get(b, [])):
            score[n] += 1 / (10 + rank)
    return sorted(score, key=lambda n: (-score[n], n))


def keep(n, bases, out):
    if n in bases or held(n) == 0:
        return False
    return not any(same_work(key_of(n), key_of(b)) for b in bases) and not any(
        same_work(key_of(n), key_of(o)) for o in out)


def graph_mmr(order, bases):
    """Defer a candidate that is a close neighbour of a pick already made, or by an author already picked twice."""
    out, deferred = [], []
    authors = Counter(author(b) for b in bases)
    for n in order:
        if not keep(n, bases, out):
            continue
        linked = any(n in neigh.get(o, [])[:TOP_LINKED] for o in out)
        a = author(n)
        if linked or (a and authors[a] >= 1):
            deferred.append(n)
            continue
        out.append(n)
        authors[a] += 1
        if len(out) == K:
            return out
    for n in deferred:
        if keep(n, bases, out):
            out.append(n)
            if len(out) == K:
                break
    return out


def vec_mmr(order, bases, lam=0.7):
    cand = [n for n in order if n in row][:80]
    q = V[[row[b] for b in bases if b in row]]
    if not len(q) or not cand:
        return plain(order, bases)
    C = V[[row[n] for n in cand]]
    rel = (C @ q.T).max(axis=1)
    out_idx, out = [], []
    while len(out) < K:
        best, best_s = None, -9
        for i, n in enumerate(cand):
            if i in out_idx or not keep(n, bases, out):
                continue
            red = max((float(C[i] @ C[j]) for j in out_idx), default=0.0)
            s = lam * rel[i] - (1 - lam) * red
            if s > best_s:
                best, best_s = i, s
        if best is None:
            break
        out_idx.append(best)
        out.append(cand[best])
    return out


def plain(order, bases):
    out = []
    for n in order:
        if keep(n, bases, out):
            out.append(n)
            if len(out) == K:
                break
    return out


def calibrated(order, bases, wildcard=False):
    """Slots per field group in proportion to the base books' groups; leftovers filled in order."""
    want = Counter(guessed(b) for b in bases)
    total = sum(want.values())
    quota = {g: math.ceil(K * c / total) for g, c in want.items()}
    out, rest = [], []
    for n in order:
        if not keep(n, bases, out):
            continue
        g = guessed(n)
        if wildcard and g == 'other':
            out.append(n)
        elif quota.get(g, 0) > 0:
            out.append(n)
            quota[g] -= 1
        else:
            rest.append(n)
        if len(out) == K:
            return out
    for n in rest:
        if keep(n, bases, out):
            out.append(n)
            if len(out) == K:
                break
    return out


VARIANTS = {
    '지금 운영(보유 많은 순)': lambda b: base.recommend(b, 'held'),
    '유사도 순(돌아가며)': lambda b: plain(candidates_rr(b), b),
    '여러 기준 책에 가까운 순(RRF)': lambda b: plain(candidates_rrf(b), b),
    '유사도 순 + 겹침 줄이기': lambda b: graph_mmr(candidates_rr(b), b),
    '유사도 순 + 분야 비율': lambda b: calibrated(candidates_rr(b), b),
    '유사도 순 + 겹침 + 분야': lambda b: graph_mmr(calibrated(candidates_rr(b), b) + candidates_rr(b), b),
    '유사도 순 + 분야(모르는 분야는 통과)': lambda b: calibrated(candidates_rr(b), b, True),
    '유사도 순 + 겹침 + 분야(모름 통과)': lambda b: graph_mmr(calibrated(candidates_rr(b), b, True) + candidates_rr(b), b),
    'RRF + 겹침 줄이기': lambda b: graph_mmr(candidates_rrf(b), b),
    '(참고) 벡터 MMR': lambda b: vec_mmr(candidates_rr(b), b),
    '무작위': lambda b: base.recommend(b, 'random'),
}


def intra(recs):
    rs = [row[r] for r in recs if r in row]
    if len(rs) < 2:
        return float('nan')
    M = V[rs] @ V[rs].T
    n = len(rs)
    return float((M.sum() - n) / (n * (n - 1)))


def same_author_share(recs, bases):
    cnt = Counter(author(r) for r in recs if author(r))
    base_a = {author(b) for b in bases}
    return sum(1 for r in recs if author(r) and (cnt[author(r)] > 1 or author(r) in base_a)) / max(1, len(recs))


top10_cut = sorted((held(b) for b in neigh), reverse=True)[len(neigh) // 10]
results = {}
for name, fn in VARIANTS.items():
    hit, prec, nd = [], [], []
    for _k, bases, rel in base.scenarios:
        recs = fn(bases)
        found = sum(1 for r in recs if r in rel)
        hit.append(found > 0)
        prec.append(found / K)
        nd.append(base.ndcg(recs, rel))
    ent, il, sa, pop, seen, lst = [], [], [], [], set(), []
    for bases in base.users:
        recs = fn(bases)
        ent.append(base.entropy(recs))
        v = intra(recs)
        if not math.isnan(v):
            il.append(v)
        sa.append(same_author_share(recs, bases))
        pop.append(sum(1 for r in recs if held(r) >= top10_cut) / max(1, len(recs)))
        seen.update(recs)
        lst.append(sum(1 for r in recs if r in base.listed) / max(1, len(recs)))
    results[name] = {
        'listed_share': sum(lst) / len(lst),
        'hit@20': sum(hit) / len(hit), 'precision@20': sum(prec) / len(prec), 'ndcg@20': sum(nd) / len(nd),
        'field_entropy': sum(ent) / len(ent), 'intra_sim': sum(il) / len(il), 'same_author': sum(sa) / len(sa),
        'top10_held': sum(pop) / len(pop), 'coverage': len(seen),
    }
    print(name, json.dumps({k: round(v, 4) for k, v in results[name].items()}, ensure_ascii=False), flush=True)
(D / 'order-metrics.json').write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding='utf-8')
