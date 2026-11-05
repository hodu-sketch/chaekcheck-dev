"""BM25 gate (user 2026-10-30 "이것도 병렬로 재 줘"): on topic questions (no single asked book, e.g. 「우울할 때 읽을 책」)
the vector list alone beat vector + BM25 (intro-text report 4-6), on specific-book questions BM25 helps a lot. Can the
search tell the two apart and give BM25 fewer places on topic questions?

Base = the user's choice: two-max-chunk512 (summary vector + plain 512-token pieces, best of the book) + pgko_intro
(pg_search Korean BM25 over title + author + line + whole intro), RRF k 60 of BM25 top 50 + vector top 60.

Gates (a question judged "topic" gets BM25 depth `cut` instead of 50):
  score   the BM25 top-1 score (pdb.score) below a threshold; also top1 / top10
  idf     the highest IDF of the question words over the 1,000 books below a threshold (words found in no book skipped)
  route   the production ML first call's route (first-call-model.bin over the int8 KURE question vector): not
          TITLE_LIKE = topic
Each gate is measured on both sets: the 800 known-book queries (MRR, R@1/10/30) and the 57 topic questions (share of
top 10 graded >= 2 by the experiment's Haiku judge; books not in the judge pools yet are graded with the same prompt).

usage: bm25_gate_eval.py scores | route | judge | report   (data: intro_text_eval.OUT)
"""
import json
import math
import os
import random
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import intro_text_eval as e  # noqa: E402

OUT = e.OUT
VECTOR = 'two-max-chunk512'
KEYWORD = 'pgko_intro'
CUTS = (0, 15, 25)
PERCENTILES = (10, 20, 30, 40, 50, 60, 70)
MODEL_DIR = e.PROJECTS / 'chaekcheck-data' / 'ml' / 'aisearch-model-100k'


def scores() -> None:
    """BM25 top 50 with pdb.score per query, from the experiment's kw_intro_ko table in the test container."""
    import psycopg
    books = e.read_json('books.json')
    where = {b['book_id']: i for i, b in enumerate(books)}
    qs = e.read_json('queries.json')
    old = e.read_json('pgsearch-orders.json')[KEYWORD]
    out = {}
    with psycopg.connect(e.PGSEARCH_DSN, autocommit=True) as db:
        for n, q in enumerate(qs):
            got = db.execute(f'SELECT id, pdb.score(id) FROM kw_intro_ko WHERE body ||| %s '
                             f'ORDER BY pdb.score(id) DESC, id LIMIT {e.KW_DEPTH}',
                             (' '.join(e.question_words(q['q'])),)).fetchall()
            order = [where[r[0]] for r in got]
            if order != old[n]:
                raise SystemExit(f"{q['id']}: the order differs from pgsearch-orders.json")
            out[q['id']] = [[i, float(s)] for i, s in zip(order, (r[1] for r in got))]
    e.write_json('gate-bm25-scores.json', out)
    print(len(out), 'queries')


def route() -> None:
    """The ML first call's route of each question, as the backend runs it (fix1029_check.py's reading of CCFC1)."""
    import struct
    import onnxruntime as ort
    from tokenizers import Tokenizer
    with open(MODEL_DIR / 'first-call-model.bin', 'rb') as f:
        assert f.read(6) == b'CCFC1\n'
        n = struct.unpack('<i', f.read(4))[0]
        h = json.loads(f.read(n).decode('utf-8'))
        dim, hid = h['dim'], h['hidden']
        heads = {}
        for hd in h['heads']:
            o = len(hd['labels'])
            w1 = np.frombuffer(f.read(hid * dim * 4), '<f4').reshape(hid, dim)
            b1 = np.frombuffer(f.read(hid * 4), '<f4')
            w2 = np.frombuffer(f.read(o * hid * 4), '<f4').reshape(o, hid)
            b2 = np.frombuffer(f.read(o * 4), '<f4')
            heads[hd['name']] = dict(hd, W1=w1, b1=b1, W2=w2, b2=b2)
    fc = heads['route']
    tok = Tokenizer.from_file(str(MODEL_DIR / 'tokenizer-cut.json'))
    tok.enable_truncation(256)
    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    sess = ort.InferenceSession(str(MODEL_DIR / 'kure-query-int8-ext.onnx'), so, providers=['CPUExecutionProvider'])
    qs = e.read_json('queries.json')
    vs = []
    for q in qs:
        t = tok.encode(q['q'])
        v = sess.run(None, {'input_ids': np.array([t.ids], dtype=np.int64),
                            'attention_mask': np.array([t.attention_mask], dtype=np.int64)})[0][0]
        vs.append(v / np.linalg.norm(v))
    x = np.array(vs, dtype=np.float64)
    z = np.maximum(0, x @ fc['W1'].T.astype(np.float64) + fc['b1']) @ fc['W2'].T.astype(np.float64) + fc['b2']
    p = np.exp(z - z.max(1, keepdims=True))
    p /= p.sum(1, keepdims=True)
    ways, tau = fc['labels'], fc['tau']
    out = {}
    for q, row in zip(qs, p):
        k = int(np.argmax(row))
        out[q['id']] = ways[k] if k == len(ways) - 1 or row[k] >= tau else ways[-1]
    e.write_json('gate-route.json', out)
    from collections import Counter
    print(Counter(out.values()))


