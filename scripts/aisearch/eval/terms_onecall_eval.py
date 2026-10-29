"""Step 0 of the two-layer + BM25 plan (user 2026-10-30 "한 줄 + Haiku 검색 낱말 (원문 불필요)로 가자"): the raw intros
stay on the PC, so production can hold only text Haiku writes. Measured on the intro-text experiment's 1,000 books:

A  one Haiku call per book writing the summary AND the search terms (as production would), against the experiment's two
   separate calls: summary vector, two-max-chunk512 (best of summary and 512-token pieces), pg_search Korean BM25 over
   title + author + line + terms, and their mix (RRF 50 + 60); 800 known-book queries and 57 topic questions.
B  what Haiku reads in the second call: summary + all terms, summary + the terms that match the question, against line,
   summary and summary + intro snippet (experiment numbers on the same queries). Fixed half sample: 50 of each kind.
C  the BM25 gate (bm25_gate_eval.py) again with BM25 over the terms instead of the whole intro: a question whose BM25
   top-1 score (or top1 / top10) is below a percentile drops BM25 (cut 0; shrinking did not work, report 10).

usage: terms_onecall_eval.py llm | embed | pgsearch | judge | pick | report   (data: intro_text_eval.OUT)
"""
import json
import random
import re
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import intro_text_eval as e  # noqa: E402

OUT = e.OUT
PCTS = (10, 20, 30, 40, 50)
HALF = 50  # pick sample per kind

ONECALL_PROMPT = """<book>
{head}
{intro}
</book>
위 책 소개글로 두 가지를 쓰세요.
1. summary: 한국어 200~250자 요약.
- 책의 주제, 분야, 형식, 대상 독자, 핵심 내용을 남깁니다.
- 검색에 쓰일 주제어와 개념어는 소개글의 표현을 그대로 살립니다.
- 출판사 홍보 문구, 수상·판매 실적, 추천사, 반복되는 말은 뺍니다.
2. terms: 이 책을 낱말 검색으로 찾을 수 있게, 사람들이 검색창에 칠 만한 낱말 30~50개를 쉼표로 이어서.
- 소개글에 나오는 주제어, 개념, 이론, 기법, 실존 인물, 사건, 시대, 지명, 전문 용어를 빠짐없이 원문 표기 그대로.
- 분야, 형식(소설, 에세이, 그림책 등), 대상 독자, 그리고 그 낱말들을 사람들이 흔히 부르는 다른 이름(동의어, 줄임말, 영어 이름).
- 제목·저자는 쓰지 않습니다.
둘 다 소개글에 없는 내용은 지어내지 않습니다.
JSON 한 줄로만 답하세요: {{"summary": "...", "terms": "낱말, 낱말, ..."}}"""


def onecall() -> dict[str, dict[str, str]]:
    out = {}
    for key, text in e.jsonl('onecall.jsonl').items():
        m = re.search(r'\{.*\}', text, re.S)
        try:
            got = json.loads(m.group(0)) if m else {}
        except json.JSONDecodeError:
            got = {}
        out[key] = {'summary': (got.get('summary') or '').strip(), 'terms': (got.get('terms') or '').strip()}
    return out


def llm() -> None:
    books = e.read_json('books.json')
    t = time.monotonic()
    e.run_jobs('onecall.jsonl', [(str(b['book_id']), e.HAIKU, ONECALL_PROMPT.format(head=e.head(b), intro=b['intro'][:e.LLM_INTRO_MAX]), 1200)
                                 for b in books])
    e.add_usage('onecall', time.monotonic() - t)
    oc = onecall()
    bad = [k for k, v in oc.items() if not v['summary'] or not v['terms']]
    print('empty summary or terms:', len(bad), bad[:10])


def embed() -> None:
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(e.MODEL, device='cpu')
    oc = onecall()
    rows = [f"{e.head(b)}\n{oc[str(b['book_id'])]['summary']}" for b in e.read_json('books.json')]
    t = time.monotonic()
    np.save(OUT / 'vec-summary1.npy', model.encode(rows, batch_size=16, normalize_embeddings=True,
                                                    convert_to_numpy=True).astype('float32'))
    print('vec-summary1', round(time.monotonic() - t), 's')


