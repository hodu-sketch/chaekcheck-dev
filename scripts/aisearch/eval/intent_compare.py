"""AI search follow-ups (user 2026-10-29: 「각각 테스트해보고 알려줘」, real users are rude): the server's rules (regex, read from
the Java source so the rules are the ones that run) against the question vector (KURE int8, the same encoder as the embed
server) on held-out rude test sentences, for 「다 같이」, 「최신」, 「아까」 and 「A나 B」 (options 1 rule, 2 rule + vector check,
4 word vectors, 3 MMR over the book vectors).
  venv python intent_compare.py   -> <out>/intent-compare.json and a table"""
import json, os, re, struct, sys
import numpy as np, onnxruntime as ort
from tokenizers import Tokenizer

DATA = r'C:\Users\yujun\Documents\Claude\Projects\chaekcheck-dev\data\aisearch-eval-2026-10-25\intent'
SRC = r'C:\Users\yujun\Documents\Claude\Projects\chaekcheck-dev-talk2\backend\src\main\java\com\chaekcheck\aisearch\internal'
ENC = r'C:\Users\yujun\Documents\Claude\Projects\chaekcheck-data\ml\aisearch-model-100k-v2'
YEAR = 2026


# ---- the Java rules, read from the source ----
def java(path):
    return open(os.path.join(SRC, path), encoding='utf-8').read()


def literal(s):
    return s.replace('\\\\', '\x00').replace('\\"', '"').replace('\x00', '\\')


def pattern(src, name):
    m = re.search(name + r'\s*=\s*(?:java\.util\.regex\.)?Pattern\s*(?:\.compile\(|\n\s*\.compile\()\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)\)', src)
    assert m, name
    return re.compile(''.join(literal(x) for x in re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))))


def strings(src, start):
    i = src.index(start)
    j = src.index(');', i)
    return re.findall(r'"((?:[^"\\]|\\.)*)"', src[i:j])


FLOW, TALK_SRC, QW, TP, REC = (java('flow/AiSearchFlow.java'), java('flow/TalkRefs.java'), java('firstcall/QuestionWords.java'),
                                java('flow/TopicParts.java'), java('flow/Recency.java'))
ALL_CHOICES, NOT_ALL, CONDITION = pattern(FLOW, 'ALL_CHOICES'), pattern(FLOW, 'NOT_ALL'), pattern(FLOW, 'CONDITION')
BACK = pattern(TALK_SRC, 'BACK')
TALK = strings(TALK_SRC, 'TALK = List.of(')
T_ENDING = pattern(TALK_SRC, 'ENDING')
Q_SPLIT, Q_ENDING, Q_JOINED = pattern(QW, 'SPLIT'), pattern(QW, 'ENDING'), pattern(QW, 'JOINED')
Q_STOP = set(strings(QW, 'STOP = Set.of('))
TP_JOINED, TP_OR = pattern(TP, 'JOINED'), pattern(TP, 'OR')
RECENT, SINCE = pattern(REC, 'RECENT'), pattern(REC, 'SINCE')


def words(q):
    out = []
    for piece in Q_SPLIT.split(q):
        w = Q_ENDING.sub('', piece, count=1)
        m = Q_JOINED.fullmatch(w)
        if m:
            w = m.group(1)
        if len(w) >= 2 and w not in Q_STOP and w not in out:
            out.append(w)
    return out[:8]


def is_talk(word):
    can = [True] + [False] * len(word)
    for end in range(1, len(word) + 1):
        for t in TALK:
            s = end - len(t)
            if s >= 0 and can[s] and word.startswith(t, s):
                can[end] = True
                break
    return can[len(word)]


def rest(q):
    out = []
    for piece in re.split(r"[\s,.?!·~\"'()]+", q.lower()):
        if not piece or is_talk(piece):
            continue
        w = T_ENDING.sub('', piece, count=1)
        if len(w) >= 2 and not is_talk(w):
            out.append(w)
    return out