def idf() -> dict[str, float]:
    """Highest IDF of the question words over the 1,000 books' BM25 text (title + author + line + intro, as substrings
    like bm25word); 0 when no word stands in any book."""
    books = e.read_json('books.json')
    lines = e.lines_by_book()
    texts = [e.tidy(f"{b['title']} {b['author']} {lines.get(b['book_id'], '')} {b['intro']}") for b in books]
    n = len(texts)
    out = {}
    for q in e.read_json('queries.json'):
        best = 0.0
        for w in e.question_words(q['q']):
            w = e.tidy(w)
            k = sum(w in t for t in texts)
            if k:
                best = max(best, math.log(1 + (n - k + 0.5) / (k + 0.5)))
        out[q['id']] = best
    return out


def vector_orders() -> dict[str, list[int]]:
    qv = np.load(OUT / 'vec-queries.npy')
    both = np.maximum(e.book_scores('summary', qv), e.book_scores('chunk512', qv))
    order = np.argsort(-both, axis=1)[:, :e.VEC_DEPTH]
    return {q['id']: [int(i) for i in row] for q, row in zip(e.read_json('queries.json'), order)}


def features() -> dict[str, dict[str, dict[str, float]]]:
    sc = e.read_json('gate-bm25-scores.json')
    f = {'score': {}, 'ratio': {}, 'idf': idf()}
    for qid, rows in sc.items():
        s = [r[1] for r in rows]
        f['score'][qid] = s[0] if s else 0.0
        f['ratio'][qid] = (s[0] / s[min(9, len(s) - 1)]) if s else 0.0
    return f


