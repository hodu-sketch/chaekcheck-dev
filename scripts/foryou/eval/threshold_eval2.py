"""Re-measure (2026-10-29) with the service's real plan rules: a plan holds only plannable libraries (enabled, join_survey
SURVEYED or NOT_FOUND — MEMBERS_ONLY and NOT_SURVEYED only when added by hand, LibraryRepository.findPlannable);
scope joinable keeps those with eligibility OPEN or ELIGIBLE for the user's region. Available now, two readings:
P = a plan library with platform_type KYOBO_SUBSCRIPTION_V1 holds it; L = a plan library with lending_model
SUBSCRIPTION or RENTAL holds it."""
import json, sys
from pathlib import Path
import avail_eval as ae
D = Path(__file__).resolve().parent; A = D.parent / 'avail'
libs = {}
for l in open(A / 'libs.tsv', encoding='utf-8'):
    i, plat, lend, js = l.rstrip('\n').split('\t'); libs[int(i)] = (plat, lend, js)
plannable = {i for i, (p, l, js) in libs.items() if js in ('SURVEYED', 'NOT_FOUND')}
subP = {i for i in plannable if libs[i][0] == 'KYOBO_SUBSCRIPTION_V1'}
subL = {i for i in plannable if libs[i][1] in ('SUBSCRIPTION', 'RENTAL')}
join = {k: v & plannable for k, v in ae.joinable.items()}
print('plannable', len(plannable), 'P', len(subP), 'L', len(subL), 'joinable per region', sorted(len(v) for v in join.values()))
res = {}
def rows_for(bases, plan, inst):
    out = []
    for n, pc, _ in ae.eligible(ae.rr(bases), bases, plan):
        out.append((n, pc, bool(ae.libs.get(n, set()) & plan & inst)))
    return out
for scope in ('joinable', 'all'):
    for reading, inst in (('P', subP), ('L', subL)):
        for T in (1, 2, 3, 5):
            ins = pc = one = pos = n = short = 0; hit = []
            for i, bases in enumerate(ae.base.users):
                plan = join[ae.user_region[i]] if scope == 'joinable' else plannable
                rows = rows_for(bases, plan, inst); index = {r[0]: k for k, r in enumerate(rows)}
                ok = [r for r in rows if r[2] or r[1] >= T]
                top = (ok + [r for r in rows if not (r[2] or r[1] >= T)])[:20]
                short += len(ok) < 20
                for r in top:
                    ins += r[2]; pc += r[1]; one += r[1] == 1 and not r[2]; pos += index[r[0]]; n += 1
            for j, (_k, bases, rel) in enumerate(ae.base.scenarios):
                plan = join[ae.user_region[j % len(ae.user_region)]] if scope == 'joinable' else plannable
                ok = [r for r in rows_for(bases, plan, inst) if r[2] or r[1] >= T][:20]
                hit.append(any(r[0] in rel for r in ok))
            k = f'{scope}|{reading}|T={T}'
            res[k] = {'바로 대출': round(ins / n, 3), '평균 내 도서관 수': round(pc / n, 1), '1곳뿐·바로 대출 아님': round(one / n, 3),
                      '원래 자리 평균': round(pos / n, 1), '통과 20권 못 됨': round(short / len(ae.base.users), 3),
                      'Hit@20': round(sum(hit) / len(hit), 3), '빈 추천': None}
            print(k, res[k], flush=True)
json.dump(res, open(D / 'threshold2-metrics.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