def rule_back(q, topics):
    if not BACK.search(q.lower()):
        return None
    r = rest(q)
    named = [i for i, t in enumerate(topics) if any(w in t.lower() for w in r)]
    if named:
        return named
    if '처음' in q:
        return [0]
    return [0] if len(topics) == 2 else None


def rule_recent(q):
    m = SINCE.search(q)
    if m:
        return int(m.group(1))
    return YEAR - 3 if RECENT.search(q) else None


def rule_all(answer):
    return bool(ALL_CHOICES.search(answer.strip())) and not NOT_ALL.search(answer)


def rule_parts(q):
    ws = re.split(r'\s+', q.strip().replace(',', ', ').replace(' ,', ','))
    topics, i = [], 0
    while i < len(ws):
        w = ws[i]
        if TP_OR.fullmatch(w):
            i += 1
            continue
        m = TP_JOINED.fullmatch(w)
        nxt = i + 1 < len(ws) and TP_OR.fullmatch(ws[i + 1])
        if m and words(m.group(1)):
            topics.append(m.group(1))
        elif nxt and words(w):
            topics.append(w)
        elif topics:
            break
        i += 1
    if not topics:
        return []
    if i < len(ws) and words(ws[i]) and not ws[i].endswith('에서'):
        topics.append(ws[i])
    return topics if len(topics) >= 2 else []


# ---- the encoder ----
tok = Tokenizer.from_file(os.path.join(ENC, 'tokenizer-cut.json'))
tok.enable_truncation(256)
sess = ort.InferenceSession(os.path.join(ENC, 'kure-query-int8-ext.onnx'), providers=['CPUExecutionProvider'])
CACHE = {}


def vec(q):
    if q not in CACHE:
        e = tok.encode(q)
        v = sess.run(None, {'input_ids': np.array([e.ids], dtype=np.int64),
                            'attention_mask': np.array([e.attention_mask], dtype=np.int64)})[0][0]
        CACHE[q] = (v / np.linalg.norm(v)).astype(np.float32)
    return CACHE[q]


def knn(q, proto, label_of, k=5):
    sims = sorted(((float(vec(q) @ vec(p['_text'])), label_of(p)) for p in proto), reverse=True)[:k]
    score = {}
    for s, lab in sims:
        score[lab] = score.get(lab, 0) + s
    return max(score, key=score.get)


def letters(s):
    return re.sub(r'[^0-9a-z가-힣]', '', s.lower())


def same_topic(a, b):
    a, b = letters(a), letters(b)
    return bool(a) and bool(b) and (a in b or b in a)


# ---- sets ----
S = json.load(open(os.path.join(DATA, 'intent-sets.json'), encoding='utf-8'))
out = {}

# 다 같이
proto, test = S['all_choices']['proto'], S['all_choices']['test']
for p in proto:
    p['_text'] = p['answer']
rows = []
for t in test:
    gold = t['label'] == 'all'
    r = rule_all(t['answer'])
    v_lab = knn(t['answer'], proto, lambda p: p['label'])
    v = v_lab == 'all'
    e = (v or r) and v_lab != 'reject'
    one = int(np.argmax([float(vec(t['answer']) @ vec(c)) for c in t['choices']]))
    rows.append({'answer': t['answer'], 'gold': t['label'], 'rule': r, 'vector': v_lab, 'mixed': e,
                 'one_index_ok': t['label'] == 'one' and one == t.get('index')})
out['all_choices'] = rows


def binary(rows, key, gold_key='gold', pos='all'):
    tp = sum(1 for r in rows if r[key] and r[gold_key] == pos)
    fp = sum(1 for r in rows if r[key] and r[gold_key] != pos)
    fn = sum(1 for r in rows if not r[key] and r[gold_key] == pos)
    ok = sum(1 for r in rows if bool(r[key]) == (r[gold_key] == pos))
    return f'맞음 {ok}/{len(rows)}, 놓침 {fn}, 잘못 걸림 {fp}'


