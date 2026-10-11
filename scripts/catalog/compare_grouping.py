"""Compares two ways of grouping books (cycle 11 handoff step 8, ADR-0079): the same books, and the same ids.

Usage (from the repository root; Python standard library only):
  python scripts/catalog/compare_grouping.py dryrun <folder A> <folder B> [N]
      Two dry-run folders (chaekcheck.collection.import.dry-run-output): books.tsv.gz and rows.tsv.gz compared line
      by line. The books are numbered in the order they were given, so equal files mean the same books in the same
      order and every row in the same book.
  python scripts/catalog/compare_grouping.py ids <service> <folder>
      After an export (mode export), writes the ids of catalog_next on the compose service (db or db-ops) into
      <folder>: book-ids.tsv.gz (id and content columns, by id) and holding-ids.tsv.gz (library_id, item_key, id,
      book_id, by library and item key). Run discard afterwards.
  python scripts/catalog/compare_grouping.py ids-compare <folder A> <folder B> [N]
      Compares two such folders.

Prints the number of differing lines and the first N (default 20) of them. Exit code 1 when anything differs.
psql runs inside the container through WSL (docker compose -p chaekcheck exec -T <service> psql), as the user and
database in .env; no password is needed there (cycle 11 prototype 3).
"""
import gzip
import itertools
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

BOOKS_SQL = ("COPY (SELECT id, title, author_display, publisher, published_on, isbn13, content_type, library_ids"
             " FROM catalog_next.book ORDER BY id) TO STDOUT")
HOLDINGS_SQL = ("COPY (SELECT library_id, item_key, id, book_id FROM catalog_next.holding"
                " ORDER BY library_id, item_key COLLATE \"C\") TO STDOUT")


def env():
    values = {}
    with open(os.path.join(ROOT, '.env'), encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                name, value = line.split('=', 1)
                values[name] = value.strip().strip('"')
    return values


def lines(path):
    with gzip.open(path, 'rt', encoding='utf-8', newline='\n') as f:
        for line in f:
            yield line.rstrip('\n')


def compare_files(name, path_a, path_b, limit):
    differ = 0
    count = 0
    examples = []
    for number, (a, b) in enumerate(itertools.zip_longest(lines(path_a), lines(path_b)), start=1):
        count += 1
        if a != b:
            differ += 1
            if len(examples) < limit:
                examples.append((number, a, b))
    print(f'{name}: {count} lines, {differ} different')
    for number, a, b in examples:
        print(f'  line {number}')
        print(f'    A: {a}')
        print(f'    B: {b}')
    return differ


def compare(folder_a, folder_b, names, limit):
    differ = 0
    for name in names:
        differ += compare_files(name, os.path.join(folder_a, name), os.path.join(folder_b, name), limit)
    print('same' if differ == 0 else f'DIFFERENT ({differ} lines)')
    return 1 if differ else 0


def ids(service, folder):
    values = env()
    user, database = values['DB_USERNAME'], values['DB_NAME']
    os.makedirs(folder, exist_ok=True)
    for name, sql in (('book-ids.tsv.gz', BOOKS_SQL), ('holding-ids.tsv.gz', HOLDINGS_SQL)):
        command = ['wsl', '-d', 'Ubuntu', '--exec', 'docker', 'compose', '-p', 'chaekcheck', 'exec', '-T', service,
                   'psql', '-U', user, '-d', database, '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-c', sql]
        with gzip.open(os.path.join(folder, name), 'wb') as out:
            process = subprocess.Popen(command, stdout=subprocess.PIPE)
            for chunk in iter(lambda: process.stdout.read(1 << 20), b''):
                out.write(chunk)
            if process.wait() != 0:
                sys.exit(f'psql failed for {name}')
        print(f'{name} written')
    return 0


def main(args):
    sys.stdout.reconfigure(encoding='utf-8')
    if len(args) >= 3 and args[0] == 'dryrun':
        return compare(args[1], args[2], ['books.tsv.gz', 'rows.tsv.gz'], int(args[3]) if len(args) > 3 else 20)
    if len(args) == 3 and args[0] == 'ids':
        return ids(args[1], args[2])
    if len(args) >= 3 and args[0] == 'ids-compare':
        return compare(args[1], args[2], ['book-ids.tsv.gz', 'holding-ids.tsv.gz'],
                       int(args[3]) if len(args) > 3 else 20)
    print(__doc__)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
