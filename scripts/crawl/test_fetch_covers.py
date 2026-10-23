"""Tests for fetch_covers.py (book covers after the crawl, plan request 2026-10-23). No network: fake answers.

Run from the repository root: python -m unittest scripts/crawl/test_fetch_covers.py
"""
import io
import json
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import crawl_catalog as cc  # noqa: E402
import fetch_covers as fc  # noqa: E402
import recommended_lists as rl  # noqa: E402

PREFERRED = ('image.yes24.com', 'bookimg.bookcube.com')
SOURCES = ('collected_preferred', 'collected_other', 'nlk_isbn', 'nlk_holdings')
YES24 = 'https://image.yes24.com/goods/1/L'
CUBE = 'https://bookimg.bookcube.com/2.jpg'
OTHER = 'https://contents.dasangng.co.kr/3.jpg'
KYOBO = 'https://dcontent.dkyobobook.co.kr/4.jpg'


def png(color=(200, 30, 30), size=(300, 450)) -> bytes:
    """A cover-like picture: a gradient over the colour, so that its hash is not a solid picture's (all zero)."""
    from PIL import Image
    image = Image.new('RGB', size, color)
    pixels = image.load()
    for y in range(size[1]):
        for x in range(0, size[0], 7):
            pixels[x, y] = (x % 256, y % 256, (x * y) % 256)
    out = io.BytesIO()
    image.save(out, 'PNG')
    return out.getvalue()


def solid(color=(255, 255, 255), size=(300, 450)) -> bytes:
    """A "no image" picture: one colour (its hash is all zero, like every solid picture)."""
    from PIL import Image
    out = io.BytesIO()
    Image.new('RGB', size, color).save(out, 'PNG')
    return out.getvalue()


class FakeResponse:
    def __init__(self, status=200, body=b'', headers=None):
        self.status = status
        self.body = body
        self.headers = headers or {}

    def read(self, n=-1):
        return self.body if n is None or n < 0 else self.body[:n]

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FakeOpener:
    """url prefix -> FakeResponse, an Exception to raise, or a callable(url) -> FakeResponse; every URL is remembered."""

    def __init__(self, answers: dict):
        self.answers = answers
        self.urls = []
        self.lock = threading.Lock()

    def __call__(self, request, timeout=None, context=None):
        url = request.full_url
        with self.lock:
            self.urls.append(url)
        for prefix, answer in self.answers.items():
            if url.startswith(prefix):
                if isinstance(answer, Exception):
                    raise answer
                return answer(url) if callable(answer) else answer
        return FakeResponse(404)


def rules_with(placeholders=()):
    return rl.Rules((), (), (), (), (), PREFERRED, SOURCES, tuple(placeholders))


class CandidatesTests(unittest.TestCase):
    def test_preferred_hosts_first_then_others_then_nlk_never_kyobo(self):
        got = fc.candidates_of([OTHER, KYOBO, CUBE, YES24, YES24], PREFERRED, SOURCES, has_isbn=True, nlk=True)
        self.assertEqual([YES24, CUBE, OTHER, fc.NLK], got)

    def test_at_most_max_collected_addresses_and_nlk_still_last(self):
        urls = [f'https://lib{i}.example/c.jpg' for i in range(10)]
        got = fc.candidates_of(urls, PREFERRED, SOURCES, has_isbn=False, nlk=True)
        self.assertEqual(fc.MAX_COLLECTED + 1, len(got))
        self.assertEqual(fc.NLK, got[-1])
        self.assertEqual(urls[:fc.MAX_COLLECTED], got[:-1])

    def test_no_nlk_without_a_key_and_none_at_all_for_a_kyobo_only_book(self):
        self.assertEqual([OTHER], fc.candidates_of([OTHER], PREFERRED, SOURCES, has_isbn=True, nlk=False))
        self.assertEqual([], fc.candidates_of([KYOBO, 'not a url', ''], PREFERRED, SOURCES, has_isbn=False, nlk=False))
        # a Kyobo-only book without an ISBN still goes to the National Library (holdings search by title)
        self.assertEqual([fc.NLK], fc.candidates_of([KYOBO], PREFERRED, SOURCES, has_isbn=False, nlk=True))

    def test_sources_order_file_can_leave_the_national_library_out(self):
        got = fc.candidates_of([OTHER, YES24], PREFERRED, ('collected_preferred', 'collected_other'), True, True)
        self.assertEqual([YES24, OTHER], got)

    def test_book_key_is_the_isbn_else_the_first_holding(self):
        self.assertEqual('isbn:9788900000001', fc.book_key('9788900000001', (5, 'A1')))
        self.assertEqual('item:5:A1', fc.book_key(None, (5, 'A1')))

    def test_queue_key_puts_both_national_library_hosts_in_one_queue(self):
        self.assertEqual(fc.NLK_KEY, fc.queue_key_of('www.nl.go.kr'))
        self.assertEqual(fc.NLK_KEY, fc.queue_key_of('cover.nl.go.kr'))
        self.assertEqual(fc.NLK_KEY, fc.host_of(fc.NLK))

    def test_rules_files_in_the_repository_load(self):
        hosts, sources = fc.load_order()
        self.assertEqual(('image.yes24.com', 'bookimg.bookcube.com'), hosts)
        self.assertEqual(SOURCES, sources)
        self.assertNotIn('dcontent.dkyobobook.co.kr', hosts)


