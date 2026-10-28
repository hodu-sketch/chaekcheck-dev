"""AI search talk probe (user 2026-10-28 「사용자가 직접 봤을 때 어색한 점」): everyday and odd conversations against a local
backend, each turn shown as the user would see it (head, cards with their text, ask-back, result).
  python talk_probe.py <backend log> [port]   -> <out>/talk-probe.json and talk-probe.md"""
import json, os, sys

import rules_run as r

DIALOGS = [
    ['안녕'], ['안녕하세요 책 추천해줄 수 있어요?'], ['우울할 때 읽을 에세이', '고마워'], ['오늘 날씨 어때'], ['너 누구야'],
    ['책첵이 뭐야'], ['AI 관련 기초 도서', '사진', '나도 잘 모르겠어', '아 그거말고', '아까전에 이야기한거잖아', '아니아니 그거말고'],
    ['추리 소설', '더 보여줘'], ['추리 소설', '다른 거'], ['추리 소설', '두 번째 책 어때?'], ['추리 소설', '이거 다 읽었어'],
    ['추천 별로야'], ['ㅋㅋㅋㅋ'], ['왜 이렇게 못 찾아 진짜'], ['해리포터'], ['해리포터 같은 책'], ['Recommend me a mystery novel'],
    ['10살 아이가 읽을 과학책'], ['회사 그만두고 싶을 때 읽을 책', '좀 더 현실적인 걸로'], ['판타지 소설', '추리 소설', '둘 다 섞은 거'],
    ['요리', '한식'], ['주식 투자 입문', '부동산은?'], ['로그인 어떻게 해?'], ['전자책 빌리는 법'], ['책 말고 영화 추천해줘'],
    ['수학 공부 너무 싫어'], ['뭐 읽지'], ['??'], ['2024년에 나온 소설'], ['철학', '쉬운 걸로', '더 쉬운 거'],
    ['출퇴근길에 30분씩 읽을 수 있고 너무 무겁지 않으면서도 생각할 거리를 주는 한국 작가의 단편 소설집 추천해줘'],
    ['사진', '스마트폰으로 찍는 거'], ['엄마'], ['잠이 안 와'], ['불안해서 아무것도 못하겠어'], ['추리 소설', '그 중에 제일 짧은 거'],
    ['추리 소설', '작가가 누구야?'],
]


def main(logpath, port):
    log = r.Log(logpath)
    out = []
    for turns in DIALOGS:
        history, steps = [], []
        for q in turns:
            s = r.ask(port, log, q, history)
            steps.append({k: s[k] for k in ('q', 'firstText', 'result', 'head', 'ask', 'ms')} |
                         {'cards': [{'title': c['title'], 'author': c.get('author'), 'text': c['text']} for c in s['cards']]})
            history = history + [r.turn_of(q, s)]
            print(q, '->', s['result'], flush=True)
        out.append(steps)
    json.dump(out, open(os.path.join(r.OUT, 'talk-probe.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    with open(os.path.join(r.OUT, 'talk-probe.md'), 'w', encoding='utf-8') as f:
        for n, steps in enumerate(out, 1):
            f.write(f'## {n}\n')
            for s in steps:
                f.write(f"\n**사용자**: {s['q']}  ({s['result']}, 첫 글 {s['firstText']}초)\n\n")
                if s['ask']:
                    f.write(f"- 되묻기: {s['ask']['question']} / 고를 답: {' · '.join(s['ask']['choices'])}\n")
                if s['head']:
                    f.write(f"- 머리말: {s['head']}\n")
                for c in s['cards']:
                    f.write(f"- 카드 「{c['title']}」({c['author']}): {c['text']}\n")
            f.write('\n')


if __name__ == '__main__':
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 8097)
