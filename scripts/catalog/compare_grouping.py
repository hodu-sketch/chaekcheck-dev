"""Compares two ways of grouping books (cycle 11 handoff step 8, ADR-0079): the same books, and the same ids.

Usage (from the repository root; Python standard library only):
  python scripts/catalog/compare_grouping.py dryrun <folder A> <folder B> [N]
      Two dry-run folders (chaekcheck.collection.import.dry-run-output): books.tsv.gz and rows.tsv.gz compared line
      by line. The books are numbered in the order they were given, so equal files mean the same books in the same
      order and every row in the same book.
  python scripts/catalog/compare_grouping.py ids <service> <folder> [schema, default catalog_next]
      After an export (mode export), writes the ids of catalog_next on the compose service (db or db-ops) into
      <folder>: book-ids.tsv.gz (id and content columns, by id) and holding-ids.tsv.gz (library_id, item_key, id,
      book_id, by library and item key). Run discard afterwards. With schema catalog: the swapped-in tables (an
      apply's result, to compare with the exporting PC's catalog_next).
  python scripts/catalog/compare_grouping.py ids-compare <folder A> <folder B> [N]
      Compares two such folders.
  python scripts/catalog/compare_grouping.py regroup <folder A> <folder B> [N]
      How the books changed from A to B (cycle 12 handoff step 7). A and B are each a dry-run folder or an ids folder,
      told apart by their file names. Rows are matched on (library, item key) found on both sides. Prints the rows
      only in A and only in B, the merged books (one B book <- several A books) and the split books (one A book ->
      several B books): how many, and the first N of each with title, author, publisher, ISBN and each side's book
      number (in a dry-run folder the number the dry run gave, in an ids folder the book id) and the rows they share.
      When both are ids folders, each listed split also says whether the B book that kept A's id is the part sharing
      the most rows with the A book (ADR-0062: each old book goes to the new book sharing most of its holdings).
      Memory: 12 bytes a slot for A's rows (1.5 to 3 slots a row, a power of two) and 5 bytes for every book number
      up to the highest of each side; files are read line by line (A's rows twice, B's rows twice, books twice).

Prints the number of differing lines and the first N (default 20) of them. Exit code 1 when anything differs
(regroup: when any book merged or split).
psql runs inside the container through WSL (docker compose -p chaekcheck exec -T <service> psql), as the user and
database in .env; no password is needed there (cycle 11 prototype 3).
"""
import gzip
import itertools
from array import array
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

BOOKS_SQL = ("COPY (SELECT id, title, author_display, publisher, published_on, isbn13, content_type, library_ids"
             " FROM {schema}.book ORDER BY id) TO STDOUT")