class DispatcherTests(unittest.TestCase):
    def test_one_server_at_a_time_with_the_gap_and_different_servers_together(self):
        clock = [100.0]
        key_of = {'a.example': '1.1.1.1', 'b.example': '1.1.1.1', 'c.example': '2.2.2.2'}.get
        d = fc.Dispatcher({'a.example': 1, 'b.example': 1, 'c.example': 1}, key_of=key_of, gap=1.0, clock=lambda: clock[0])
        first = d.take()
        second = d.take()
        # the two hosts on 1.1.1.1 never go together: the second taken host is c.example
        self.assertEqual({first, second}, {'a.example', 'c.example'})
        d.done('c.example', None)
        d.done(first, None)  # 1.1.1.1 is free, but only after the gap
        got = []

        def taker():
            got.append(d.take())
        t = threading.Thread(target=taker)
        t.start()
        time.sleep(0.2)
        self.assertEqual([], got, 'b.example must wait for the gap')
        clock[0] += 1.0
        with d.cond:
            d.cond.notify_all()
        t.join(timeout=2)
        self.assertEqual(['b.example'], got)
        d.done('b.example', None)
        self.assertIsNone(d.take())

    def test_a_book_moved_to_another_host_is_handed_out_and_an_off_server_is_left(self):
        d = fc.Dispatcher({'a.example': 1}, key_of=lambda h: h, gap=0.0)
        self.assertEqual('a.example', d.take())
        d.done('a.example', moved_to='b.example')
        self.assertEqual('b.example', d.take())
        d.done('b.example', moved_to='c.example')
        d.turn_off('c.example')
        self.assertIsNone(d.take())
        self.assertEqual(1, d.pending_total())


class FakeBooks:
    def __init__(self, rows):
        self.rows = rows

    def latest_import(self):
        return 'catalog_import 14 (test)'

    def books(self, limit=None):
        for row in self.rows[:limit]:
            yield row


def collected_db(rows):
    con = sqlite3.connect(':memory:')
    con.executescript(cc.SCHEMA)
    con.executemany("INSERT INTO holding (library_id, item_key, platform, title, cover_url, first_seen_at, last_seen_at, "
                    "last_run_id) VALUES (?, ?, 'X', 't', ?, '', '', 1)", rows)
    return con


class PlanAndFetchTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.data = Path(self.tmp.name)
        self.covers_path = self.data / 'covers' / 'covers.sqlite'
        self.log = fc.Log(None)
        self.collected = collected_db([(1, 'A', YES24), (2, 'B', CUBE), (3, 'C', OTHER), (4, 'D', KYOBO), (4, 'D2', KYOBO),
                                       (5, 'E', None)])

    def tearDown(self):
        self.tmp.cleanup()

    def plan(self, books, nlk=True):
        covers = fc.open_covers_db(self.covers_path)
        counts = fc.plan(covers, self.collected, FakeBooks(books), self.log, nlk)
        covers.close()
        return counts

    def rows(self):
        con = fc.open_covers_db(self.covers_path)
        try:
            return {r[0]: r for r in con.execute('SELECT book_key, book_id, status, source, cover_url, file, next_host, '
                                                 'candidates, last_error FROM book_cover')}
        finally:
            con.close()

    def fetch(self, opener, placeholders=(), workers=4, sleep=lambda s: None, **kw):
        http = fc.Http(self.log, ['SECRET-KEY'], opener=opener)
        fetcher = fc.Fetcher(self.data, self.covers_path, http, self.log, 'SECRET-KEY', rules_with(placeholders),
                             gap=0.0, sleep=sleep, **kw)
        return fetcher.run(workers), http

    def test_plan_rows_candidates_and_stable_keys(self):
        books = [(10, '9788900000001', '책 하나', '글쓴이', [(4, 'D'), (3, 'C'), (1, 'A')]),
                 (11, None, '책 둘', None, [(2, 'B'), (5, 'E')]),
                 (12, None, '교보만', None, [(4, 'D')]),
                 (13, None, '없음', None, [(5, 'E')])]
        counts = self.plan(books)
        self.assertEqual({'books': 4, 'new': 4, 'known': 0, 'no_candidates': 0}, counts)
        rows = self.rows()
        self.assertEqual([YES24, OTHER, fc.NLK], json.loads(rows['isbn:9788900000001'][7]))
        self.assertEqual('image.yes24.com', rows['isbn:9788900000001'][6])
        self.assertEqual([CUBE, fc.NLK], json.loads(rows['item:2:B'][7]))
        self.assertEqual([fc.NLK], json.loads(rows['item:4:D'][7]), 'Kyobo-only: the National Library by title')
        self.assertEqual([fc.NLK], json.loads(rows['item:5:E'][7]))
        # without a National Library key the Kyobo-only book has nothing to try
        self.tmp.cleanup()
        self.tmp = tempfile.TemporaryDirectory()
        self.data = Path(self.tmp.name)
        self.covers_path = self.data / 'covers' / 'covers.sqlite'
        counts = self.plan(books, nlk=False)
        self.assertEqual(2, counts['no_candidates'])
        self.assertEqual('NONE', self.rows()['item:4:D'][2])

    def test_fetch_saves_the_first_picture_skips_404_placeholder_and_over_5mb_then_none(self):
        self.plan([(10, None, '하나', None, [(1, 'A'), (3, 'C')]),      # yes24 404 -> other ok
                   (11, None, '둘', None, [(2, 'B')]),                  # bookcube placeholder -> NONE (no nlk)
                   (12, None, '셋', None, [(3, 'C')])], nlk=False)      # other ok (shared file with book 10)
        placeholder = solid()
        big = FakeResponse(200, png(), {'Content-Length': str(rl.COVER_MAX_BYTES + 1)})
        opener = FakeOpener({YES24: FakeResponse(404), CUBE: FakeResponse(200, placeholder), OTHER: FakeResponse(200, png())})
        result, http = self.fetch(opener, placeholders=[rl.image_hash(rl.shrink(placeholder))])
        rows = self.rows()
        self.assertEqual('DONE', rows['item:1:A'][2])
        self.assertEqual('collected_other', rows['item:1:A'][3])
        self.assertEqual(OTHER, rows['item:1:A'][4])
        self.assertTrue((self.data / rows['item:1:A'][5]).exists())
        self.assertTrue(rows['item:1:A'][5].startswith('covers/'))
        self.assertEqual('NONE', rows['item:2:B'][2])
        self.assertEqual('no image picture', rows['item:2:B'][8])
        self.assertEqual(rows['item:1:A'][5], rows['item:3:C'][5], 'the same address is one file')
        self.assertEqual(2, result['done'])
        self.assertEqual(1, result['none'])
        self.assertEqual({'collected_other': 2}, result['by_source'])
        # a picture over 5MB by Content-Length is not read
        self.collected.execute("INSERT INTO holding (library_id, item_key, platform, title, cover_url, first_seen_at, "
                               "last_seen_at, last_run_id) VALUES (3, 'C2', 'X', 't', ?, '', '', 1)", (OTHER + '?big',))
        opener2 = FakeOpener({OTHER + '?big': big})
        self.plan([(13, None, '넷', None, [(3, 'C2')])], nlk=False)
        self.fetch(opener2)
        self.assertEqual(1, len(opener2.urls))
        self.assertEqual('NONE', self.rows()['item:3:C2'][2])
        self.assertEqual('over 5MB', self.rows()['item:3:C2'][8])

    def test_second_run_leaves_done_books_alone_and_a_replan_only_renumbers(self):
        self.plan([(10, '9788900000001', '하나', None, [(1, 'A')])])
        opener = FakeOpener({YES24: FakeResponse(200, png())})
        self.fetch(opener)
        self.assertEqual(1, len(opener.urls))
        # the catalogue was imported again: the same book is now id 77
        counts = self.plan([(77, '9788900000001', '하나', None, [(1, 'A')])])
        self.assertEqual(1, counts['known'])
        row = self.rows()['isbn:9788900000001']
        self.assertEqual((77, 'DONE'), (row[1], row[2]))
        self.fetch(opener)
        self.assertEqual(1, len(opener.urls), 'no request for a book that has its cover')

    def test_national_library_isbn_then_holdings_and_the_key_stays_out_of_errors(self):
        self.plan([(10, '9788900000001', '하나', '글쓴이', [(4, 'D')]),   # nlk: TITLE_URL
                   (11, None, '둘', '김작가', [(4, 'D')]),                # nlk: holdings by title + author
                   (12, None, '셋', None, [(4, 'D2')])])                  # nlk: no author -> nothing
        seoji = json.dumps({'docs': [{'TITLE_URL': 'http://image.kyobobook.co.kr/x.jpg'}]}).encode()
        holdings = json.dumps({'result': [{'titleInfo': '둘', 'authorInfo': '김작가', 'imageUrl': '/cov/2.jpg'}]}).encode()
        opener = FakeOpener({
            rl.SEOJI_API: FakeResponse(200, seoji),
            rl.NLK_SEARCH_API: lambda url: FakeResponse(200, holdings if 'kwd=' in url else b'{"result": []}'),
            'https://image.kyobobook.co.kr/x.jpg': FakeResponse(200, png()),
            rl.NLK_COVER_BASE + 'cov/2.jpg': FakeResponse(200, png((0, 0, 200))),
        })
        result, http = self.fetch(opener)
        rows = self.rows()
        self.assertEqual(('DONE', 'nlk_isbn', 'https://image.kyobobook.co.kr/x.jpg'), rows['isbn:9788900000001'][2:5])
        self.assertEqual(('DONE', 'nlk_holdings', rl.NLK_COVER_BASE + 'cov/2.jpg'), rows['item:4:D'][2:5])
        self.assertEqual('NONE', rows['item:4:D2'][2])
        self.assertIn('nlk_holdings: no imageUrl', rows['item:4:D2'][8])
        self.assertNotIn('SECRET-KEY', ' '.join(str(r[8]) for r in rows.values()))
        self.assertTrue(all('cert_key=SECRET-KEY' in u or 'key=SECRET-KEY' in u for u in opener.urls if 'nl.go.kr' in u
                            and 'cover.nl.go.kr' not in u), 'the API is called with the key')
        self.assertEqual({'nlk_isbn': 1, 'nlk_holdings': 1}, result['by_source'])

    def test_a_server_that_keeps_failing_is_left_with_its_books_pending(self):
        books = [(i, None, f'책{i}', None, [(1, f'A{i}')]) for i in range(fc.OFF_AFTER + 5)]
        self.collected.executemany("INSERT INTO holding (library_id, item_key, platform, title, cover_url, first_seen_at, "
                                   "last_seen_at, last_run_id) VALUES (1, ?, 'X', 't', ?, '', '', 1)",
                                   [(f'A{i}', f'{YES24}{i}') for i in range(fc.OFF_AFTER + 5)])
        self.plan(books, nlk=False)
        opener = FakeOpener({YES24: urllib.error.URLError('down')})
        result, _ = self.fetch(opener, workers=2)
        self.assertEqual(fc.OFF_AFTER, len(opener.urls))
        self.assertEqual(5, result['pending'])
        self.assertEqual(fc.OFF_AFTER, result['none'])

    def test_max_books_stops_a_trial_and_export_lists_the_pairs(self):
        self.collected.executemany("INSERT INTO holding (library_id, item_key, platform, title, cover_url, first_seen_at, "
                                   "last_seen_at, last_run_id) VALUES (1, ?, 'X', 't', ?, '', '', 1)",
                                   [(f'A{i}', YES24) for i in range(1, 6)])
        self.plan([(i, None, f'책{i}', None, [(1, f'A{i}')]) for i in range(1, 6)], nlk=False)
        # five books share one address: the same file, five rows
        opener = FakeOpener({YES24: FakeResponse(200, png())})
        result, _ = self.fetch(opener, workers=1, max_books=2)
        self.assertEqual(2, result['done'])
        con = fc.open_covers_db(self.covers_path)
        path = fc.export(con, self.data)
        con.close()
        lines = path.read_text(encoding='utf-8').splitlines()
        self.assertEqual('book_id\tbook_key\tisbn13\tstatus\tsource\tfile\tcover_url', lines[0])
        self.assertEqual(5, len(lines) - 1)
        self.assertEqual(2, sum(1 for line in lines[1:] if '\tDONE\t' in line))

    def test_blocked_host_is_never_requested_even_if_it_slipped_into_the_plan(self):
        con = fc.open_covers_db(self.covers_path)
        con.execute("INSERT INTO book_cover (book_key, book_id, candidates, next_host, status, planned_at) "
                    "VALUES ('item:9:Z', 9, ?, 'dcontent.dkyobobook.co.kr', 'PENDING', 'x')", (json.dumps([KYOBO]),))
        con.commit()
        con.close()
        opener = FakeOpener({KYOBO: FakeResponse(200, png())})
        self.fetch(opener, workers=1)
        self.assertEqual([], opener.urls)
        self.assertEqual('NONE', self.rows()['item:9:Z'][2])


if __name__ == '__main__':
    unittest.main()