for r in rows:
    r['vector_all'] = r['vector'] == 'all'
print('다 같이   규칙:', binary(rows, 'rule'), '| 벡터:', binary(rows, 'vector_all'), '| 섞어:', binary(rows, 'mixed'))
print('   벡터 4갈래 맞음', sum(r['vector'] == r['gold'] for r in rows), '/', len(rows),
      '| 하나 고름의 번호 맞음', sum(r['one_index_ok'] for r in rows), '/', sum(r['gold'] == 'one' for r in rows))

# 최신
proto, test = S['recency']['proto'], S['recency']['test']
for p in proto:
    p['_text'] = p['q']
rows = []
for t in test:
    r = rule_recent(t['q'])
    v = knn(t['q'], proto, lambda p: p['label'])
    e = r if (r and SINCE.search(t['q'])) else (YEAR - 3 if v == 'recent' else None)
    rows.append({'q': t['q'], 'gold': t['label'], 'rule': r is not None, 'vector': v == 'recent', 'mixed': e is not None,
                 'note': t.get('note')})
out['recency'] = rows
print('최신      규칙:', binary(rows, 'rule', pos='recent'), '| 벡터:', binary(rows, 'vector', pos='recent'), '| 섞어:',
      binary(rows, 'mixed', pos='recent'))

# 아까
proto, test = S['back_to']['proto'], S['back_to']['test']


def vec_back(q, topics, margin, floor):
    sims = [float(vec(q) @ vec(t)) for t in topics]
    best = max(sims)
    return [i for i, s in enumerate(sims) if s >= best - margin and s >= floor]


best = None
for margin in (0.0, 0.02, 0.04, 0.06, 0.08, 0.1):
    for floor in (0.2, 0.3, 0.4, 0.5):
        ok = sum(sorted(vec_back(p['q'], p['topics'], margin, floor)) == sorted(p['expected']) for p in proto)
        if best is None or ok > best[0]:
            best = (ok, margin, floor)
_, MARGIN, FLOOR = best
rows = []
for t in test:
    r = rule_back(t['q'], t['topics'])
    v = vec_back(t['q'], t['topics'], MARGIN, FLOOR)
    e = r if r else v
    g = sorted(t['expected'])
    rows.append({'q': t['q'], 'topics': t['topics'], 'gold': g, 'rule': r, 'vector': v,
                 'rule_ok': r is not None and sorted(r) == g, 'vector_ok': sorted(v) == g, 'mixed_ok': e is not None and sorted(e) == g})
out['back_to'] = {'margin': MARGIN, 'floor': FLOOR, 'rows': rows}
print(f'아까      규칙: 맞음 {sum(r["rule_ok"] for r in rows)}/{len(rows)} (아까로 못 알아봄 {sum(r["rule"] is None for r in rows)})',
      f'| 벡터: 맞음 {sum(r["vector_ok"] for r in rows)}/{len(rows)} | 섞어: 맞음 {sum(r["mixed_ok"] for r in rows)}/{len(rows)}',
      f'(벡터 기준 margin {MARGIN}, floor {FLOOR})')

# A나 B: 1 rule, 2 rule + vector check, 4 word vectors
proto, test = S['ab_split']['proto'], S['ab_split']['test']


def parts_ok(pred, gold):
    if not gold:
        return len(pred) < 2
    return len(pred) == len(gold) and all(any(same_topic(p, g) for p in pred) for g in gold)


def checked(pred, thr):
    if len(pred) < 2:
        return pred
    vs = [vec(p) for p in pred]
    low = min(float(vs[i] @ vs[j]) for i in range(len(vs)) for j in range(i + 1, len(vs)))
    return pred if low < thr else []


