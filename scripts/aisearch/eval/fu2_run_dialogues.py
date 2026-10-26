"""Follow-up test 2 (plans A and B, user 2026-10-26): 80 dialogues of the relation test set (rel/pair-*-t.jsonl,
first questions never trained on), 10 a relation plus 10 more for continue / shift / new. Against the local backend
(first call ml, real Haiku with the dev .env key):
  turn1  python run_dialogues.py turn1          -> turn1.json (the first question's cards, the same for every mode)
  turn2  python run_dialogues.py turn2 <mode>   -> turn2-<mode>.json (the follow-up with the earlier turn as history)
The backend's follow-up mode is set when it starts (CHAEKCHECK_AISEARCH_FOLLOW_UP)."""
import json, os, random, sys, uuid, urllib.request
HERE = os.path.dirname(os.path.abspath(__file__)); RD = os.path.join(HERE, '..', 'rel')
URL = 'http://localhost:8095/api/v1/ai-searches/stream'
XSRF = str(uuid.uuid4()); N = [0]
def load(p): return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]
def dialogues():
    out = []
    for rel, k in (('refers', 10), ('ask_one', 10), ('continue', 20), ('shift', 15), ('feedback', 10), ('new', 15), ('none', 10)):
        rows = load(os.path.join(RD, f'pair-{rel}-t.jsonl')); random.Random(7).shuffle(rows); out += rows[:k]
    return out
def ask(q, history, turn):
    N[0] += 1
    body = json.dumps({'question': q, 'turnNo': turn, 'history': history, 'residenceRegionIds': [1114]}).encode()
    req = urllib.request.Request(URL, body, {'Content-Type': 'application/json', 'X-XSRF-TOKEN': XSRF, 'Cookie': 'XSRF-TOKEN=' + XSRF,
                                             'X-Real-IP': '10.77.%d.%d' % (N[0] // 200, N[0] % 200 + 1)})
    ev, name = [], None
    with urllib.request.urlopen(req, timeout=90) as r:
        for line in r.read().decode('utf-8').split('\n'):
            if line.startswith('event:'): name = line[6:]
            elif line.startswith('data:'): ev.append((name, json.loads(line[5:])))
    cards = [d for n, d in ev if n == 'card']
    head = ''.join(d['text'] for n, d in ev if n == 'head-delta')
    done = [d for n, d in ev if n == 'done']
    # the pick text of each card comes in card-delta events (fixed 2026-10-26: before, only the card's blurb was kept)
    texts = {}
    for n, d in ev:
        if n == 'card-delta': texts[d['bookId']] = texts.get(d['bookId'], '') + d['text']
    return {'cards': [{'bookId': c['bookId'], 'title': c['title'], 'author': c.get('author'), 'summary': c.get('aiSummary'),
                       'text': texts.get(c['bookId'], '')} for c in cards],
            'head': head, 'result': done[-1]['result'] if done else None}
D = dialogues()
if sys.argv[1] == 'single':
    # speed and tokens of the first step (user 2026-10-26 "빠진 측정"): the 57 eval questions once, first turn only
    import time
    label = sys.argv[2]; Q = json.load(open(os.path.join(HERE, '..', '..', 'eval_questions.json'), encoding='utf-8')) if len(sys.argv) < 4 else json.load(open(sys.argv[3], encoding='utf-8'))
    out = {}
    for x in Q:
        t = time.time(); r = ask(x['q'], [], 1); r['ms'] = int((time.time() - t) * 1000); out[str(x['id'])] = r
        print(label, x['id'], r['result'], len(r['cards']), r['ms'], flush=True)
    json.dump(out, open(os.path.join(HERE, f'single-{label}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
    sys.exit()
if sys.argv[1] == 'turn1':
    out = {}
    for x in D:
        out[x['id']] = ask(x['q1'], [], 1); print(x['id'], len(out[x['id']]['cards']), flush=True)
    json.dump({'dialogues': D, 'turn1': out}, open(os.path.join(HERE, 'turn1.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
else:
    mode = sys.argv[2]; T1 = json.load(open(os.path.join(HERE, 'turn1.json'), encoding='utf-8'))['turn1']; out = {}
    for x in D:
        hist = [{'question': x['q1'], 'bookIds': [c['bookId'] for c in T1[x['id']]['cards']]}]
        out[x['id']] = ask(x['q2'], hist, 2); print(mode, x['id'], out[x['id']]['result'], len(out[x['id']]['cards']), flush=True)
    run = '' if len(sys.argv) < 4 else '-' + sys.argv[3]  # repeat runs (r2, r3) for the spread
    json.dump(out, open(os.path.join(HERE, f'turn2-{mode}{run}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
