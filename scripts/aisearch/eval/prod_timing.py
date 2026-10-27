"""AI search first-text timing on production (docs/reports/ai-search-latency-2026-10-27.md 6절): the same 24 first
questions (12 of 5 words or more, 12 of 4 or fewer) one at a time, 7 s apart (rate 10 a minute per IP), then the server's
log lines of that window (the steps) over SSH.
  python prod_timing.py <label>      -> <out>/prod-<label>.json"""
import json, os, re, subprocess, sys, time, urllib.request, uuid
from datetime import datetime, timezone

OUT = os.environ.get('LATENCY_OUT', r'C:\Users\yujun\Documents\Claude\Projects\chaekcheck-dev\data\aisearch-eval-2026-10-25\latency-prod')
KEY = r'C:\Users\yujun\Documents\Claude\Projects\chaekcheck-dev\.secrets\chaekcheck-key.pem'
SERVER = 'ubuntu@52.64.117.112'
LONG = ['소설 말고 위로가 되는 책', '자기계발서 말고 마음이 편해지는 에세이', '수식 없이 읽는 물리학 책', '번역서 말고 한국 작가가 쓴 경제 책',
        '공포 말고 따뜻한 판타지 소설', '어린이책 말고 어른이 보는 그림책', '출근길 지하철에서 가볍게 읽을 과학책', '기분 전환이 되는 웃긴 소설',
        '경제를 처음 공부하는 사람을 위한 책', '회사에서 글쓰기 실력을 늘리는 책', '여행 가서 읽을, 너무 무겁지 않은 인문학 책', '미래 소비 트렌드와 경제적 자산 관리']
SHORT = ['사진', '음악', '바다', '게임', '커피', '로마', '전쟁', '엄마', '자바스크립트', '주식 투자', '다이어트', '영어 회화']
LINE = re.compile(r'^(\S+)\s.*ai search: first .*?cards (\d+), (\d+) ms, tokens (\d+)/(\d+)(.*)$')


def ask(q):
    xsrf = str(uuid.uuid4())
    body = json.dumps({'question': q, 'turnNo': 1, 'history': [], 'residenceRegionIds': [1114]}).encode()
    req = urllib.request.Request('https://chaekcheck.com/api/v1/ai-searches/stream', body,
                                 {'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrf, 'Cookie': 'XSRF-TOKEN=' + xsrf,
                                  'User-Agent': 'chaekcheck-latency-check'})
    t0, first, name, asked, result = time.time(), None, None, False, None
    with urllib.request.urlopen(req, timeout=60) as r:
        for raw in r:
            line = raw.decode('utf-8').rstrip('\r\n')
            if line.startswith('event:'):
                name = line[6:].strip()
            elif line.startswith('data:'):
                if first is None and name in ('head-delta', 'card', 'ask'):
                    first = round(time.time() - t0, 3)
                if name == 'ask': asked = True
                if name == 'done': result = json.loads(line[5:])['result']
    return {'q': q, 'words': len(q.split()), 'firstText': first, 'wall': round(time.time() - t0, 3), 'ask': asked, 'result': result}


def main(label):
    os.makedirs(OUT, exist_ok=True)
    start = datetime.now(timezone.utc)
    rows = []
    for a, b in zip(LONG, SHORT):
        for q in (a, b):
            rows.append(ask(q)); print(rows[-1], flush=True); time.sleep(7)
    end = datetime.now(timezone.utc)
    log = subprocess.run(['ssh', '-i', KEY, SERVER, "docker logs --since %s --until %s chaekcheck-backend-1 2>&1 | grep 'ai search: first'"
                          % (start.strftime('%Y-%m-%dT%H:%M:%SZ'), end.strftime('%Y-%m-%dT%H:%M:%SZ'))],
                         capture_output=True, text=True, encoding='utf-8').stdout.splitlines()
    lines = []
    for l in log:
        m = LINE.search(l)
        if m:
            s = re.search(r'steps (.*)$', m.group(6))
            lines.append({'ms': int(m.group(3)), 'early': 'early keyword' in l,
                          'steps': {k: int(v) for k, v in (x.split('=') for x in s.group(1).split())} if s else {}})
    if len(lines) == len(rows):
        for r, l in zip(rows, lines): r.update(server=l)
    json.dump({'label': label, 'start': start.isoformat(), 'rows': rows, 'server_lines': lines,
               'matched': len(lines) == len(rows)}, open(os.path.join(OUT, f'prod-{label}.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('log lines', len(lines), 'requests', len(rows))


if __name__ == '__main__':
    main(sys.argv[1])