def word_parts(q, thr):
    ws = [w for w in words(q) if not CONDITION.search(w)]
    if len(ws) < 2:
        return []
    vs = [vec(w) for w in ws]
    # words far from each other and each about as near the question as the others: side-by-side topics
    keep = []
    for i, w in enumerate(ws):
        if all(float(vs[i] @ vec(k)) < thr for k in keep):
            keep.append(w)
    return keep if len(keep) >= 2 else []


THR2 = max((sum(parts_ok(checked(rule_parts(p['q']), t), p['parts']) for p in proto), t) for t in (0.5, 0.6, 0.7, 0.8, 0.9, 1.01))[1]
THR4 = max((sum(parts_ok(word_parts(p['q'], t), p['parts']) for p in proto), t) for t in (0.3, 0.4, 0.5, 0.6, 0.7))[1]
rows = []
for t in test:
    p1 = rule_parts(t['q'])
    p2 = checked(p1, THR2)
    p4 = word_parts(t['q'], THR4)
    rows.append({'q': t['q'], 'gold': t['parts'], 'opt1': p1, 'opt2': p2, 'opt4': p4,
                 'ok1': parts_ok(p1, t['parts']), 'ok2': parts_ok(p2, t['parts']), 'ok4': parts_ok(p4, t['parts'])})
out['ab_split'] = {'thr2': THR2, 'thr4': THR4, 'rows': rows}
multi = [r for r in rows if r['gold']]
single = [r for r in rows if not r['gold']]
for k, name in (('ok1', '1 조사'), ('ok2', '2 조사+벡터 확인'), ('ok4', '4 낱말 벡터')):
    print(f'A나 B {name}: 맞음 {sum(r[k] for r in rows)}/{len(rows)} (여러 주제 {sum(r[k] for r in multi)}/{len(multi)}, '
          f'한 주제 {sum(r[k] for r in single)}/{len(single)})')
print('   기준값 2:', THR2, '4:', THR4)

# 3 MMR against one search and option 1's per-part search, in the book vectors (coverage of each topic)
b = open(os.path.join(ENC, 'book-vectors.bin'), 'rb').read()
count, dim = struct.unpack('<ii', b[6:14])
off = 14 + count * 8
scales = np.frombuffer(b, '<f4', count, off)
rowsv = np.frombuffer(b, np.int8, count * dim, off + count * 4).reshape(count, dim)


def nearest(v, k):
    s = (rowsv @ (v * 127).astype(np.float32)) * scales  # one scale for all queries: the order is the same
    idx = np.argpartition(-s, k)[:k]
    return list(idx[np.argsort(-s[idx])]), s


def mmr(v, k=30, pool=300, lam=0.7):
    idx, s = nearest(v, pool)
    vecs = rowsv[idx].astype(np.float32) * scales[idx][:, None]
    chosen = []
    while len(chosen) < k:
        best_j, best = None, None
        for j in range(len(idx)):
            if j in chosen:
                continue
            red = max((float(vecs[j] @ vecs[c]) for c in chosen), default=0)
            m = lam * float(vecs[j] @ v) - (1 - lam) * red
            if best is None or m > best:
                best, best_j = m, j
        chosen.append(best_j)
    return [idx[j] for j in chosen]


cov = {'one search': [], '1 per part': [], '3 MMR': []}
for t in [r for r in test if r['parts']][:30]:
    q = vec(t['q'])
    own = [set(nearest(vec(p), 100)[0]) for p in t['parts']]
    single_ = set(nearest(q, 30)[0])
    per = set()
    for p in t['parts']:
        per |= set(nearest(vec(p), 30 // len(t['parts']))[0])
    m = set(mmr(q))
    for name, got in (('one search', single_), ('1 per part', per), ('3 MMR', m)):
        cov[name].append(min(len(got & o) for o in own))
out['coverage'] = cov
print('후보 30권 중 가장 적게 들어간 주제의 책 수(중간값):', {k: float(np.median(v)) for k, v in cov.items()})
json.dump(out, open(os.path.join(DATA, 'intent-compare.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
