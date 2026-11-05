"""Step 6 of the two layers (user 2026-10-30 "6단계 그거 기억하고 계획대로 가자", docs/maintenance/6-aisearch.md 15):
scores the runs of AiSearchTwoLayerEvalTests (out-<mode>-<run>.json: per question the books in order, and ms) over every
AI search book with the plan libraries' filter.

  py scripts/aisearch/eval/two_layer_step6.py judge    # Haiku grades the topic (question, book) pairs no grade has yet
  py scripts/aisearch/eval/two_layer_step6.py report   # step6-report.json and the tables printed

Known questions (800, one asked book): only those whose book a plan library holds count (the others cannot be found
with the filter). candidates: in the first 10 / 30 and MRR; picks: picked, picked first. Topic questions (57): the
share of the first 10 (candidates) or of the picks graded 2 or 3 by Haiku (intro_text_eval.JUDGE_PROMPT, 0-3), with the
grades of the 2026-10-29 experiment reused. py = <data>/ml/venv/Scripts/python.exe -X utf8.
"""
from __future__ import annotations

import csv
import json
import random
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import intro_text_eval as e  # noqa: E402

DIR = e.PROJECTS / 'chaekcheck-dev' / 'data' / 'aisearch-two-layer-2026-10-31'
csv.field_size_limit(10_000_000)


def outs() -> dict[str, dict]:
    return {p.stem[4:]: json.loads(p.read_text(encoding='utf-8')) for p in sorted(DIR.glob('out-*.json'))}


def questions() -> list[dict]:
    return json.loads((DIR / 'questions.json').read_text(encoding='utf-8'))


def plan() -> set[int]:
    libs = json.loads((DIR / 'libs-1114.json').read_text(encoding='utf-8'))['libraries']
    return {x['id'] for x in libs if x['eligibility'] in ('ELIGIBLE', 'OPEN')}


def held() -> set[int]:
    """The search books a plan library holds."""
    p = plan()
    out = set()
    with (DIR / 'books.csv').open(encoding='utf-8', newline='') as f:
        for row in csv.reader(f):
            if row[3] and p & {int(x) for x in row[3].split(',')}:
                out.add(int(row[0]))
    return out


def old_grades() -> dict[str, dict[int, int]]:
    """The 2026-10-29 experiment's grades: judge, gate and one-call pools."""
    g = e.grades()
    for jsonl_name, pools in (('gate-judge.jsonl', 'gate-judge-pools'), ('onecall-judge.jsonl', 'onecall-judge-pools')):
        if not (e.OUT / jsonl_name).exists():
            continue
        for key, text in e.jsonl(jsonl_name).items():
            pool = json.loads((e.OUT / pools / f'{key}.json').read_text())
            m = re.search(r'\{.*\}', text, re.S)
            got = json.loads(m.group(0)) if m else {}
            g.setdefault(key, {}).update({b: int(got[str(n + 1)]) for n, b in enumerate(pool) if str(n + 1) in got})
    return g


def grades() -> dict[str, dict[int, int]]:
    g = old_grades()
    path = DIR / 'judge.jsonl'
    if path.exists():
        for row in map(json.loads, path.read_text(encoding='utf-8').splitlines()):
            pool = json.loads((DIR / 'judge-pools' / f"{row['key']}.json").read_text())
            qid = row['key'].split('#')[0]
            m = re.search(r'\{.*\}', row['text'], re.S)
            got = json.loads(m.group(0)) if m else {}
            g.setdefault(qid, {}).update({b: int(got[str(n + 1)]) for n, b in enumerate(pool) if str(n + 1) in got})
    return g


def shown(run: str, got: dict) -> list[int]:
    """What a topic question is graded on: the first 10 candidates, or the picks."""
    return got['books'][:10] if run.startswith('candidates') else got['books']