def configs() -> list[dict]:
    """Every gate x threshold x cut, plus the base (no gate) and the two ends (BM25 always cut)."""
    qs = e.read_json('queries.json')
    f = features()
    out = [{'name': 'base (BM25 50 always)', 'topic': set()}]
    for cut in CUTS:
        out.append({'name': f'all cut {cut}', 'topic': {q['id'] for q in qs}, 'cut': cut})
    for feat, vals in f.items():
        xs = sorted(vals.values())
        for pct in PERCENTILES:
            thr = xs[len(xs) * pct // 100]
            topic = {qid for qid, v in vals.items() if v < thr}
            for cut in CUTS:
                out.append({'name': f'{feat} < {thr:.3g} (p{pct}) cut {cut}', 'topic': topic, 'cut': cut,
                            'gate': feat, 'thr': thr, 'pct': pct})
    if (OUT / 'gate-route.json').exists():
        r = e.read_json('gate-route.json')
        topic = {qid for qid, way in r.items() if way != 'title_like'}
        for cut in CUTS:
            out.append({'name': f'route not title_like cut {cut}', 'topic': topic, 'cut': cut, 'gate': 'route'})
    return out


def ordered(cfg: dict, vec: dict, kw: dict, qid: str) -> list[int]:
    depth = cfg.get('cut', e.KW_DEPTH) if qid in cfg['topic'] else e.KW_DEPTH
    return e.rrf(kw[qid][:depth], vec[qid])


def lists() -> tuple[list[dict], dict, dict]:
    vec = vector_orders()
    kw = {qid: [r[0] for r in rows] for qid, rows in e.read_json('gate-bm25-scores.json').items()}
    return configs(), vec, kw


def judge() -> None:
    """Grade the topic top-10 books no judge pool holds yet, with the experiment's prompt (one call per question)."""
    books = e.read_json('books.json')
    by_id = {b['book_id']: b for b in books}
    ids = [b['book_id'] for b in books]
    cfgs, vec, kw = lists()
    have = {}
    for q in e.read_json('queries.json'):
        if q['kind'] == 'topic':
            have[q['id']] = set(json.loads((OUT / 'judge-pools' / f"{q['id']}.json").read_text()))
    extra_dir = OUT / 'gate-judge-pools'
    extra_dir.mkdir(exist_ok=True)
    rng = random.Random(e.SEED)
    jobs = []
    for q in e.read_json('queries.json'):
        if q['kind'] != 'topic':
            continue
        new = sorted({ids[i] for c in cfgs for i in ordered(c, vec, kw, q['id'])[:10]} - have[q['id']])
        if not new:
            continue
        rng.shuffle(new)
        old = extra_dir / f"{q['id']}.json"
        if old.exists() and json.loads(old.read_text()) != new:
            raise SystemExit(f'{old} holds another pool')
        old.write_text(json.dumps(new))
        listing = '\n\n'.join(f"[{n + 1}] {e.head(by_id[b])}\n{by_id[b]['intro'][:e.JUDGE_INTRO_MAX]}"
                              for n, b in enumerate(new))
        jobs.append((q['id'], e.HAIKU, e.JUDGE_PROMPT.format(q=q['q'], books=listing), 1500))
    print(len(jobs), 'questions,', sum(len(json.loads((extra_dir / f'{j[0]}.json').read_text())) for j in jobs),
          'new books')
    e.run_jobs('gate-judge.jsonl', jobs)


def all_grades() -> dict[str, dict[int, int]]:
    import re
    g = e.grades()
    path = OUT / 'gate-judge.jsonl'
    if path.exists():
        for key, text in e.jsonl('gate-judge.jsonl').items():
            pool = json.loads((OUT / 'gate-judge-pools' / f'{key}.json').read_text())
            m = re.search(r'\{.*\}', text, re.S)
            got = json.loads(m.group(0)) if m else {}
            g.setdefault(key, {}).update({b: int(got[str(n + 1)]) for n, b in enumerate(pool) if str(n + 1) in got})
    return g


def report() -> None:
    books = e.read_json('books.json')
    ids = [b['book_id'] for b in books]
    where = {b: i for i, b in enumerate(ids)}
    qs = e.read_json('queries.json')
    known = [q for q in qs if q['kind'] in e.KNOWN]
    topic = [q for q in qs if q['kind'] == 'topic']
    g = all_grades()
    cfgs, vec, kw = lists()
    rows = []
    for c in cfgs:
        rk = {}
        for q in known:
            o = ordered(c, vec, kw, q['id'])
            i = where[q['book_id']]
            rk[q['id']] = o.index(i) + 1 if i in o else len(ids) + 1
        per = {}
        for kind in e.KNOWN + ('all',):
            xs = [rk[q['id']] for q in known if kind == 'all' or q['kind'] == kind]
            per[kind] = {'mrr': round(sum(1 / x for x in xs) / len(xs), 3),
                         'r1': round(sum(x <= 1 for x in xs) / len(xs), 3),
                         'r10': round(sum(x <= 10 for x in xs) / len(xs), 3),
                         'r30': round(sum(x <= 30 for x in xs) / len(xs), 3)}
        p10, unjudged = [], 0
        for q in topic:
            got = [g.get(q['id'], {}).get(ids[i]) for i in ordered(c, vec, kw, q['id'])[:10]]
            unjudged += sum(x is None for x in got)
            p10.append(sum((x or 0) >= 2 for x in got) / 10)
        rows.append({'name': c['name'], 'gate': c.get('gate'), 'cut': c.get('cut'), 'thr': c.get('thr'),
                     'pct': c.get('pct'), 'known': per, 'topic_p10': round(sum(p10) / len(p10), 3),
                     'topic_unjudged': unjudged,
                     'as_topic_known': sum(q['id'] in c['topic'] for q in known),
                     'as_topic_topic': sum(q['id'] in c['topic'] for q in topic),
                     'as_topic_known_by_kind': {k: sum(q['id'] in c['topic'] for q in known if q['kind'] == k)
                                                for k in e.KNOWN}})
    e.write_json('gate-report.json', rows)
    for r in rows:
        print(f"{r['name']:38s} known mrr {r['known']['all']['mrr']:.3f} r30 {r['known']['all']['r30']:.3f} "
              f"niche mrr {r['known']['niche']['mrr']:.3f} | topic p10 {r['topic_p10']:.3f} "
              f"(unjudged {r['topic_unjudged']}) | as topic {r['as_topic_known']}/800 {r['as_topic_topic']}/57")


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    steps = {'scores': scores, 'route': route, 'judge': judge, 'report': report}
    step = sys.argv[1] if len(sys.argv) > 1 else ''
    if step not in steps:
        raise SystemExit('step: ' + ' | '.join(steps))
    steps[step]()
