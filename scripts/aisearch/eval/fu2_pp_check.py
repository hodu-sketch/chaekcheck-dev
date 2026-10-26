"""Second-call prompt check (user 2026-10-26 "이거 따로 프롬프트 좀 고쳐줘"): the user's three-turn AI agent / LLM
dialogue and questions with and without a time condition, against the local backend on 8095. Counts the answers
that bring up the publish year or "최신" without being asked. usage: pp_check.py LABEL [REPEATS]"""
import json, os, re, sys
HERE = os.path.dirname(os.path.abspath(__file__)); args = sys.argv[1:]
_src = open(os.path.join(HERE, 'fu2_run_dialogues.py'), encoding='utf-8').read()
exec(_src[:_src.index('D = dialogues()')])  # ask() and its request setup, without running the dialogues
DIALOGUE = ['ai agent나...', 'llm인가', '위에 언급한 두 개에 관한 책 좀 찾아줘']
NO_TIME = ['머신러닝 기본도서', 'LLM 입문서', '번아웃이 왔을 때 읽을 책', '아이 키우는 부모를 위한 육아서', '파이썬 처음 배우는 사람 책',
           '경제 공부 시작하는 책', '가볍게 읽을 과학책', '한국 현대사 교양서']
TIME = ['요즘 나온 사회 문제 책', '최근에 나온 AI 책', '2020년 이후에 나온 경제 책', '신간 소설 추천해줘']
YEAR = re.compile(r'\d{4}년|최신|최근|요즘|신간|올해|출간')
label = args[0]; reps = int(args[1]) if len(args) > 1 else 1
out = {'dialogue': [], 'no_time': {}, 'time': {}}
for r in range(reps):
    hist = []
    turns = []
    for n, q in enumerate(DIALOGUE, 1):
        a = ask(q, hist, n); turns.append({'q': q, **a})
        hist.append({'question': q, 'bookIds': [c['bookId'] for c in a['cards']]})
    out['dialogue'].append(turns)
    for q in NO_TIME:
        out['no_time'].setdefault(q, []).append(ask(q, [], 1))
    for q in TIME:
        out['time'].setdefault(q, []).append(ask(q, [], 1))
json.dump(out, open(os.path.join(HERE, f'pp-{label}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
def says_year(a): return bool(YEAR.search(a['head'] + ' '.join(c.get('text') or '' for c in a['cards'])))
nt = [a for v in out['no_time'].values() for a in v]; t = [a for v in out['time'].values() for a in v]
print(label, 'no time asked, year/최신 said: %d/%d' % (sum(map(says_year, nt)), len(nt)),
      '| time asked, year used: %d/%d' % (sum(map(says_year, t)), len(t)))
for turns in out['dialogue']:
    print(' 3rd turn head:', turns[2]['head']); print('  cards:', [c['title'] for c in turns[2]['cards']])
