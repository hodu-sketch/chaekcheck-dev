"""pg_search BM25 with the plan-library filter at 100k / 1M rows (docs/aisearch/reports/2026-10-30-vector-scaling.md 10절).

The 1,000 experiment books' "title author line terms" repeated to N rows, each with 1-60 random library ids (like
catalog.book_search.library_ids), in the test container chaekcheck-pgsearch-test (intro_text_eval.PGSEARCH_DSN). Times
the question words of the experiment's queries: no filter, the filter as integer[] && (outside the BM25 index), and
the library ids as a whitespace text field in the index matched with boost 0 (filters without changing the score).

  <data>/ml/venv/Scripts/python.exe -X utf8 scripts/aisearch/eval/bm25_scale_eval.py ROWS [QUESTIONS]
"""
import json, random, statistics, sys, time
sys.path.insert(0, str(__import__('pathlib').Path(__file__).resolve().parent))
import intro_text_eval as e
import psycopg
books = e.read_json('books.json'); lines = e.lines_by_book(); words = e.jsonl('terms.jsonl')
base = [f"{b['title']} {b['author']} {lines.get(b['book_id'], '')} {words[str(b['book_id'])]}" for b in books]
qs = [q for q in (' '.join(e.question_words(q['q'])) for q in e.read_json('queries.json')) if q][:int(sys.argv[2]) if len(sys.argv) > 2 else 300]
rnd = random.Random(1)
plan = [3, 17, 42, 88, 120]
n = int(sys.argv[1])
def timeit(db, sql, args):
    for q in qs[:20]: db.execute(sql, args(q)).fetchall()
    ms = []
    for q in qs:
        s = time.perf_counter(); db.execute(sql, args(q)).fetchall(); ms.append((time.perf_counter() - s) * 1000)
    ms.sort(); return {'median': round(statistics.median(ms), 1), 'p90': round(ms[int(len(ms) * .9)], 1), 'max': round(ms[-1], 1)}
with psycopg.connect(e.PGSEARCH_DSN, autocommit=True) as db:
    t = f'filt_{n}'
    db.execute(f'DROP TABLE IF EXISTS {t}')
    db.execute(f'CREATE TABLE {t} (id bigint PRIMARY KEY, body text NOT NULL, libs integer[] NOT NULL, libtext text NOT NULL)')
    with db.cursor().copy(f'COPY {t} (id, body, libs, libtext) FROM STDIN') as cp:
        for i in range(n):
            l = sorted(rnd.sample(range(1, 151), rnd.randint(1, 60)))
            cp.write_row((i, base[i % len(base)], l, ' '.join(f'l{x}' for x in l)))
    out = {'rows': n}
    try:
        t0 = time.monotonic()
        db.execute(f"CREATE INDEX {t}_idx ON {t} USING paradedb (id, (body::pdb.lindera(korean)), libs, (libtext::pdb.whitespace))")
        out['index'] = 'body + libs (int[]) + libtext'
    except Exception as ex:
        out['libs_int_array_error'] = str(ex).split('\n')[0]
        db.execute(f"CREATE INDEX {t}_idx ON {t} USING paradedb (id, (body::pdb.lindera(korean)), (libtext::pdb.whitespace))")
        out['index'] = 'body + libtext'
    out['index_s'] = round(time.monotonic() - t0, 1)
    out['index_mb'] = round(db.execute(f"SELECT pg_relation_size('{t}_idx')").fetchone()[0] / 1048576, 1)
    out['no filter'] = timeit(db, f'SELECT id FROM {t} WHERE body ||| %s ORDER BY pdb.score(id) DESC LIMIT 50', lambda q: (q,))
    out['int[] && (not in index)' if 'libs_int_array_error' in out else 'int[] && (in index)'] = timeit(db, f'SELECT id FROM {t} WHERE body ||| %s AND libs && %s::integer[] ORDER BY pdb.score(id) DESC LIMIT 50', lambda q: (q, plan))
    lt = ' '.join(f'l{x}' for x in plan)
    try:
        for cast in ('pdb.boost(0)',):
            try:
                sql = f'SELECT id FROM {t} WHERE body ||| %s AND libtext ||| %s::{cast} ORDER BY pdb.score(id) DESC, id LIMIT 50'
                out[f'libtext ::{cast}'] = timeit(db, sql, lambda q: (q, lt))
                same = []
                for q in qs[:50]:
                    a = [r[0] for r in db.execute(f'SELECT id FROM {t} WHERE body ||| %s AND libs && %s::integer[] ORDER BY pdb.score(id) DESC, id LIMIT 50', (q, plan)).fetchall()]
                    b = [r[0] for r in db.execute(sql, (q, lt)).fetchall()]
                    same.append(a == b)
                out[f'libtext ::{cast} same order as int[] (50 queries)'] = sum(same)
            except Exception as ex:
                out[f'{cast}_error'] = str(ex).split(chr(10))[0]
        out['libtext ||| (in index)'] = timeit(db, f'SELECT id FROM {t} WHERE body ||| %s AND libtext ||| %s ORDER BY pdb.score(id) DESC LIMIT 50', lambda q: (q, lt))
        # check: same books as the int[] filter?
        q = qs[0]
        a = [r[0] for r in db.execute(f'SELECT id FROM {t} WHERE body ||| %s AND libs && %s::integer[] ORDER BY pdb.score(id) DESC, id LIMIT 50', (q, plan)).fetchall()]
        b = [r[0] for r in db.execute(f'SELECT id FROM {t} WHERE body ||| %s AND libtext ||| %s ORDER BY pdb.score(id) DESC, id LIMIT 50', (q, lt)).fetchall()]
        out['libtext same ids as int[] (first query)'] = len(set(a) & set(b))
    except Exception as ex:
        out['libtext_error'] = str(ex).split('\n')[0]
    plan_q = db.execute(f"EXPLAIN SELECT id FROM {t} WHERE body ||| 'a' AND libs && '{{3,17}}'::integer[] ORDER BY pdb.score(id) DESC LIMIT 50").fetchall()
    out['plan_int'] = ' | '.join(r[0].strip() for r in plan_q)[:400]
    print(json.dumps(out, ensure_ascii=False), flush=True)
    db.execute(f'DROP TABLE {t}')
