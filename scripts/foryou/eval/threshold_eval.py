"""Threshold instead of weights (user 2026-10-29 "너무 보유량이 낮은 것은 제외 … 문턱을 넘은 것끼리는 동등하게"):
a pick passes when a plan library holds it through 교보 구독형 (no loan limit) or at least T plan libraries hold it;
passing picks keep the closeness order, the rest are dropped (or, variant 'back', moved after the passing ones)."""
import json, sys
import avail_eval as ae
from pathlib import Path
D = Path(__file__).resolve().parent
res = {}
for scope in ('joinable', 'all'):
    for T in (1, 2, 3, 5, 10):
        for mode in ('drop',) if T == 1 else ('drop', 'back'):
            inst = pc = one = pos = n = short = 0; hit = []
            for i, bases in enumerate(ae.base.users):
                plan = ae.joinable[ae.user_region[i]] if scope == 'joinable' else ae.ALL
                rows = ae.eligible(ae.rr(bases), bases, plan)
                index = {r[0]: k for k, r in enumerate(rows)}
                ok = [r for r in rows if r[2] or r[1] >= T]
                top = (ok if mode == 'drop' else ok + [r for r in rows if not (r[2] or r[1] >= T)])[:20]
                short += len(top) < 20
                for r in top:
                    inst += r[2]; pc += r[1]; one += r[1] == 1 and not r[2]; pos += index[r[0]]; n += 1
            for j, (_k, bases, rel) in enumerate(ae.base.scenarios):
                plan = ae.joinable[ae.user_region[j % len(ae.user_region)]] if scope == 'joinable' else ae.ALL
                rows = ae.eligible(ae.rr(bases), bases, plan)
                top = [r for r in rows if r[2] or r[1] >= T][:20]
                hit.append(any(r[0] in rel for r in top))
            k = f'{scope}|T={T}|{mode}'
            res[k] = {'바로 대출': round(inst / n, 3), '평균 내 도서관 수': round(pc / n, 1),
                      '1곳뿐·구독 아님': round(one / n, 3), '원래 자리 평균': round(pos / n, 1),
                      '20권 못 채운 사용자': round(short / len(ae.base.users), 3), 'Hit@20': round(sum(hit) / len(hit), 3)}
            print(k, res[k], flush=True)
json.dump(res, open(D / 'threshold-metrics.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