def pgsearch() -> None:
    """kw_terms1_ko (title + author + line + one-call terms), and the top 50 with pdb.score for it and the experiment's
    kw_terms_ko, as bm25_gate_eval.scores does for the intro."""
    import psycopg
    books = e.read_json('books.json')
    qs = e.read_json('queries.json')
    lines = e.lines_by_book()
    oc = onecall()
    where = {b['book_id']: i for i, b in enumerate(books)}
    old_orders = e.read_json('pgsearch-orders.json')['pgko_terms']
    out = {}
    with psycopg.connect(e.PGSEARCH_DSN, autocommit=True) as db:
        db.execute('DROP TABLE IF EXISTS kw_terms1_ko')
        db.execute('CREATE TABLE kw_terms1_ko (id bigint PRIMARY KEY, body text NOT NULL)')
        with db.cursor().copy('COPY kw_terms1_ko (id, body) FROM STDIN') as cp:
            for b in books:
                cp.write_row((b['book_id'], f"{b['title']} {b['author']} {lines.get(b['book_id'], '')} "
                                            f"{oc[str(b['book_id'])]['terms']}"))
        db.execute('CREATE INDEX kw_terms1_ko_idx ON kw_terms1_ko USING paradedb (id, (body::pdb.lindera(korean)))')
        for table, name in (('kw_terms_ko', 'terms'), ('kw_terms1_ko', 'terms1')):
            per = {}
            for n, q in enumerate(qs):
                got = db.execute(f'SELECT id, pdb.score(id) FROM {table} WHERE body ||| %s '
                                 f'ORDER BY pdb.score(id) DESC, id LIMIT {e.KW_DEPTH}',
                                 (' '.join(e.question_words(q['q'])),)).fetchall()
                order = [where[r[0]] for r in got]
                if name == 'terms' and order != old_orders[n]:
                    raise SystemExit(f"{q['id']}: kw_terms_ko order differs from pgsearch-orders.json")
                per[q['id']] = [[i, float(r[1])] for i, r in zip(order, got)]
            out[name] = per
    e.write_json('onecall-bm25-scores.json', out)
    print({k: len(v) for k, v in out.items()})


# ---------------------------------------------------------------- the lists

def vector_orders() -> dict[str, dict[str, list[int]]]:
    qv = np.load(OUT / 'vec-queries.npy')
    qs = e.read_json('queries.json')
    s_old, s_new = e.book_scores('summary', qv), e.book_scores('summary1', qv)
    chunk = e.book_scores('chunk512', qv)
    out = {}
    for name, sc in (('summary', s_old), ('summary1', s_new), ('two-max-chunk512', np.maximum(s_old, chunk)),
                     ('two-max1-chunk512', np.maximum(s_new, chunk))):
        order = np.argsort(-sc, axis=1)[:, :1000]
        out[name] = {q['id']: [int(i) for i in row] for q, row in zip(qs, order)}
    return out


def keyword_orders() -> dict[str, dict[str, list[int]]]:
    sc = e.read_json('onecall-bm25-scores.json')
    return {f'pgko_{k}': {qid: [r[0] for r in rows] for qid, rows in v.items()} for k, v in sc.items()}


def gate_features(name: str) -> dict[str, dict[str, float]]:
    sc = e.read_json('onecall-bm25-scores.json')[name]
    f = {'score': {}, 'ratio': {}}
    for qid, rows in sc.items():
        s = [r[1] for r in rows]
        f['score'][qid] = s[0] if s else 0.0
        f['ratio'][qid] = (s[0] / s[min(9, len(s) - 1)]) if s else 0.0
    return f


