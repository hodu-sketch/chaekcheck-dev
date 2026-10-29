"""AI search summary by situation (user 2026-10-29): how often it goes out and whether it names only the cards shown,
the free-text way (v1.21.2) on one local backend against the structured way on another, the same questions.
  python tail_compare.py <port A> <label A> <port B> <label B>   -> <out>/tail-compare.json"""
import json, os, re, sys, time, urllib.request, uuid

OUT = r'C:\Users\yujun\Documents\Claude\Projects\chaekcheck-dev\data\aisearch-eval-2026-10-25\rules'
QUESTIONS = ['사진', '여행 에세이', '우주 과학 입문서', '요리', '커피', '출퇴근길에 읽을 짧은 소설', '심리학 입문서', '주식 투자 입문',
             '추리 소설', '판타지 소설', '철학 입문', '자존감 에세이', '육아', '글쓰기 잘하는 법', '경제 교양서', '역사 소설', '그림책',
             '영어 회화', '다이어트', '고양이']
NUMBER = re.compile(r'\d+\s*번(?!째)')
TITLE = re.compile(r'『([^』]+)』')


def letters(text):
    return re.sub(r'[^0-9A-Za-z가-힣]', '', text).lower()


def ask(port, q):
    xsrf = str(uuid.uuid4())
    body = json.dumps({'question': q, 'turnNo': 1, 'history': [], 'residenceRegionIds': [1114]}).encode()
    req = urllib.request.Request(f'http://localhost:{port}/api/v1/ai-searches/stream', body,
                                 {'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrf, 'Cookie': 'XSRF-TOKEN=' + xsrf,
                                  'X-Real-IP': '10.88.%d.%d' % (port % 200, int(time.time() * 1000) % 200 + 1)})
    t0, name, tail, titles, result, last = time.time(), None, '', [], None, None
    with urllib.request.urlopen(req, timeout=120) as r:
        for raw in r:
            line = raw.decode('utf-8').rstrip('\r\n')
            if line.startswith('event:'):
                name = line[6:].strip()
            elif line.startswith('data:'):
                d = json.loads(line[5:])
                if name == 'tail-delta': tail += d['text']
                elif name == 'card': titles.append(d['title'])
                elif name == 'done': result = d['result']
    named = TITLE.findall(tail)
    cards = [letters(t) for t in titles]
    wrong = [t for t in named if not any(c.startswith(letters(t)) or letters(t).startswith(c) for c in cards)]
    return {'q': q, 'result': result, 'cards': len(titles), 'tail': tail, 'wall': round(time.time() - t0, 2),
            'numbers': bool(NUMBER.search(tail)), 'not_shown': wrong, 'lines': len([l for l in tail.split('\n') if l.strip()])}


def main(pa, la, pb, lb):
    out = {la: [], lb: []}
    for q in QUESTIONS:
        for port, label in ((pa, la), (pb, lb)):
            row = ask(port, q)
            out[label].append(row)
            print(label, q, row['result'], row['cards'], 'tail' if row['tail'] else '-', row['not_shown'] or '', flush=True)
    json.dump(out, open(os.path.join(OUT, 'tail-compare.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    for label, rows in out.items():
        eligible = [r for r in rows if r['result'] == 'ok' and r['cards'] >= 2]
        shown = [r for r in eligible if r['tail']]
        print(f"{label}: 끝난 답 {sum(r['result'] == 'ok' for r in rows)}/{len(rows)}, partial {sum(r['result'] == 'partial' for r in rows)}, "
              f"정리 글 {len(shown)}/{len(eligible)}, 번호 {sum(r['numbers'] for r in shown)}, 카드 밖 책 {sum(bool(r['not_shown']) for r in shown)}, "
              f"줄 평균 {sum(r['lines'] for r in shown) / max(1, len(shown)):.1f}, 끝나는 시간 중간 {sorted(r['wall'] for r in rows)[len(rows) // 2]}s")


if __name__ == '__main__':
    main(int(sys.argv[1]), sys.argv[2], int(sys.argv[3]), sys.argv[4])
