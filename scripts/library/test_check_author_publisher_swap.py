"""Tests for check_author_publisher_swap.py on a small made-up collected copy (tables library, holding).

Run from the repository root: python -m unittest scripts/library/test_check_author_publisher_swap.py
"""
import contextlib
import io
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_author_publisher_swap as swap  # noqa: E402

# same columns as the collected copy (crawler schema); only some are filled here
SCHEMA = """
CREATE TABLE library (
  id INTEGER PRIMARY KEY, name TEXT NOT NULL, platform_type TEXT NOT NULL, base_url TEXT NOT NULL,
  host TEXT NOT NULL, seed_holdings_count INTEGER, last_full_success_at TEXT, last_full_run_id INTEGER,
  source TEXT, crawl_skip TEXT, vendor TEXT);
CREATE TABLE holding (
  library_id INTEGER NOT NULL REFERENCES library(id), item_key TEXT NOT NULL, platform TEXT NOT NULL,
  title TEXT NOT NULL, author_raw TEXT, publisher TEXT, published_on TEXT, published_raw TEXT, supplier TEXT,
  content_kind TEXT, category_code TEXT, category_name TEXT, cover_url TEXT, detail_path TEXT,
  first_seen_at TEXT NOT NULL, last_seen_at TEXT NOT NULL, removed_at TEXT, last_run_id INTEGER NOT NULL,
  branch_name TEXT, section TEXT, kind_source TEXT, content_type TEXT, is_audiobook INTEGER, kind_check TEXT,
  alt_code TEXT, detail_url TEXT, PRIMARY KEY (library_id, item_key));
"""

# (author, publisher) pairs of the reference (non-portal) libraries
BOOKS = [("<이준용> 저", "미래의창"), ("박소연 지음", "(주)메이븐"), ("김진명", "이타북스"),
         ("정용준 저자, 양민희 역", "은행나무"), ("조준호", "바른북스")]


class CheckTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "copy.sqlite")
        db = sqlite3.connect(self.path)
        db.executescript(SCHEMA)
        libraries = [(1, "교보도서관", "KYOBO_V1"), (2, "예스도서관", "YES24_V2"),
                     (10, "바른포털", "ELIB_PORTAL_V1"), (11, "뒤바뀐포털", "ELIB_PORTAL_V1"),
                     (12, "섞인포털", "ELIB_PORTAL_V1"), (13, "작은포털", "ELIB_PORTAL_V1"),
                     (14, "바뀐포털을흉내", "ELIB_PORTAL_V1")]
        db.executemany("INSERT INTO library (id, name, platform_type, base_url, host) VALUES (?, ?, ?, ?, ?)",
                       [(i, n, p, f"https://lib{i}.example.kr", f"lib{i}.example.kr") for i, n, p in libraries])
        rows = []

        def add(lid, author, publisher, removed=None):
            rows.append((lid, f"k{len(rows)}", "X", "제목", author, publisher, "t", "t", removed, 1))

        for lid in (1, 2):
            for author, publisher in BOOKS:
                for _ in range(3):
                    add(lid, author, publisher)
        # removed reference rows do not count: "유령" is used as a publisher only in removed rows
        for _ in range(9):
            add(1, "홍길동", "유령", removed="2026-10-01")
        # 10: correct (author first) + one unknown name
        for author, publisher in BOOKS * 30:
            add(10, author, publisher)
        add(10, "아무도모름", "어딘가")
        # 11: swapped (publisher in the author field); rows of 11 never count as reference
        for author, publisher in BOOKS * 30:
            add(11, publisher, author)
        add(11, "유령", "홍길동")
        # 12: half swapped -> mixed
        for i, (author, publisher) in enumerate(BOOKS * 40):
            add(12, *((publisher, author) if i % 2 else (author, publisher)))
        # 13: swapped but too few rows to decide
        for author, publisher in BOOKS:
            add(13, publisher, author)
        # 14: the swapped portal 11 must not vote: if portals counted, "미래의창" would look like an author here
        for author, publisher in BOOKS * 30:
            add(14, publisher, author)
        db.executemany("INSERT INTO holding (library_id, item_key, platform, title, author_raw, publisher, "
                       "first_seen_at, last_seen_at, removed_at, last_run_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                       rows)
        db.commit()
        db.close()

    def tearDown(self):
        swap.author_keys.cache_clear()
        swap.publisher_key.cache_clear()
        shutil.rmtree(self.dir)

    def test_verdicts_per_portal(self):
        result = {r[0]: r[3:] for r in swap.check(self.path)}
        self.assertEqual(set(result), {10, 11, 12, 13, 14})
        self.assertEqual(result[10], (swap.CORRECT, 0, 150, 1))
        self.assertEqual(result[11], (swap.SWAPPED, 150, 0, 1))
        self.assertEqual(result[12], (swap.UNDECIDED, 100, 100, 0))
        self.assertEqual(result[13], (swap.UNDECIDED, 5, 0, 0))
        self.assertEqual(result[14], (swap.SWAPPED, 150, 0, 0))

    def test_thresholds_are_options(self):
        result = {r[0]: r[3] for r in swap.check(self.path, min_decided=5, library_share=0.5)}
        self.assertEqual(result[13], swap.SWAPPED)
        self.assertEqual(result[12], swap.SWAPPED)  # 50 % reaches a 0.5 share, swapped is checked first

    def test_opens_read_only(self):
        os.chmod(self.path, 0o444)
        try:
            swap.check(self.path)
        finally:
            os.chmod(self.path, 0o644)

    def test_author_keys(self):
        self.assertEqual(swap.author_keys("고쿠분 고이치로 (지은이), 박영대 (옮긴이)"), ("고쿠분고이치로", "박영대"))
        self.assertEqual(swap.author_keys("저자명 : 아사쿠라 마야"), ("아사쿠라마야",))
        self.assertEqual(swap.author_keys("<이준용> 저"), ("이준용",))
        self.assertEqual(swap.author_keys("＜이현욱＞역"), ("이현욱",))
        self.assertEqual(swap.author_keys("윤윤구외 1명"), ("윤윤구",))
        self.assertEqual(swap.author_keys("케빈 랭포드 저/안은미 역"), ("케빈랭포드", "안은미"))
        self.assertEqual(swap.author_keys(None), ())
        self.assertEqual(swap.publisher_key("(주)미래의창"), "미래의창")
        self.assertEqual(swap.publisher_key("미래의 창"), "미래의창")

    def test_row_and_library_rules(self):
        self.assertEqual(swap.row_verdict(1, 1), swap.UNDECIDED)    # too rare
        self.assertEqual(swap.row_verdict(1, 9), swap.SWAPPED)
        self.assertEqual(swap.row_verdict(9, 1), swap.CORRECT)
        self.assertEqual(swap.row_verdict(5, 5), swap.UNDECIDED)    # used both ways
        self.assertEqual(swap.library_verdict(99, 0), swap.UNDECIDED)
        self.assertEqual(swap.library_verdict(95, 5), swap.SWAPPED)
        self.assertEqual(swap.library_verdict(1, 17500), swap.CORRECT)
        self.assertEqual(swap.library_verdict(50, 50), swap.UNDECIDED)

    def test_main_prints_table(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            swap.main([self.path])
        lines = out.getvalue().splitlines()
        self.assertTrue(lines[0].startswith("# thresholds: min-evidence 3"))
        self.assertIn("11\t뒤바뀐포털\tlib11.example.kr\t뒤바뀜\t150\t0\t1\t100.00", lines)


if __name__ == "__main__":
    unittest.main()
