"""One round of the memory measurement (cycle 08 handoff step 9, research.md 7): warm-up + 3 runs of measure_books.py,
first-page p50/p95, buffer hit ratio over the 3 runs, the largest relations in shared_buffers, holdingsOf plans with
buffers, and the container memory.

Usage (from the repository root; backend running, WSL with the compose db up, pg_buffercache created in db):
  python scripts/catalog/measure_round.py <out dir> <label>

Writes <out dir>/<label>/ (the runs, db.txt, docker.txt) and prints one summary line:
  label  p50  p95  hit%  container-memory
"""
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
WSL_ROOT = '/mnt/c/Users/yujun/Documents/Claude/Projects/chaekcheck-dev'
ENV = dict(os.environ, MSYS_NO_PATHCONV='1', PYTHONIOENCODING='utf-8')

DB_SQL = r"""
\pset pager off
SHOW shared_buffers; SHOW effective_cache_size; SHOW work_mem;
SELECT round(100.0 * sum(blks_hit) / nullif(sum(blks_hit + blks_read), 0), 2) AS hit_pct, sum(blks_hit) AS hit,
  sum(blks_read) AS read FROM pg_stat_database WHERE datname = current_database();
SELECT relname, heap_blks_read, heap_blks_hit, idx_blks_read, idx_blks_hit FROM pg_statio_user_tables
WHERE schemaname = 'catalog' ORDER BY heap_blks_read + heap_blks_hit DESC;
SELECT c.relname, count(*) AS buffers, pg_size_pretty(count(*) * 8192) AS size
FROM pg_buffercache b JOIN pg_class c ON b.relfilenode = pg_relation_filenode(c.oid)
  AND b.reldatabase = (SELECT oid FROM pg_database WHERE datname = current_database())
GROUP BY 1 ORDER BY 2 DESC LIMIT 10;
EXPLAIN (ANALYZE, BUFFERS) SELECT id, book_id, library_id, platform, detail_url, title_raw, author_raw, publisher_raw
FROM catalog.holding WHERE book_id = ANY(CAST('{189825}' AS bigint[])) ORDER BY book_id, library_id, id;
EXPLAIN (ANALYZE, BUFFERS) SELECT id, book_id, library_id, platform, detail_url, title_raw, author_raw, publisher_raw
FROM catalog.holding WHERE book_id = ANY(CAST('{1293,1668,1039}' AS bigint[])) ORDER BY book_id, library_id, id;
SELECT attname, correlation FROM pg_stats WHERE schemaname = 'catalog' AND tablename = 'holding'
  AND attname = 'book_id';
"""


def run(args, **kw):
    return subprocess.run(args, capture_output=True, text=True, env=ENV, cwd=ROOT, encoding='utf-8', **kw)


def psql(sql, out):
    path = os.path.join(ROOT, 'data', 'cycle08', 'round.sql')
    open(path, 'w', encoding='utf-8').write(sql)
    result = run(['wsl', '-d', 'Ubuntu', '--', 'bash', f'{WSL_ROOT}/data/cycle08/psql.sh', 'db',
                  f'{WSL_ROOT}/data/cycle08/round.sql'])
    if out:
        open(out, 'w', encoding='utf-8').write(result.stdout + result.stderr)
    return result.stdout


def main(out_dir, label):
    target = os.path.join(out_dir, label)
    os.makedirs(target, exist_ok=True)
    measure = [sys.executable, 'scripts/catalog/measure_books.py']
    run(measure + [os.path.join(target, 'warm')])
    psql('SELECT pg_stat_reset();', None)
    runs = []
    for n in (1, 2, 3):
        run(measure + [os.path.join(target, f'r{n}')])
        runs.append(os.path.join(target, f'r{n}'))
    summary = run([sys.executable, 'scripts/catalog/summarize_times.py'] + runs).stdout
    open(os.path.join(target, 'times.tsv'), 'w', encoding='utf-8').write(summary)
    db = psql(DB_SQL, os.path.join(target, 'db.txt'))
    docker = run(['wsl', '-d', 'Ubuntu', '--', 'docker', 'stats', '--no-stream', '--format',
                  '{{.Name}} {{.MemUsage}}', 'chaekcheck-db-1']).stdout.strip()
    open(os.path.join(target, 'docker.txt'), 'w', encoding='utf-8').write(docker + '\n')
    every = summary.strip().splitlines()[-1].split('\t')
    hit = next((line.split('|')[0].strip() for line in db.splitlines()
                if line.strip() and line.split('|')[0].strip().replace('.', '').isdigit()), '?')
    print(f'{label}\tp50 {every[2]}\tp95 {every[3]}\thit {hit}%\t{docker}')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    sys.stdout.reconfigure(encoding='utf-8')
    main(*sys.argv[1:3])
