"""Tests for compare_grouping.py regroup on small made-up folders (cycle 12 handoff step 7).

Run from the repository root: python -m unittest scripts/catalog/test_compare_grouping.py
"""
import gzip
import io
import os
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import compare_grouping as grouping  # noqa: E402

# A dry run: book number, title, author, publisher, published date, ISBN, content type, libraries
DRY_BOOKS_A = [
    (1, '같은 책', '가', '갑출판', '2020-01-01', '9780000000001', 'EBOOK', '1'),
    (2, '합칠 책', '나', '을출판', '2021-01-01', '9780000000002', 'EBOOK', '1'),
    (3, '합칠 책', '나', '을출판', '2021-01-01', '', 'EBOOK', '2'),
    (4, '나눌 책', '다', '병출판', '2019-01-01', '9780000000004', 'EBOOK', '1,2'),
    (5, '한쪽 줄', '라', '정출판', '2018-01-01', '9780000000005', 'EBOOK', '1'),
]
# library, item key, book number
DRY_ROWS_A = [(1, 'k1', 1), (2, 'k2', 1), (1, 'k3', 2), (2, 'k4', 3), (1, 'k5', 4), (2, 'k6', 4), (2, 'k7', 4),
              (1, 'k8', 5), (1, 'k9', 5)]
DRY_BOOKS_B = [
    (1, '같은 책', '가', '갑출판', '2020-01-01', '9780000000001', 'EBOOK', '1'),
    (2, '합친 책', '나', '을출판', '2021-01-01', '9780000000002', 'EBOOK', '1,2'),
    (3, '나뉜 책 1', '다', '병출판', '2019-01-01', '9780000000004', 'EBOOK', '1,2'),
    (4, '나뉜 책 2', '다', '병출판', '2019-01-01', '9780000000044', 'EBOOK', '2'),
    (5, '한쪽 줄', '라', '정출판', '2018-01-01', '9780000000005', 'EBOOK', '1'),
]
DRY_ROWS_B = [(1, 'k1', 1), (2, 'k2', 1), (1, 'k3', 2), (2, 'k4', 2), (1, 'k5', 3), (2, 'k6', 3), (2, 'k7', 4),
              (1, 'k8', 5), (3, 'k10', 5)]

# ids: id, title, author, publisher, published date, ISBN, content type, library ids (COPY text, \N for null)
IDS_BOOKS_A = [
    (10, '같은 책', '가', '갑출판', '2020-01-01', '9780000000001', 'EBOOK', '{1}'),
    (11, '합칠 책', '나', '을출판', '2021-01-01', '9780000000002', 'EBOOK', '{1}'),
    (12, '합칠 책', '나', '을출판', '2021-01-01', '\\N', 'EBOOK', '{2}'),
    (13, '나눌 책', '다', '병출판', '2019-01-01', '9780000000004', 'EBOOK', '{1,2}'),
    (14, '덜 겹친 쪽이 번호를 받은 책', '마', '무출판', '2017-01-01', '9780000000006', 'EBOOK', '{1}'),
]
# library id, item key, holding id, book id
IDS_ROWS_A = [(1, 'k1', 100, 10), (2, 'k2', 101, 10), (1, 'k3', 102, 11), (2, 'k4', 103, 12),
              (1, 'k5', 104, 13), (2, 'k6', 105, 13), (2, 'k7', 106, 13),
              (1, 'k11', 107, 14), (1, 'k12', 108, 14), (1, 'k13', 109, 14), (1, 'k9', 110, 14)]
IDS_BOOKS_B = [
    (10, '같은 책', '가', '갑출판', '2020-01-01', '9780000000001', 'EBOOK', '{1}'),
    (11, '합친 책', '나', '을출판', '2021-01-01', '9780000000002', 'EBOOK', '{1,2}'),
    (13, '나뉜 책 1', '다', '병출판', '2019-01-01', '9780000000004', 'EBOOK', '{1,2}'),
    (14, '덜 겹친 책', '마', '무출판', '2017-01-01', '9780000000006', 'EBOOK', '{1}'),
    (20, '나뉜 책 2', '다', '병출판', '2019-01-01', '9780000000044', 'EBOOK', '{2}'),
    (21, '더 겹친 책', '마', '무출판', '2017-01-01', '9780000000066', 'EBOOK', '{1}'),
]
IDS_ROWS_B = [(1, 'k1', 100, 10), (2, 'k2', 101, 10), (1, 'k3', 102, 11), (2, 'k4', 103, 11),
              (1, 'k5', 104, 13), (2, 'k6', 105, 13), (2, 'k7', 106, 20),
              (1, 'k11', 107, 14), (1, 'k12', 108, 21), (1, 'k13', 109, 21), (3, 'k10', 111, 21)]