def lists() -> list[dict]:
    """Every list measured: name, how it orders a question, and (gate rows) the questions sent to vectors alone."""
    vec, kw = vector_orders(), keyword_orders()
    qs = e.read_json('queries.json')
    out = []
    for v in ('summary', 'summary1', 'two-max-chunk512', 'two-max1-chunk512'):
        out.append({'name': v, 'part': 'A', 'order': (lambda qid, v=v: vec[v][qid])})
    for k in ('pgko_terms', 'pgko_terms1'):
        out.append({'name': k, 'part': 'A', 'order': (lambda qid, k=k: kw[k][qid])})
    for v, k in (('summary', 'pgko_terms'), ('summary1', 'pgko_terms1'), ('two-max-chunk512', 'pgko_terms'),
                 ('two-max1-chunk512', 'pgko_terms1')):
        out.append({'name': f'{v}+{k}', 'part': 'A',
                    'order': (lambda qid, v=v, k=k: e.rrf(kw[k][qid][:e.KW_DEPTH], vec[v][qid][:e.VEC_DEPTH]))})
    # C: the gate over the terms BM25 (experiment terms: the base the user chose; one-call terms: production's)
    for v, k, short in (('two-max-chunk512', 'pgko_terms', 'terms'), ('two-max1-chunk512', 'pgko_terms1', 'terms1')):
        def gated(qid, topic, v=v, k=k):
            return e.rrf(([] if qid in topic else kw[k][qid][:e.KW_DEPTH]), vec[v][qid][:e.VEC_DEPTH])
        out.append({'name': f'C {short}: base', 'part': 'C', 'gate': None, 'topic': set(), 'order': gated, 'base': short})
        out.append({'name': f'C {short}: all drop', 'part': 'C', 'gate': 'all', 'topic': {q['id'] for q in qs},
                    'order': gated, 'base': short})
        for feat, vals in gate_features(short).items():
            xs = sorted(vals.values())
            for pct in PCTS:
                thr = xs[len(xs) * pct // 100]
                out.append({'name': f'C {short}: {feat} < {thr:.3g} (p{pct})', 'part': 'C', 'gate': feat, 'thr': thr,
                            'pct': pct, 'topic': {qid for qid, x in vals.items() if x < thr}, 'order': gated,
                            'base': short})
    return out


def order_of(item: dict, qid: str) -> list[int]:
    return item['order'](qid, item['topic']) if 'topic' in item else item['order'](qid)


def judge() -> None:
    """Grade the topic top-10 books that no judge pool holds yet, with the experiment's prompt."""
    import bm25_gate_eval as gate
    books = e.read_json('books.json')
    by_id = {b['book_id']: b for b in books}
    ids = [b['book_id'] for b in books]
    have = gate.all_grades()
    items = lists()
    pools = OUT / 'onecall-judge-pools'
    pools.mkdir(exist_ok=True)
    rng = random.Random(e.SEED)
    jobs = []
    for q in e.read_json('queries.json'):
        if q['kind'] != 'topic':
            continue
        new = sorted({ids[i] for it in items for i in order_of(it, q['id'])[:10]} - set(have.get(q['id'], {})))
        if not new:
            continue
        rng.shuffle(new)
        old = pools / f"{q['id']}.json"
        if old.exists() and json.loads(old.read_text()) != new:
            raise SystemExit(f'{old} holds another pool')
        old.write_text(json.dumps(new))
        listing = '\n\n'.join(f"[{n + 1}] {e.head(by_id[b])}\n{by_id[b]['intro'][:e.JUDGE_INTRO_MAX]}" for n, b in enumerate(new))
        jobs.append((q['id'], e.HAIKU, e.JUDGE_PROMPT.format(q=q['q'], books=listing), 1500))
    print(len(jobs), 'questions,', sum(len(json.loads((pools / f'{j[0]}.json').read_text())) for j in jobs), 'new books')
    t = time.monotonic()
    e.run_jobs('onecall-judge.jsonl', jobs)
    e.add_usage('onecall-judge', time.monotonic() - t)


def all_grades() -> dict[str, dict[int, int]]:
    import bm25_gate_eval as gate
    g = gate.all_grades()
    if (OUT / 'onecall-judge.jsonl').exists():
        for key, text in e.jsonl('onecall-judge.jsonl').items():
            pool = json.loads((OUT / 'onecall-judge-pools' / f'{key}.json').read_text())
            m = re.search(r'\{.*\}', text, re.S)
            got = json.loads(m.group(0)) if m else {}
            g.setdefault(key, {}).update({b: int(got[str(n + 1)]) for n, b in enumerate(pool) if str(n + 1) in got})
    return g


# ---------------------------------------------------------------- B: the reading text

def matched(terms: str, words: list[str]) -> list[str]:
    """The terms that share a question word: one holds the other, or they share two letters in a row."""
    ws = [e.tidy(w) for w in words]
    out = []
    for t in (x.strip() for x in terms.split(',')):
        tt = e.tidy(t)
        if not tt or t in out:
            continue
        if any(w in tt or tt in w or any(w[i:i + 2] in tt for i in range(len(w) - 1)) for w in ws):
            out.append(t)
    return out


def half_sample() -> list[dict]:
    qs = [q for q in e.read_json('queries.json') if q['kind'] in e.KNOWN]
    rng = random.Random(e.SEED + 7)
    out = []
    for kind in e.KNOWN:
        sel = [q for q in qs if q['kind'] == kind]
        out += sorted(rng.sample(sel, HALF), key=lambda q: q['id'])
    return out


def pick() -> None:
    books = {b['book_id']: b for b in e.read_json('books.json')}
    cands = e.read_json('pick-candidates.json')
    oc = onecall()
    jobs, stats = [], {'matched_terms_mean': [], 'with_match': 0, 'n': 0}
    for q in half_sample():
        c = cands[q['id']]
        words = e.question_words(q['q'])
        per = {'summary1': {}, 'summary1_terms': {}, 'summary1_match': {}}
        for b in c:
            s, t = oc[str(b)]['summary'], oc[str(b)]['terms']
            per['summary1'][b] = s
            per['summary1_terms'][b] = s + (f' / 검색 낱말: {t}' if t else '')
            m = matched(t, words)
            per['summary1_match'][b] = s + (f" / 검색 낱말: {', '.join(m)}" if m else '')
            if b == q['book_id']:
                stats['matched_terms_mean'].append(len(m))
                stats['with_match'] += bool(m)
                stats['n'] += 1
        for name, text in per.items():
            listing = '\n'.join(f"{n + 1}. 제목: {books[b]['title']} / 저자: {books[b]['author'] or '(모름)'}"
                                f"{' / 출간: ' + books[b]['year'] + '년' if books[b]['year'] else ''}"
                                f"{' / 한 줄: ' + text[b] if text[b] else ''}" for n, b in enumerate(c))
            jobs.append((f"{name}|{q['id']}", e.HAIKU, e.PICK_PROMPT.format(q=q['q'], books=listing), 400))
    stats['matched_terms_mean'] = round(sum(stats['matched_terms_mean']) / len(stats['matched_terms_mean']), 2)
    e.write_json('onecall-pick-stats.json', stats)
    print(stats, len(jobs), 'jobs')
    t = time.monotonic()
    e.run_jobs('onecall-pick.jsonl', jobs)
    e.add_usage('onecall-pick', time.monotonic() - t)


def pick_rates() -> dict:
    qs = {q['id']: q for q in e.read_json('queries.json')}
    sample = {q['id'] for q in half_sample()}
    cands = e.read_json('pick-candidates.json')
    out = {}
    rows = list(e.jsonl('pick.jsonl').items()) + list(e.jsonl('onecall-pick.jsonl').items())
    for key, text in rows:
        name, qid = key.split('|', 1)
        if qid not in sample:
            continue
        m = re.search(r'\{.*\}', text, re.S)
        try:
            picks = json.loads(m.group(0))['picks'] if m else []
        except (json.JSONDecodeError, KeyError):
            picks = []
        c = cands[qid]
        got = [c[p['no'] - 1] for p in picks if isinstance(p, dict) and isinstance(p.get('no'), int) and 0 < p['no'] <= len(c)]
        for kind in (qs[qid]['kind'], 'all'):
            r = out.setdefault(name, {}).setdefault(kind, {'n': 0, 'picked': 0, 'first': 0, 'picks': 0})
            r['n'] += 1
            r['picked'] += qs[qid]['book_id'] in got
            r['first'] += bool(got) and got[0] == qs[qid]['book_id']
            r['picks'] += len(got)
    for per in out.values():
        for r in per.values():
            r['picked_rate'] = round(r['picked'] / r['n'], 3)
            r['first_rate'] = round(r['first'] / r['n'], 3)
            r['picks_mean'] = round(r['picks'] / r['n'], 2)
    return out


# ---------------------------------------------------------------- report

def report() -> None:
    books = e.read_json('books.json')
    ids = [b['book_id'] for b in books]
    where = {b: i for i, b in enumerate(ids)}
    qs = e.read_json('queries.json')
    known = [q for q in qs if q['kind'] in e.KNOWN]
    topic = [q for q in qs if q['kind'] == 'topic']
    g = all_grades()
    rows = []
    for it in lists():
        rk = {}
        for q in known:
            o = order_of(it, q['id'])
            i = where[q['book_id']]
            rk[q['id']] = o.index(i) + 1 if i in o else len(ids) + 1
        per = {}
        for kind in e.KNOWN + ('all',):
            xs = [rk[q['id']] for q in known if kind == 'all' or q['kind'] == kind]
            per[kind] = {'mrr': round(sum(1 / x for x in xs) / len(xs), 3), 'r1': round(sum(x <= 1 for x in xs) / len(xs), 3),
                         'r10': round(sum(x <= 10 for x in xs) / len(xs), 3), 'r30': round(sum(x <= 30 for x in xs) / len(xs), 3)}
        p10, unjudged = [], 0
        for q in topic:
            got = [g.get(q['id'], {}).get(ids[i]) for i in order_of(it, q['id'])[:10]]
            unjudged += sum(x is None for x in got)
            p10.append(sum((x or 0) >= 2 for x in got) / 10)
        row = {'name': it['name'], 'part': it['part'], 'known': per, 'topic_p10': round(sum(p10) / len(p10), 3),
               'topic_unjudged': unjudged}
        if 'topic' in it:
            row.update({'gate': it.get('gate'), 'thr': it.get('thr'), 'pct': it.get('pct'),
                        'as_topic_known': sum(q['id'] in it['topic'] for q in known),
                        'as_topic_topic': sum(q['id'] in it['topic'] for q in topic),
                        'as_topic_known_by_kind': {k: sum(q['id'] in it['topic'] for q in known if q['kind'] == k)
                                                   for k in e.KNOWN}})
        rows.append(row)
    oc = onecall()
    terms_n = sorted(len([t for t in v['terms'].split(',') if t.strip()]) for v in oc.values())
    sum_len = sorted(len(v['summary']) for v in oc.values())
    old_terms = sorted(len([t for t in v.split(',') if t.strip()]) for v in e.jsonl('terms.jsonl').values())
    old_sum = sorted(len(v) for v in e.jsonl('summary.jsonl').values())
    out = {'lists': rows, 'pick': pick_rates() if (OUT / 'onecall-pick.jsonl').exists() else {},
           'pick_stats': e.read_json('onecall-pick-stats.json') if (OUT / 'onecall-pick-stats.json').exists() else {},
           'texts': {'onecall_terms_median': terms_n[500], 'onecall_summary_chars_median': sum_len[500],
                     'separate_terms_median': old_terms[500], 'separate_summary_chars_median': old_sum[500]},
           'usage': {k: v for k, v in json.loads((OUT / 'usage.json').read_text()).items() if k.startswith('onecall')}}
    e.write_json('onecall-report.json', out)
    for r in rows:
        a = r['known']['all']
        extra = f" | as topic {r['as_topic_known']}/{r['as_topic_topic']}" if 'as_topic_known' in r else ''
        print(f"{r['name']:44s} mrr {a['mrr']:.3f} r1 {a['r1']:.3f} r10 {a['r10']:.3f} r30 {a['r30']:.3f} | "
              f"topic {r['topic_p10']:.3f} (unj {r['topic_unjudged']}){extra}")
    for name, per in out['pick'].items():
        print(name, {k: (v['picked_rate'], v['first_rate']) for k, v in per.items()})
    print(out['texts'], out['pick_stats'])


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    steps = {'llm': llm, 'embed': embed, 'pgsearch': pgsearch, 'judge': judge, 'pick': pick, 'report': report}
    step = sys.argv[1] if len(sys.argv) > 1 else ''
    if step not in steps:
        raise SystemExit('step: ' + ' | '.join(steps))
    steps[step]()