def judge() -> None:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    import build_chunk_vectors as chunks
    g = grades()
    want: dict[str, set[int]] = {}
    for run, out in outs().items():
        for q in questions():
            if q['kind'] == 'topic' and q['id'] in out:
                want.setdefault(q['id'], set()).update(b for b in shown(run, out[q['id']]) if b not in g.get(q['id'], {}))
    want = {k: v for k, v in want.items() if v}
    if not want:
        print('every pair has a grade')
        return
    intros = {int(r['book_id']): r['intro'] for r in chunks.read_tsv(chunks.ML / 'intro-books-100k.tsv')}
    heads = {int(t['book_id']): e.head(t) for t in chunks.read_tsv(chunks.DATA / 'aisearch' / '100k' / 'targets.tsv')}
    (DIR / 'judge-pools').mkdir(exist_ok=True)
    asked = {q['id']: q['q'] for q in questions()}
    rng = random.Random(e.SEED)
    jobs = []
    for qid, books in sorted(want.items()):
        n = 1 + sum(1 for p in (DIR / 'judge-pools').glob(f'{qid}#*.json'))
        pool = sorted(books)
        rng.shuffle(pool)
        key = f'{qid}#{n}'
        (DIR / 'judge-pools' / f'{key}.json').write_text(json.dumps(pool))
        listing = '\n\n'.join(f"[{k + 1}] {heads.get(b, str(b))}\n{intros.get(b, '')[:e.JUDGE_INTRO_MAX]}"
                              for k, b in enumerate(pool))
        jobs.append((key, e.HAIKU, e.JUDGE_PROMPT.format(q=asked[qid], books=listing), 1500))
    print(len(jobs), 'questions,', sum(len(v) for v in want.values()), 'books to grade')
    saved = e.OUT
    e.OUT = DIR
    try:
        e.run_jobs('judge.jsonl', jobs)
    finally:
        e.OUT = saved


def pct(xs: list[float]) -> float:
    return round(100 * sum(xs) / len(xs), 1) if xs else 0.0


def report() -> None:
    qs = questions()
    findable = held()
    g = grades()
    known = [q for q in qs if q['kind'] in e.KNOWN and q['book_id'] in findable]
    topic = [q for q in qs if q['kind'] == 'topic']
    rows = {}
    for run, out in outs().items():
        r = {'questions': len(out)}
        cand = run.startswith('candidates')
        for kind in e.KNOWN + ('all',):
            sub = [q for q in known if (kind == 'all' or q['kind'] == kind) and q['id'] in out]
            ranks = [out[q['id']]['books'].index(q['book_id']) + 1 if q['book_id'] in out[q['id']]['books'] else 0
                     for q in sub]
            if cand:
                r[kind] = {'n': len(sub), 'in10': pct([0 < x <= 10 for x in ranks]), 'in30': pct([x > 0 for x in ranks]),
                           'mrr': round(sum(1 / x for x in ranks if x) / max(1, len(ranks)), 3)}
            else:
                r[kind] = {'n': len(sub), 'picked': pct([x > 0 for x in ranks]), 'first': pct([x == 1 for x in ranks])}
        good, missing, empty = [], 0, 0
        for q in topic:
            books = shown(run, out.get(q['id'], {'books': []}))
            if not books:
                empty += 1
            for b in books:
                if b in g.get(q['id'], {}):
                    good.append(g[q['id']][b] >= 2)
                else:
                    missing += 1
        r['topic'] = {'good': pct(good), 'graded': len(good), 'missing': missing, 'no_books': empty}
        ms = sorted(v['ms'] for v in out.values())
        r['ms'] = {'p50': ms[len(ms) // 2], 'p95': ms[len(ms) * 95 // 100], 'max': ms[-1]}
        rows[run] = r
    (DIR / 'step6-report.json').write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding='utf-8')
    print(f'known questions a plan library holds: {len(known)} of {sum(1 for q in qs if q["kind"] in e.KNOWN)}; '
          f'plan libraries {len(plan())}')
    for run, r in rows.items():
        a = r['all']
        main = (f"in10 {a['in10']} in30 {a['in30']} mrr {a['mrr']}" if 'in10' in a
                else f"picked {a['picked']} first {a['first']}")
        print(f"{run:24} {main} | topic {r['topic']} | ms {r['ms']}")


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    {'judge': judge, 'report': report}[sys.argv[1]]()
