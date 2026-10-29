"""The production dialogue of 2026-10-29 20:54 (최신책 → 아까 → 왜 못 골라 → 다 같이) and rude variants, turn by turn on a
local backend as the web sends them (history with ask-backs and heads), with the server's log line of each turn (from,
parts, talk). User 2026-10-29 「각각 테스트해보고」.
  python follow_up_replay.py <port> <label> <backend log>   -> <out>/follow-up-<label>.json"""
import json, os, sys, time

import rules_run as r

OUT = r'C:\Users\yujun\Documents\Claude\Projects\chaekcheck-dev\data\aisearch-eval-2026-10-25\intent'
DIALOGUES = {
    'prod-20-54': ['재밌고 의외인 것이 많은 교양서 알려줘', '이거말고 세계역사나 과학사나 좀 더 다양한 분야에서', '최신책',
                   '아니 최신책 아까 세계역사나 뭐 이런 것들 이야기했자나', '아니 왜 못 골라', '다 같이 하면 되자나',
                   '아니 시발 다 같이하라고 세계사나 과학사나 왜 이게 안 돼'],
    'typo': ['세게사 책', '과학싸나 철학', '요새꺼', '아까 세계사 말한거 최신으로'],
    'list': ['요리 여행 캠핑 다 보여줘', '최근꺼만'],
    'mood': ['요즘 너무 지쳤는데 위로되는 책', '짧은 거'],
}


def main(port, label, logpath):
    log = r.Log(logpath)
    out = {}
    for name, questions in DIALOGUES.items():
        history, steps = [], []
        for q in questions:
            s = r.ask(port, log, q, history)
            steps.append({k: s.get(k) for k in ('q', 'result', 'head', 'ask', 'ms', 'steps', 'line')} |
                         {'cards': [c['title'] for c in s['cards']]})
            print(name, '|', q, '|', s['result'], '|', (s['ask'] or {}).get('question') if s['ask'] else s['head'][:80],
                  '|', [c['title'] for c in s['cards']][:8], flush=True)
            history = history + [r.turn_of(q, s)]
            time.sleep(6)
        out[name] = steps
    json.dump(out, open(os.path.join(OUT, f'follow-up-{label}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main(int(sys.argv[1]), sys.argv[2], sys.argv[3])
