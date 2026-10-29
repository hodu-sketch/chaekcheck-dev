"""pgvector benchmark for docs/aisearch/reports/2026-10-29-hnsw.md 3절, against a THROWAWAY container (never the
project's db / db-test): see run_pg_bench.sh.

Loads book_vec(id bigint, embedding vector(1024)) from the binary COPY file (make_copy_file.py), makes book_half
(halfvec(1024)), then per table: exact seq scan, then per HNSW setting: build time, sizes, and per query
(the questions in queries.f32, NearestBench encode) client time, EXPLAIN ANALYZE execution time and recall@k against
the server's own top 120 (brute-gt.tsv, NearestBench bench).
usage: python pg_bench.py DSN DATA_DIR COPY_PATH_IN_CONTAINER [--quick]
"""
import json, struct, sys, time
import psycopg

dsn, data, copy_path = sys.argv[1], sys.argv[2], sys.argv[3]
quick = '--quick' in sys.argv
LIMIT = 120

raw = open(f'{data}/queries.f32', 'rb').read()
nq, dim = struct.unpack_from('<ii', raw, 0)
queries = [struct.unpack_from(f'<{dim}f', raw, 8 + i * dim * 4) for i in range(nq)]
qtext = ['[' + ','.join(f'{x:.7g}' for x in q) + ']' for q in queries]
gt = {}
for line in open(f'{data}/brute-gt.tsv'):
    i, ids = line.rstrip('\n').split('\t')
    gt[int(i)] = [int(x) for x in ids.split(',')]
if quick:
    qtext = qtext[:40]

results = {'queries': len(qtext), 'runs': []}
out_path = f'{data}/pg-results.json'