def write_gz(path, rows):
    with gzip.open(path, 'wt', encoding='utf-8', newline='\n') as f:
        for row in rows:
            f.write('\t'.join(str(v) for v in row) + '\n')


class RegroupTests(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.dir)

    def folder(self, name, files):
        path = os.path.join(self.dir, name)
        os.makedirs(path)
        for file, rows in files.items():
            write_gz(os.path.join(path, file), rows)
        return path

    def dry_runs(self):
        return (self.folder('a', {'books.tsv.gz': DRY_BOOKS_A, 'rows.tsv.gz': DRY_ROWS_A}),
                self.folder('b', {'books.tsv.gz': DRY_BOOKS_B, 'rows.tsv.gz': DRY_ROWS_B}))

    def ids(self):
        return (self.folder('a', {'book-ids.tsv.gz': IDS_BOOKS_A, 'holding-ids.tsv.gz': IDS_ROWS_A}),
                self.folder('b', {'book-ids.tsv.gz': IDS_BOOKS_B, 'holding-ids.tsv.gz': IDS_ROWS_B}))

    def test_dry_run_against_dry_run_finds_one_merge_and_one_split(self):
        r = grouping.regroup_result(*self.dry_runs())
        self.assertEqual(('dry run', 'dry run'), (r['kind_a'], r['kind_b']))
        self.assertEqual((9, 9, 8, 1, 1), (r['rows_a'], r['rows_b'], r['matched'], r['only_a'], r['only_b']))
        self.assertEqual((1, 1), (r['merged'], r['split']))
        self.assertEqual([{'b': 2, 'parts': [(2, 1), (3, 1)]}], r['merges'])
        self.assertEqual([{'a': 4, 'parts': [(3, 2), (4, 1)]}], r['splits'])
        self.assertEqual(('합칠 책', '나', '을출판', ''), r['info_a'][3])
        self.assertEqual(('나뉜 책 2', '다', '병출판', '9780000000044'), r['info_b'][4])

    def test_ids_against_ids_marks_whether_the_kept_id_shares_the_most(self):
        r = grouping.regroup_result(*self.ids())
        self.assertEqual((11, 11, 10, 1, 1), (r['rows_a'], r['rows_b'], r['matched'], r['only_a'], r['only_b']))
        self.assertEqual((1, 2), (r['merged'], r['split']))
        self.assertEqual([{'b': 11, 'parts': [(11, 1), (12, 1)]}], r['merges'])
        first, second = r['splits']
        self.assertEqual((13, [(13, 2), (20, 1)]), (first['a'], first['parts']))
        self.assertEqual({'shared': 2, 'most': 2, 'is_most': True, 'tie': False}, first['heir'])
        self.assertEqual((14, [(14, 1), (21, 2)]), (second['a'], second['parts']))
        self.assertEqual({'shared': 1, 'most': 2, 'is_most': False, 'tie': False}, second['heir'])
        self.assertEqual(('합칠 책', '나', '을출판', ''), r['info_a'][12])

    def test_same_folders_give_the_same_grouping(self):
        a, _ = self.dry_runs()
        out = io.StringIO()
        with redirect_stdout(out):
            code = grouping.regroup(a, a)
        self.assertEqual(0, code)
        self.assertIn('rows on both sides: 9, only in A: 0, only in B: 0', out.getvalue())
        self.assertIn('same grouping', out.getvalue())

    def test_printout_lists_the_books_and_exits_with_one(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = grouping.regroup(*self.ids(), limit=1)
        text = out.getvalue()
        self.assertEqual(1, code)
        self.assertIn('merged: 1 B books', text)
        self.assertIn('  B id 11 <- A id 11, 12', text)
        self.assertIn('    A id 12\t합칠 책\t나\t을출판\t\tshared rows 1', text)
        self.assertIn('split: 2 A books', text)
        self.assertIn('  A id 13 -> B id 13, 20 | B id 13 kept the A id and shares 2 rows, the most is 2: yes', text)
        self.assertNotIn('A id 14 ->', text)  # only the first N = 1
        self.assertIn('DIFFERENT (1 merged, 2 split)', text)

    def test_folder_kind_comes_from_the_file_names(self):
        a, _ = self.dry_runs()
        self.assertIs(grouping.DRY_RUN, grouping.kind(a))
        with self.assertRaises(SystemExit):
            grouping.kind(self.dir)


if __name__ == '__main__':
    unittest.main()
