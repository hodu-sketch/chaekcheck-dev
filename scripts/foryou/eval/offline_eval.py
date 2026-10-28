"""Offline scores of 책첵 추천's personal picks (cycle 23, user 2026-10-25 "1단계 오프라인 지표로 지금 추천의 기준 점수").

Reads the TSVs exported from the local DB next to this file. Simulates ForYouController.similarBooks with the plan =
every library and no build scores (production: CHAEKCHECK_FORYOU_BUILD_ENABLED=false), so the neighbours of the base
books are ordered by how many libraries hold them. Variant "sim" orders them by neighbour rank instead (round robin
over the base books), for comparison. Writes metrics.json and judge-pairs.tsv (pairs for the Haiku judge).
"""
import json, math, random, sys
from collections import Counter, defaultdict
from pathlib import Path

D = Path(__file__).resolve().parent
sys.path.insert(0, str(Path(sys.argv[1])))  # scripts/crawl, for work_key / same_work
from foryou_neighbours import same_work  # noqa: E402

FIELD = {
    'kaist:literature': 'literature', 'kaist:humanities': 'humanities', 'kaist:society': 'society',
    'kaist:science': 'science', 'kaist:technology': 'science', 'kaist:arts': 'arts',
    'leedongjin:korean_fiction': 'literature', 'leedongjin:foreign_fiction': 'literature',
    'leedongjin:graphic_novel': 'literature', 'leedongjin:essay': 'literature',
    'leedongjin:humanities': 'humanities', 'leedongjin:history': 'humanities', 'leedongjin:society': 'society',
    'leedongjin:economy': 'society', 'leedongjin:science': 'science',
    'snu100:korean_lit': 'literature', 'snu100:foreign_lit': 'literature', 'snu100:eastern_thought': 'humanities',
    'snu100:western_thought': 'humanities', 'snu100:science_tech': 'science',
}
GROUPS = ['literature', 'humanities', 'society', 'science', 'arts', 'other']
K = 20
rng = random.Random(23)


def rows(name):
    with open(D / name, encoding='utf-8') as f:
        return [line.rstrip('\n').split('\t') for line in f if line.strip()]


books = {int(r[0]): (r[1], r[2], int(r[3])) for r in rows('books.tsv')}
neigh = {int(r[0]): [int(x) for x in r[1].split(',') if x] for r in rows('neighbours.tsv')}
listed_rows = rows('listed.tsv')
field_of, lists_of, sets = {}, defaultdict(set), defaultdict(set)
for book_id, key, field, year, _month in listed_rows:
    b = int(book_id)
    lists_of[b].add(key)
    g = 'literature' if key == 'nobel' else FIELD.get(f'{key}:{field}')
    if g and b not in field_of:
        field_of[b] = g
    # curated sets for the hit test: one list's one field (KAIST also by year)
    sets[f'{key}:{field}' + (f':{year}' if key == 'kaist' and year else '')].add(b)
listed = set(lists_of)


def guessed(b):
    if b in field_of:
        return field_of[b]
    votes = Counter(field_of[n] for n in neigh.get(b, []) if n in field_of)
    if not votes:
        return 'other'
    (top, n), *rest = votes.most_common()
    return top if n >= 2 and (not rest or rest[0][1] < n) else 'other'


def held(b):
    return books.get(b, ('', '', 0))[2]


def key_of(b):
    t, a, _ = books.get(b, ('', '', 0))
    return (b, t, a)


def recommend(base, variant):
    """The top K personal picks for these base books."""
    if variant == 'held':
        cand = []
        for b in base:
            for n in neigh.get(b, []):
                if n not in cand:
                    cand.append(n)
        order = sorted(cand, key=lambda n: (-held(n), n))
    elif variant == 'sim':
        order = []
        for rank in range(20):
            for b in base:
                ns = neigh.get(b, [])
                if rank < len(ns) and ns[rank] not in order:
                    order.append(ns[rank])
    else:  # random books of the pool
        order = rng.sample(sorted(neigh), 200)
    out = []
    for n in order:
        if n in base or held(n) == 0:
            continue
        if any(same_work(key_of(n), key_of(b)) for b in base) or any(same_work(key_of(n), key_of(o)) for o in out):
            continue
        out.append(n)
        if len(out) == K:
            break
    return out


def ndcg(recs, rel):
    dcg = sum(1 / math.log2(i + 2) for i, r in enumerate(recs) if r in rel)
    ideal = sum(1 / math.log2(i + 2) for i in range(min(len(rel), K)))
    return dcg / ideal if ideal else 0.0


