"""Unit tests for the integrated portal list reader (parse_portal) and PUBLISHER_FIRST_PORTAL_HOSTS (cycle 19 R3-1).

Run from the repository root: python -m unittest scripts/crawl/test_parse_portal.py
No network: the pages are trimmed copies in testdata/ (first 3 rows of one 'new e-books' list page each, fetched
once on 2026-10-16; the source URL is in each file's first comment).
"""
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import crawl_catalog as c  # noqa: E402


def read(name, base, menu):
    body = (HERE / "testdata" / f"portal-{name}.html").read_bytes()
    _, items = c.parse_portal(body, base, "EBK", menu)
    return [(i["title"], i["author_raw"], i["publisher"], i["published_on"]) for i in items]


class PublisherFirstPortalTests(unittest.TestCase):
    """div.info is 'publisher | author | date' on these three portals: the second field is the author."""

    def test_gyeonggi_office_of_education(self):
        self.assertEqual(read("goe", "https://lib.goe.go.kr/elib", "11"), [
            ("자도 자도 피곤한 당신을 위한 수면 처방", "이준용", "미래의창", "2026-08-03"),
            ("SKY 합격 생기부 공식", "윤윤구외 1명", "빅피시", "2026-07-29"),
            ("레몬나무의 바다", "마리아 돌로레스 아길라", "밝은미래", "2026-07-27")])

    def test_daegu(self):
        self.assertEqual(read("daegu", "https://library.daegu.go.kr/elib", "14"), [
            ("도깨비불 게스트하우스", "범유진", "모모", "2026-03-26"),
            ("세종의 나라 2", "김진명", "이타북스", "2026-04-23"),
            ("세종의 나라 1", "김진명", "이타북스", "2026-04-23")])

    def test_jeonbuk_office_of_education(self):
        self.assertEqual(read("jbe", "https://lib.jbe.go.kr/elib", "12"), [
            ("현미경으로 만난 우주암석 운석", "김환철 저", "별빛뜨락", "2025-07-03"),
            ("감정을 언어화하면 인생이 바뀐다", "김영호 저", "피닉스앤컴퍼니", "2025-07-28"),
            ("드디어 만나는 해부학 수업", "케빈 랭포드 저/안은미 역", "현대지성", "2025-06-30")])

    def test_hosts(self):
        self.assertEqual(c.PUBLISHER_FIRST_PORTAL_HOSTS, {"lib.goe.go.kr", "library.daegu.go.kr", "lib.jbe.go.kr"})


class AuthorFirstPortalTests(unittest.TestCase):
    """Every other portal is read as before: the first field is the author (AC-10)."""

    def test_busan_unchanged(self):
        self.assertEqual(read("busan", "https://library.busan.go.kr/elib", "14"), [
            ("최애는 부처님 - 오타쿠의 방구석 불교 탐구 생활", "비구니연습생(계소영) 지음", "불광출판사", "2026-07-29"),
            ("당신 곁의 한국 정원 - 철학, 문화, 역사가 수놓인 우리 정원 이야기", "신지선 지음", "수오서재",
             "2025-09-30"),
            ("동사의 힘 - 단 한 줄을 쓰더라도 우아하게", "사라 카우프먼 지음, 박혜원 옮김", "서스테인", "2026-09-03")])

    def test_order_follows_the_host_not_the_page(self):
        # the same page read for a host outside the list keeps the first field as the author
        rows = read("goe", "https://library.busan.go.kr/elib", "11")
        self.assertEqual(rows[0][1:3], ("미래의창", "이준용"))

    def test_other_fields_unchanged(self):
        body = (HERE / "testdata" / "portal-goe.html").read_bytes()
        _, items = c.parse_portal(body, "https://lib.goe.go.kr/elib", "EBK", "11")
        first = items[0]
        self.assertEqual(first["item_key"], "EBK:244356")
        self.assertEqual(first["supplier"], "교보문고")
        self.assertEqual(first["branch_name"], "경기도교육청통합도서관")
        self.assertEqual(first["detail_path"], "/module/elib/book/view.do?menu_idx=11&book_idx=244356&type=EBK")


if __name__ == "__main__":
    unittest.main()
