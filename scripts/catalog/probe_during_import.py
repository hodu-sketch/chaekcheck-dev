"""Calls the search and a book page again and again while an import runs, and records the database size (cycle 08
handoff step 8, AC-8, AC-21, disk).

Usage (from the repository root, the backend running, WSL with the compose db up):
  python scripts/catalog/probe_during_import.py <out dir> [interval seconds, default 1] [book id, default 1]

Writes <out dir>/probe.tsv (time, what, HTTP status or error, ms) and <out dir>/db-size.tsv (time, bytes, every 15 s)
until stopped with Ctrl+C or until <out dir>/stop exists. The first page of /api/v1/books?title=사랑 (no region) is
the AC-8 query; the book page is /api/v1/books/{id}. The same statements running all the time also make the
service's pooled connections use server-side prepared statements before the swap (AC-21).
"""
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = 'http://localhost:8080'
PSQL = ('wsl', '-d', 'Ubuntu', '--', 'bash', '/mnt/c/Users/yujun/Documents/Claude/Projects/chaekcheck-dev/data/cycle08/psql.sh',
        'db', '/mnt/c/Users/yujun/Documents/Claude/Projects/chaekcheck-dev/data/cycle08/q-dbsize.sql')


def call(url):
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(url, timeout=120) as response:
            response.read()
            status = str(response.status)
    except urllib.error.HTTPError as ex:
        status = str(ex.code)
    except Exception as ex:  # noqa: BLE001 - a broken connection is a result here
        status = type(ex).__name__
    return status, (time.perf_counter() - started) * 1000


def db_size():
    try:
        out = subprocess.run(PSQL, capture_output=True, text=True, timeout=60,
                             env=dict(os.environ, MSYS_NO_PATHCONV='1')).stdout
        return next(line.strip() for line in out.splitlines() if line.strip().isdigit())
    except Exception as ex:  # noqa: BLE001
        return type(ex).__name__


def main(out_dir, interval='1', book_id='1'):
    os.makedirs(out_dir, exist_ok=True)
    search = f'{BASE}/api/v1/books?{urllib.parse.urlencode({"title": "사랑"})}'
    book = f'{BASE}/api/v1/books/{book_id}'
    probe = open(f'{out_dir}/probe.tsv', 'a', encoding='utf-8', buffering=1)
    sizes = open(f'{out_dir}/db-size.tsv', 'a', encoding='utf-8', buffering=1)
    last_size = 0.0
    while not os.path.exists(f'{out_dir}/stop'):
        now = time.strftime('%H:%M:%S')
        for what, url in (('search', search), ('book', book)):
            status, ms = call(url)
            probe.write(f'{now}\t{what}\t{status}\t{ms:.0f}\n')
        if time.time() - last_size >= 15:
            sizes.write(f'{now}\t{db_size()}\n')
            last_size = time.time()
        time.sleep(float(interval))


if __name__ == '__main__':
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    main(*sys.argv[1:4])