def entropy(recs):
    c = Counter(guessed(r) for r in recs)
    n = sum(c.values())
    h = -sum(v / n * math.log(v / n) for v in c.values())
    return h / math.log(len(GROUPS))


# hit test: 3 books of a curated set are seen, the rest of the set should come up
hit_sets = {k: v & set(neigh) for k, v in sets.items()}
hit_sets = {k: v for k, v in hit_sets.items() if 6 <= len(v) <= 120}
scenarios = []
for k, v in sorted(hit_sets.items()):
    for _ in range(10):
        base = rng.sample(sorted(v), 3)
        scenarios.append((k, base, v - set(base)))

# mixed users: 3 to 6 listed books from different fields, as the screen picks them
by_group = defaultdict(list)
for b in listed & set(neigh):
    by_group[field_of.get(b, 'other')].append(b)
users = []
for _ in range(1000):
    n = rng.randint(3, 6)
    gs = rng.sample([g for g in GROUPS if by_group[g]], min(n, len([g for g in GROUPS if by_group[g]])))
    users.append([rng.choice(by_group[g]) for g in gs][:n])

pool = sorted(neigh)
held_sorted = sorted((held(b) for b in pool), reverse=True)
top10_cut = held_sorted[len(held_sorted) // 10]
metrics = {}
for variant in ('held', 'sim', 'random'):
    p, hit, nd = [], [], []
    for _k, base, rel in scenarios:
        recs = recommend(base, variant)
        found = sum(1 for r in recs if r in rel)
        p.append(found / K)
        hit.append(1 if found else 0)
        nd.append(ndcg(recs, rel))
    ent, nov, pop, seen = [], [], [], set()
    for base in users:
        recs = recommend(base, variant)
        ent.append(entropy(recs))
        nov.append(sum(held(r) for r in recs) / max(1, len(recs)))
        pop.append(sum(1 for r in recs if held(r) >= top10_cut) / max(1, len(recs)))
        seen.update(recs)
    metrics[variant] = {
        'precision@20': sum(p) / len(p), 'hit@20': sum(hit) / len(hit), 'ndcg@20': sum(nd) / len(nd),
        'field_entropy': sum(ent) / len(ent), 'mean_held': sum(nov) / len(nov),
        'share_top10pct_held': sum(pop) / len(pop), 'coverage_books': len(seen),
        'coverage_share': len(seen) / len(pool),
    }

# neighbour lists themselves
agree = total = leak = pairs = 0
for b in listed & set(neigh):
    for n in neigh[b]:
        pairs += 1
        if same_work(key_of(b), key_of(n)):
            leak += 1
        if n in field_of and b in field_of:
            total += 1
            agree += field_of[n] == field_of[b]
same_author = sum(1 for b in neigh for n in neigh[b][:5]
                  if books.get(b) and books.get(n) and books[b][1] and books[b][1] == books[n][1])
ids = lambda name: {int(r[0]) for r in rows(name)}
blurbs, covers, tags = ids('blurb_ids.tsv'), ids('cover_ids.tsv'), ids('tag_ids.tsv')
off = set(neigh) - listed
metrics['lists'] = {
    'field_agreement': agree / total, 'field_pairs': total, 'edition_leak': leak / pairs,
    'same_author_top5': same_author / (5 * len(neigh)),
    'guessed_field_share_off_list': sum(1 for b in off if guessed(b) != 'other') / len(off),
    'fill_blurb': len(blurbs & set(neigh)) / len(neigh), 'fill_cover': len(covers & set(neigh)) / len(neigh),
    'fill_tags': len(tags & set(neigh)) / len(neigh), 'fill_neighbours': 1.0,
    'pool': len(pool), 'listed_in_pool': len(listed & set(neigh)), 'hit_sets': len(hit_sets),
    'scenarios': len(scenarios), 'users': len(users), 'random_baseline_precision': None,
}
(D / 'metrics.json').write_text(json.dumps(metrics, ensure_ascii=False, indent=1), encoding='utf-8')

# pairs for the Haiku judge: 60 mixed users, each base book with the first 2 picks that came from its neighbours
jrng = random.Random(7)
with open(D / 'judge-pairs.tsv', 'w', encoding='utf-8', newline='\n') as f:
    pid = 0
    for variant in ('held', 'sim', 'random'):
        for base in jrng.sample(users, 50):
            recs = recommend(base, variant)
            for r in recs[:4]:
                src = next((b for b in base if r in neigh.get(b, [])), base[0])
                pid += 1
                f.write(f'{pid}\t{variant}\t{src}\t{r}\n')
print(json.dumps(metrics, ensure_ascii=False, indent=1))
