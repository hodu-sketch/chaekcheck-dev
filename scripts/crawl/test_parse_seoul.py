"""Unit tests for the 서울도서관 category list reader (parse_seoul, library-maintenance 4-14).

Run from the repository root: python -m unittest scripts/crawl/test_parse_seoul.py
No network: testdata/seoul-catesearch-*.json are the first rows of one category page each, fetched once on 2026-10-20
(GET https://elib.seoul.go.kr/api/contents/catesearch?majorCategory=139|100&...&pageCount=5|3), descriptions and
cover addresses removed.
"""
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import crawl_catalog as c  # noqa: E402


def read(name, seg):
    return c.parse_seoul((HERE / "testdata" / name).read_bytes(), seg)


class SeoulCategoryPageTests(unittest.TestCase):

    def test_audiobook_rows(self):
        total, items = read("seoul-catesearch-ty03-139.json", ("TY03-139", "139"))
        self.assertEqual(total, 560)
        self.assertEqual([(i["item_key"], i["title"], i["author_raw"], i["publisher"], i["published_on"],
                           i["supplier"], i["content_kind"], i["detail_path"]) for i in items], [
            ("AD7033002", "기쁨", "안톤 체호프", "이북코리아", "2025-05-13", "오디언", "오디오북",
             "/contents/detail.do?no=AD7033002"),
            ("AD7033003", "끈 한조각", "기 드 모파상", "이북코리아", "2025-05-13", "오디언", "오디오북",
             "/contents/detail.do?no=AD7033003")])

    def test_overdrive_rows_are_skipped(self):
        total, items = read("seoul-catesearch-eb-100.json", ("EB-100", "100"))
        self.assertEqual(total, 3900)
        self.assertEqual(items, [])
        self.assertEqual(c.SKIPPED.rows, 2)

    def test_list_address(self):
        lib = {"base_url": "https://elib.seoul.go.kr", "platform_type": "SEOUL_ELIB_V1", "host": "elib.seoul.go.kr"}
        self.assertEqual(c.page_url(lib, 3, ("EB-001", "001")),
                         "https://elib.seoul.go.kr/api/contents/catesearch?libCode=&majorCategory=001&subCategory="
                         "&innerKeyword=&orderOption=1&loanable=&currentCount=3&pageCount=100")

    def test_an_error_page_is_not_a_list(self):
        with self.assertRaises(ValueError):
            c.parse_seoul(b"<!DOCTYPE html><html></html>", ("EB-001", "001"))

    def setUp(self):
        c.SKIPPED.rows = 0


if __name__ == "__main__":
    unittest.main()