def pct(xs, p):
    a = sorted(xs)
    return a[min(len(a) - 1, max(0, int(-(-p * len(a) // 100)) - 1))]


def summary(xs):
    return {'median': round(pct(xs, 50), 2), 'p90': round(pct(xs, 90), 2), 'p99': round(pct(xs, 99), 2),
            'max': round(max(xs), 2)}


def save():
    json.dump(results, open(out_path, 'w'), indent=1)


conn = psycopg.connect(dsn, autocommit=True)
cur = conn.cursor()


def sql(s, *a):
    cur.execute(s, *a)
    return cur.fetchall() if cur.description else None


def sizes(table, index=None):
    r = {'table_bytes': sql(f"select pg_relation_size('{table}')")[0][0],
         'table_total_bytes': sql(f"select pg_total_relation_size('{table}')")[0][0]}
    if index:
        r['index_bytes'] = sql(f"select pg_relation_size('{index}')")[0][0]
    return r


def load():
    sql('create extension if not exists vector')
    results['pgvector'] = sql("select extversion from pg_extension where extname='vector'")[0][0]
    results['postgres'] = sql('show server_version')[0][0]
    sql('drop table if exists book_vec, book_half')
    sql('create table book_vec(id bigint primary key, embedding vector(1024) not null)')
    t = time.perf_counter()
    sql(f"copy book_vec from '{copy_path}' (format binary)")
    load_s = time.perf_counter() - t
    t = time.perf_counter()
    sql('create table book_half as select id, embedding::halfvec(1024) as embedding from book_vec')
    sql('alter table book_half add primary key (id)')
    half_s = time.perf_counter() - t
    sql('vacuum analyze book_vec')
    sql('vacuum analyze book_half')
    results['load'] = {'rows': sql('select count(*) from book_vec')[0][0], 'copy_s': round(load_s, 1),
                       'halfvec_s': round(half_s, 1), 'book_vec': sizes('book_vec'), 'book_half': sizes('book_half')}
    save()
    print('loaded', results['load'], flush=True)


def run_queries(table, op, k, label, settings):
    """One warm-up pass, then each query once: client wall time (psycopg over TCP), then EXPLAIN ANALYZE time."""
    cast = 'vector' if table == 'book_vec' else 'halfvec'
    q = f'select id from {table} order by embedding {op} %s::{cast}(1024) limit {k}'
    for s in settings:
        sql(s)
    for v in qtext[:20]:
        sql(q, (v,))
    client, server, rec, got_n = [], [], [], []
    for i, v in enumerate(qtext):
        t = time.perf_counter()
        ids = [r[0] for r in sql(q, (v,))]
        client.append((time.perf_counter() - t) * 1000)
        want = set(gt[i][:k])
        rec.append(len(want & set(ids)) / len(want))
        got_n.append(len(ids))
    for v in qtext:
        plan = sql('explain (analyze, format json) ' + q, (v,))[0][0][0]
        server.append(plan['Execution Time'])
    plan_node = plan['Plan']
    while plan_node.get('Plans'):
        plan_node = plan_node['Plans'][0]
    for s in settings:
        sql('reset ' + s.split()[1].split('=')[0]) if s.startswith('set ') else None
    r = {'label': label, 'table': table, 'k': k, 'settings': settings, 'client_ms': summary(client),
         'server_ms': summary(server), 'recall_mean': round(sum(rec) / len(rec), 4), 'recall_min': round(min(rec), 4),
         'rows_returned_min': min(got_n), 'rows_returned_mean': round(sum(got_n) / len(got_n), 1),
         'scan_node': plan_node.get('Node Type'), 'index': plan_node.get('Index Name')}
    results['runs'].append(r)
    save()
    print(json.dumps(r, ensure_ascii=False), flush=True)


def bench_table(table, opclass):
    # exact: no index on the vector column, the planner scans the table (parallel workers as configured)
    for k in (LIMIT, 10):
        run_queries(table, '<#>', k, 'exact seq scan', [])
    builds = [(16, 64)] if quick else [(32, 128), (16, 64)]  # m16 last: kept with --keep for the memory test
    for m, efc in builds:
        idx = f'{table}_hnsw_m{m}_ef{efc}'
        sql('set maintenance_work_mem = \'1GB\'')
        t = time.perf_counter()
        sql(f'create index {idx} on {table} using hnsw (embedding {opclass}) with (m = {m}, ef_construction = {efc})')
        build_s = time.perf_counter() - t
        sql('reset maintenance_work_mem')
        b = {'table': table, 'index': idx, 'm': m, 'ef_construction': efc, 'build_s': round(build_s, 1),
             **sizes(table, idx)}
        results.setdefault('builds', []).append(b)
        save()
        print('built', b, flush=True)
        for k in (LIMIT, 10):
            for ef in (40, 100, 200, 400):
                run_queries(table, '<#>', k, f'hnsw m{m} efc{efc} ef_search {ef}', [f'set hnsw.ef_search = {ef}'])
                if k == LIMIT and ef < LIMIT:
                    run_queries(table, '<#>', k, f'hnsw m{m} efc{efc} ef_search {ef} iterative',
                                [f'set hnsw.ef_search = {ef}', "set hnsw.iterative_scan = 'relaxed_order'"])
        if not ('--keep' in sys.argv and table == 'book_half' and m == 16):
            sql(f'drop index {idx}')


def round_trip():
    """What the client time holds besides the search: an empty query, and sending + parsing one query vector."""
    for label, q, arg in (('select 1', 'select 1', None),
                          ('send + parse vector(1024)', 'select %s::vector(1024) is not null', True),
                          ('send + parse halfvec(1024)', 'select %s::halfvec(1024) is not null', True)):
        ms = []
        for v in qtext:
            t = time.perf_counter()
            sql(q, (v,)) if arg else sql(q)
            ms.append((time.perf_counter() - t) * 1000)
        results.setdefault('round_trip', {})[label] = summary(ms)
        print('round trip', label, summary(ms), flush=True)
    save()


if __name__ == '__main__':
    load()
    round_trip()
    bench_table('book_vec', 'vector_ip_ops')
    bench_table('book_half', 'halfvec_ip_ops')
    print('done ->', out_path)
