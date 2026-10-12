"""Unit tests for the cycle 13 collector changes: library.vendor migration, blocked hosts, blocked sites.

Run from the repository root: python -m unittest scripts/crawl/test_crawl_migrate.py
No network: every test uses an in-memory SQLite DB or plain values.
"""
import sqlite3
import sys
import unittest
import urllib.error
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import crawl_catalog as c  # noqa: E402

# library table as it was before cycle 13 (no crawl_skip, no vendor), with one row
OLD_LIBRARY = """
CREATE TABLE library (id INTEGER PRIMARY KEY, name TEXT NOT NULL, platform_type TEXT NOT NULL, base_url TEXT NOT NULL,
  host TEXT NOT NULL, seed_holdings_count INTEGER, last_full_success_at TEXT, last_full_run_id INTEGER, source TEXT);
INSERT INTO library (id, name, platform_type, base_url, host) VALUES (1, 'a', 'YES24_V2', 'https://a.example', 'a.example');
"""


def columns(con, table):
    return [r[1] for r in con.execute(f"PRAGMA table_info({table})")]


def fresh(script=""):
    con = sqlite3.connect(":memory:")
    con.executescript(script)
    con.executescript(c.SCHEMA)
    return con


class VendorMigrationTests(unittest.TestCase):
    def test_new_db_has_vendor_and_crawl_skip(self):
        con = fresh()
        c.migrate(con)
        self.assertIn("vendor", columns(con, "library"))
        self.assertIn("crawl_skip", columns(con, "library"))

    def test_old_db_gets_vendor_and_keeps_rows(self):
        con = fresh(OLD_LIBRARY)
        self.assertNotIn("vendor", columns(con, "library"))
        c.migrate(con)
        self.assertIn("vendor", columns(con, "library"))
        self.assertEqual(con.execute("SELECT name, vendor FROM library WHERE id=1").fetchone(), ("a", None))

    def test_migrate_twice_is_harmless(self):
        con = fresh(OLD_LIBRARY)
        c.migrate(con)
        c.migrate(con)
        self.assertEqual(columns(con, "library").count("vendor"), 1)

    def test_vendor_check_rejects_unknown_values(self):
        for script in ("", OLD_LIBRARY):
            con = fresh(script)
            c.migrate(con)
            con.execute("INSERT OR REPLACE INTO library (id, name, platform_type, base_url, host, vendor) "
                        "VALUES (2, 'b', 'YES24_V2', 'https://b.example', 'b.example', 'YES24')")
            with self.assertRaises(sqlite3.IntegrityError):
                con.execute("UPDATE library SET vendor='ACME' WHERE id=2")


class BlockedHostTests(unittest.TestCase):
    def test_kyobo_subscription_hosts_are_blocked(self):
        self.assertTrue(c.is_blocked_host("https://gdc.dkyobobook.co.kr/content/contentList.ink"))
        self.assertTrue(c.is_blocked_host("http://dkyobobook.co.kr/"))
        self.assertTrue(c.is_blocked_host("https://X.DKYOBOBOOK.CO.KR"))
        self.assertFalse(c.is_blocked_host("https://elib.yjc.ac.kr:8091"))
        self.assertFalse(c.is_blocked_host("https://notdkyobobook.co.kr"))

    def test_fetch_retry_never_requests_a_blocked_host(self):
        with self.assertRaises(c.BlockedHost):
            c.fetch_retry("https://gdc.dkyobobook.co.kr/x", "dkyobobook.co.kr")


class BlockedSiteTests(unittest.TestCase):
    def wrapped(self, cause):
        try:
            raise RuntimeError("fetch failed") from cause
        except RuntimeError as e:
            return e

    def test_403_and_timeouts_count_as_blocked(self):
        http403 = urllib.error.HTTPError("https://a.example", 403, "Forbidden", {}, None)
        self.assertEqual(c.blocked_reason(self.wrapped(http403)), "HTTP 403")
        self.assertEqual(c.blocked_reason(self.wrapped(TimeoutError())), "time-out")
        self.assertEqual(c.blocked_reason(self.wrapped(urllib.error.URLError(TimeoutError()))), "time-out")

    def test_other_failures_do_not(self):
        http404 = urllib.error.HTTPError("https://a.example", 404, "Not Found", {}, None)
        self.assertIsNone(c.blocked_reason(self.wrapped(http404)))
        self.assertIsNone(c.blocked_reason(ValueError("parse")))

    def test_mark_blocked_skips_only_that_library(self):
        con = fresh(OLD_LIBRARY)
        c.migrate(con)
        con.execute("INSERT INTO library (id, name, platform_type, base_url, host) "
                    "VALUES (2, 'b', 'YES24_V2', 'https://b.example', 'b.example')")
        http403 = urllib.error.HTTPError("https://a.example", 403, "Forbidden", {}, None)
        c.mark_blocked(con, {"id": 1, "name": "a"}, self.wrapped(http403))
        skips = dict(con.execute("SELECT id, crawl_skip FROM library").fetchall())
        self.assertIn("HTTP 403", skips[1])
        self.assertIsNone(skips[2])


if __name__ == "__main__":
    unittest.main()