HOLDINGS_SQL = ("COPY (SELECT library_id, item_key, id, book_id FROM {schema}.holding"
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


def ids(service, folder, schema='catalog_next'):
    values = env()
    user, database = values['DB_USERNAME'], values['DB_NAME']
    os.makedirs(folder, exist_ok=True)
    for name, sql in (('book-ids.tsv.gz', BOOKS_SQL), ('holding-ids.tsv.gz', HOLDINGS_SQL)):
        command = ['wsl', '-d', 'Ubuntu', '--exec', 'docker', 'compose', '-p', 'chaekcheck', 'exec', '-T', service,
                   'psql', '-U', user, '-d', database, '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-c',
                   sql.format(schema=schema)]
        with gzip.open(os.path.join(folder, name), 'wb') as out:
            process = subprocess.Popen(command, stdout=subprocess.PIPE)
            for chunk in iter(lambda: process.stdout.read(1 << 20), b''):
                out.write(chunk)
            if process.wait() != 0:
                sys.exit(f'psql failed for {name}')
        print(f'{name} written')
    return 0


DRY_RUN = ('dry run', 'books.tsv.gz', 'rows.tsv.gz')
IDS = ('ids', 'book-ids.tsv.gz', 'holding-ids.tsv.gz')


def kind(folder):
    """The kind of a folder (DRY_RUN or IDS), from its file names."""
    for k in (DRY_RUN, IDS):
        if all(os.path.isfile(os.path.join(folder, name)) for name in k[1:]):
            return k
    sys.exit(f'{folder}: has neither books.tsv.gz and rows.tsv.gz (dry run) nor book-ids.tsv.gz and'
             ' holding-ids.tsv.gz (ids)')


def row_parts(line, holding):
    """(library and item key as one string, book number) of a rows.tsv.gz line (library, item key, book) or, with
    holding, of a holding-ids.tsv.gz line (library, item key, holding id, book id)."""
    parts = line.rsplit('\t', 2 if holding else 1)
    return parts[0], int(parts[-1])


class KeyTable:
    """(library, item key) -> book number for one side, in two flat arrays (open addressing, linear probing).

    A key is kept only as its 64-bit str hash (12 bytes a slot instead of a str, an int and a dict entry, about
    110 bytes a row). Two different keys share a hash with a chance of about n*n/2**65 (1e-5 for 17 million rows),
    which is ignored. Hashes differ between runs of Python but not within one."""

    def __init__(self, count):
        size = 16
        while size < count * 3 // 2:
            size <<= 1
        self.mask = size - 1
        self.hashes = array('q', bytes(8 * size))
        self.books = array('i', bytes(4 * size))

    def _slot(self, key):
        h = hash(key) or 1  # 0 marks an empty slot
        hashes, mask = self.hashes, self.mask
        i = h & mask
        while hashes[i] != 0 and hashes[i] != h:
            i = (i + 1) & mask
        return h, i

    def put(self, key, book):
        """False when the key is already there (the first book stays)."""
        h, i = self._slot(key)
        if self.hashes[i] == h:
            return False
        self.hashes[i] = h
        self.books[i] = book
        return True

    def get(self, key):
        """The book number, or 0 when the key is not there."""
        h, i = self._slot(key)
        return self.books[i] if self.hashes[i] == h else 0


def book_numbers(path):
    """(number of books, highest book number) of a books file."""
    count = highest = 0
    for line in lines(path):
        count += 1
        highest = max(highest, int(line.split('\t', 1)[0]))
    return count, highest


def book_info(path, wanted):
    """{book number: (title, author, publisher, ISBN)} for the wanted numbers. A COPY null (\\N) is shown empty."""
    info = {}
    for line in lines(path):
        fields = line.split('\t')
        number = int(fields[0])
        if number in wanted:
            info[number] = tuple('' if v == '\\N' else v for v in fields[1:4] + fields[5:6])
    return info


def regroup_result(folder_a, folder_b, limit=20):
    """Rows matched and books merged and split from A to B, as a dict (printed by regroup)."""
    kind_a, kind_b = kind(folder_a), kind(folder_b)
    books_a, highest_a = book_numbers(os.path.join(folder_a, kind_a[1]))
    books_b, highest_b = book_numbers(os.path.join(folder_b, kind_b[1]))
    if max(highest_a, highest_b) >= 1 << 31:
        sys.exit('a book number above 2**31 - 1 does not fit the arrays')
    rows_a_path, rows_b_path = os.path.join(folder_a, kind_a[2]), os.path.join(folder_b, kind_b[2])
    holding_a, holding_b = kind_a is IDS, kind_b is IDS

    rows_a = sum(1 for _ in lines(rows_a_path))
    table = KeyTable(rows_a)
    repeated_a = 0
    for line in lines(rows_a_path):
        if not table.put(*row_parts(line, holding_a)):
            repeated_a += 1

    # first pass over B: the first partner of each book; a different one later means a merge or a split
    first_b_of_a = array('i', bytes(4 * (highest_a + 1)))
    first_a_of_b = array('i', bytes(4 * (highest_b + 1)))
    split = bytearray(highest_a + 1)
    merged = bytearray(highest_b + 1)
    rows_b = matched = 0
    for line in lines(rows_b_path):
        rows_b += 1
        key, b = row_parts(line, holding_b)
        a = table.get(key)
        if a == 0:
            continue
        matched += 1
        seen = first_b_of_a[a]
        if seen == 0:
            first_b_of_a[a] = b
        elif seen != b:
            split[a] = 1
        seen = first_a_of_b[b]
        if seen == 0:
            first_a_of_b[b] = a
        elif seen != a:
            merged[b] = 1
    merged_count, split_count = merged.count(1), split.count(1)
    listed_merged = list(itertools.islice((b for b in range(highest_b + 1) if merged[b]), limit))
    listed_split = list(itertools.islice((a for a in range(highest_a + 1) if split[a]), limit))
    del first_b_of_a, first_a_of_b, split, merged

    # second pass over B, only for the listed books: the rows each (A book, B book) pair shares
    parts_of_b = {b: {} for b in listed_merged}
    parts_of_a = {a: {} for a in listed_split}
    if parts_of_b or parts_of_a:
        for line in lines(rows_b_path):
            key, b = row_parts(line, holding_b)
            a = table.get(key)
            if a == 0:
                continue
            parts = parts_of_b.get(b)
            if parts is not None:
                parts[a] = parts.get(a, 0) + 1
            parts = parts_of_a.get(a)
            if parts is not None:
                parts[b] = parts.get(b, 0) + 1
    del table

    wanted_a, wanted_b = set(listed_split), set(listed_merged)
    for parts in parts_of_b.values():
        wanted_a.update(parts)
    for parts in parts_of_a.values():
        wanted_b.update(parts)
    splits = []
    for a in listed_split:
        parts = parts_of_a[a]
        entry = {'a': a, 'parts': sorted(parts.items())}
        if holding_a and holding_b:
            # ADR-0062: the A id goes to the B book sharing most of A's holdings, unless that B book keeps a
            # smaller id of another A book; so the part with A's id should share the most
            most = max(parts.values())
            kept = parts.get(a)
            entry['heir'] = {'shared': kept, 'most': most, 'is_most': kept == most,
                             'tie': kept == most and list(parts.values()).count(most) > 1}
        splits.append(entry)
    return {
        'kind_a': kind_a[0], 'kind_b': kind_b[0], 'books_a': books_a, 'books_b': books_b,
        'rows_a': rows_a, 'rows_b': rows_b, 'repeated_a': repeated_a, 'matched': matched,
        'only_a': rows_a - repeated_a - matched, 'only_b': rows_b - matched,
        'merged': merged_count, 'split': split_count,
        'merges': [{'b': b, 'parts': sorted(parts_of_b[b].items())} for b in listed_merged],
        'splits': splits,
        'info_a': book_info(os.path.join(folder_a, kind_a[1]), wanted_a),
        'info_b': book_info(os.path.join(folder_b, kind_b[1]), wanted_b),
    }


def regroup(folder_a, folder_b, limit=20):
    r = regroup_result(folder_a, folder_b, limit)
    name_a = 'A id' if r['kind_a'] == 'ids' else 'A no.'
    name_b = 'B id' if r['kind_b'] == 'ids' else 'B no.'

    def book(name, number, info, shared):
        title, author, publisher, isbn = info.get(number, ('?', '?', '?', '?'))
        print(f'    {name} {number}\t{title}\t{author}\t{publisher}\t{isbn}\tshared rows {shared}')

    print(f"A: {folder_a} ({r['kind_a']}), {r['books_a']} books, {r['rows_a']} rows")
    print(f"B: {folder_b} ({r['kind_b']}), {r['books_b']} books, {r['rows_b']} rows")
    if r['repeated_a']:
        print(f"  A has {r['repeated_a']} rows whose (library, item key) came before; the first one counts")
    print(f"rows on both sides: {r['matched']}, only in A: {r['only_a']}, only in B: {r['only_b']}")
    print(f"merged: {r['merged']} B books, each from several A books")
    for m in r['merges']:
        print(f"  {name_b} {m['b']} <- {name_a} {', '.join(str(a) for a, _ in m['parts'])}")
        for a, shared in m['parts']:
            book(name_a, a, r['info_a'], shared)
    print(f"split: {r['split']} A books, each into several B books")
    for s in r['splits']:
        note = ''
        heir = s.get('heir')
        if heir is not None:
            if heir['shared'] is None:
                note = " | no part kept the A id"
            else:
                verdict = 'yes, tied' if heir['tie'] else 'yes' if heir['is_most'] else 'NO'
                note = (f" | B id {s['a']} kept the A id and shares {heir['shared']} rows, the most is"
                        f" {heir['most']}: {verdict}")
        print(f"  {name_a} {s['a']} -> {name_b} {', '.join(str(b) for b, _ in s['parts'])}{note}")
        for b, shared in s['parts']:
            book(name_b, b, r['info_b'], shared)
    changed = r['merged'] + r['split']
    print('same grouping' if changed == 0 else f"DIFFERENT ({r['merged']} merged, {r['split']} split)")
    return 1 if changed else 0


def main(args):
    sys.stdout.reconfigure(encoding='utf-8')
    if len(args) >= 3 and args[0] == 'dryrun':
        return compare(args[1], args[2], ['books.tsv.gz', 'rows.tsv.gz'], int(args[3]) if len(args) > 3 else 20)
    if len(args) in (3, 4) and args[0] == 'ids':
        return ids(args[1], args[2], *args[3:])
    if len(args) >= 3 and args[0] == 'ids-compare':
        return compare(args[1], args[2], ['book-ids.tsv.gz', 'holding-ids.tsv.gz'],
                       int(args[3]) if len(args) > 3 else 20)
    if len(args) in (3, 4) and args[0] == 'regroup':
        return regroup(args[1], args[2], int(args[3]) if len(args) > 3 else 20)
    print(__doc__)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
