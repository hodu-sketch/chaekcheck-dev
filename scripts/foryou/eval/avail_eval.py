"""책첵 추천: available-now first and my-library count mixed into the closeness order (user 2026-10-29 "빌리려고
하는데 다 대출 중인 사태를 막기 위함 … 보완하고 싶은데"). Plans: each user gets a random sampled region and its joinable
libraries (scope joinable), or every library (scope all, the default). Available now = a plan library holds the book
through 교보 구독형 (no loan limit)."""
import json, math, random, sys
from collections import defaultdict
from pathlib import Path
D = Path(__file__).resolve().parent; A = D.parent / 'avail'
sys.path.insert(0, sys.argv[1])
import offline_eval as base
neigh, key_of = base.neigh, base.key_of
from foryou_neighbours import same_work
libs = {int(r.split('\t')[0]): set(map(int, r.rstrip('\n').split('\t')[1].split(','))) if r.rstrip('\n').split('\t')[1] else set()
        for r in open(A / 'book-libs.tsv', encoding='utf-8')}
sub = {int(r.split('\t')[0]): set(map(int, r.rstrip('\n').split('\t')[1].split(','))) for r in open(A / 'book-sub.tsv', encoding='utf-8')}
regions = json.load(open(A / 'region-libs.json'))
joinable = {k: {x['id'] for x in v if x['e'] in ('ELIGIBLE', 'OPEN')} for k, v in regions.items()}
ALL = set().union(*[{x['id'] for x in v} for v in regions.values()])
rng = random.Random(31)

def rr(bases):
    out = []
    for rank in range(20):
        for b in bases:
            ns = neigh.get(b, [])
            if rank < len(ns) and ns[rank] not in out:
                out.append(ns[rank])
    return out

def eligible(order, bases, plan):
    out = []
    for n in order:
        pc = len(libs.get(n, set()) & plan)
        if n in bases or pc == 0:
            continue
        if any(same_work(key_of(n), key_of(b)) for b in bases) or any(same_work(key_of(n), key_of(o)) for o, *_ in out):
            continue
        out.append((n, pc, bool(sub.get(n, set()) & plan)))
    return out

def variant(name, rows):
    if name == '유사도 순(지금)':
        return rows
    if name == '보유 많은 순(예전)':
        return sorted(rows, key=lambda r: -r[1])
    if name.startswith('바로 대출 먼저, 5권 묶음'):
        w = 5
    elif name.startswith('바로 대출 먼저, 10권 묶음'):
        w = 10
    else:
        w = None
    if w:
        out = []
        for i in range(0, len(rows), w):
            out += sorted(rows[i:i + w], key=lambda r: (not r[2], -r[1]))
        return out
    lam, mu = {'섞기 약하게(λ2, μ1)': (2, 1), '섞기 중간(λ4, μ2)': (4, 2), '섞기 강하게(λ8, μ3)': (8, 3)}[name]
    score = {r[0]: -i + lam * r[2] + mu * math.log1p(r[1]) for i, r in enumerate(rows)}
    return sorted(rows, key=lambda r: -score[r[0]])

NAMES = ['유사도 순(지금)', '바로 대출 먼저, 5권 묶음', '바로 대출 먼저, 10권 묶음', '섞기 약하게(λ2, μ1)', '섞기 중간(λ4, μ2)',
         '섞기 강하게(λ8, μ3)', '보유 많은 순(예전)']
user_region = [rng.choice(list(joinable)) for _ in base.users]
res = {}
for scope in ('joinable', 'all'):
    for name in NAMES:
        inst = pc = one = pos = 0; n = 0; hit = []
        for i, bases in enumerate(base.users):
            plan = joinable[user_region[i]] if scope == 'joinable' else ALL
            rows = eligible(rr(bases), bases, plan)
            index = {r[0]: k for k, r in enumerate(rows)}
            top = variant(name, rows)[:20]
            for r in top:
                inst += r[2]; pc += r[1]; one += r[1] == 1; pos += index[r[0]]; n += 1
        for j, (_k, bases, rel) in enumerate(base.scenarios):
            plan = joinable[user_region[j % len(user_region)]] if scope == 'joinable' else ALL
            top = variant(name, eligible(rr(bases), bases, plan))[:20]
            hit.append(any(r[0] in rel for r in top))
        res[f'{scope}|{name}'] = {'바로 대출 비율': round(inst / n, 3), '평균 내 도서관 수': round(pc / n, 1),
                                  '내 도서관 1곳뿐': round(one / n, 3), '평균 원래 자리(낮을수록 비슷함)': round(pos / n, 1),
                                  'Hit@20': round(sum(hit) / len(hit), 3)}
        print(scope, name, res[f'{scope}|{name}'], flush=True)
json.dump(res, open(D / 'avail-metrics.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
