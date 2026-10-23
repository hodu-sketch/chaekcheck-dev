"""Tests for holding.intro_raw: the book introduction as the LIST page carries it (plan request 2026-10-23, user decision
easy/2026-10-23-book-intro-decisions.html #1 "원문을 내 PC에만 저장").

Run from the repository root: python -m unittest scripts/crawl/test_parse_intro.py
No network: the pages are trimmed copies in testdata/ (first 2 rows of one list page each, fetched once on
2026-10-23; the source URL is in each file's first comment). Aladin's list has no introduction, the portal's and the
Seoul library's category list neither: those stay None (no detail page is opened for it).
"""
import sqlite3
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import crawl_catalog as c  # noqa: E402


def page(name):
    return (HERE / "testdata" / name).read_bytes()


class IntroTextTests(unittest.TestCase):
    def test_tags_stripped_line_breaks_kept_blank_lines_squeezed(self):
        from bs4 import BeautifulSoup
        el = BeautifulSoup("<li class='txt'>첫 줄 <b>굵게</b>\n\n\n  둘째 줄&nbsp;끝  \n</li>", "lxml").select_one("li")
        self.assertEqual("첫 줄 굵게\n둘째 줄 끝", c.intro(el))
        self.assertIsNone(c.intro(None))
        self.assertIsNone(c.intro(BeautifulSoup("<p>  </p>", "lxml").select_one("p")))

    def test_escaped_tags_in_the_text_are_stripped_but_not_angle_brackets_around_names(self):
        # YES24 V1 carries "&lt;br /&gt;" as text; a name in angle brackets is not a tag
        self.assertEqual("앞\n뒤 <이갑수> 저", c.clean_intro("앞<br />뒤 <이갑수> 저"))
        self.assertEqual("한 문단\n다른 문단", c.clean_intro("<p>한 문단</p><P class=x>다른 문단</P>"))
        self.assertIsNone(c.clean_intro(None))
        self.assertIsNone(c.clean_intro("<br>"))


class KyoboTests(unittest.TestCase):
    def test_li_txt_is_the_intro_with_its_paragraphs(self):
        total, items = c.parse_kyobo(page("kyobo-ghlib.html"), "https://www.ghlib.go.kr:446")
        self.assertEqual(5652, total)
        self.assertEqual(2, len(items))
        first = items[0]
        self.assertEqual(("4801175780096", "미국은 왜 전쟁을 멈추지 못하는가", "부키"),
                         (first["item_key"], first["title"], first["publisher"]))
        self.assertTrue(first["intro_raw"].startswith("1조 달러 전쟁 기계, 미국 군산복합체를 통해"))
        self.assertIn("\n국방부 연간 예산", first["intro_raw"], "paragraphs stay on their own lines")
        self.assertEqual(500, len(first["intro_raw"]), "the Kyobo list cuts the text at 500 characters")
        self.assertTrue(items[1]["intro_raw"].startswith("“다른 사람들을 신경 쓰느라"))


class Yes24Tests(unittest.TestCase):
    def test_v1_book_note(self):
        total, items = c.parse_yes24_v1(page("yes24v1-jeonju.html"), "http://ebook.jeonju.go.kr:8095")
        self.assertEqual(1598, total)
        self.assertEqual("#킬러스타그램", items[0]["title"])
        self.assertEqual("대대로 사람을 죽이는 킬러 가족이 있다면?『편협의 완성』 이갑수 작가의 첫 장편소설!여기 한 가족이 있다. "
                         "요리를 좋...", items[0]["intro_raw"])
        self.assertEqual("뼛속 ENTP인 블루베어의 코로나 이후 7일간 일본 관서 지방을 일주한 이야기.\n남들과 같은 방식...",
                         items[1]["intro_raw"], "the escaped <br /> in the text becomes a line break")

    def test_v2_p_txt(self):
        total, items = c.parse_yes24_v2(page("yes24v2-gangseo.html"), "https://ebook.gangseo.seoul.kr")
        self.assertEqual(20581, total)
        self.assertEqual("#마이네임", items[0]["title"])
        self.assertTrue(items[0]["intro_raw"].startswith("부모의 이혼으로 별안간 성이 바뀐 미온."))
        self.assertTrue(items[0]["intro_raw"].endswith("..."), "the list's shortened text, as it is")
        self.assertEqual("https://image.yes24.com/goods/117481004/M", items[0]["cover_url"])

    def test_v3_p_txt_euc_kr(self):
        total, items = c.parse_yes24_v2(page("yes24v3-mapo.html"), "http://maponaru.mapo.go.kr", "euc-kr")
        self.assertEqual(1752, total)
        self.assertEqual("100원이 필요해", items[0]["title"])
        self.assertTrue(items[0]["intro_raw"].startswith("선생님들께 100원이 모자란다고"))


class OtherPlatformTests(unittest.TestCase):
    def test_gangnam_book_intro(self):
        total, items = c.parse_gangnam(page("gangnam.html"), "https://ebook.gangnam.go.kr")
        self.assertEqual(6273, total)
        self.assertEqual(("KB008456", "아코디언", "천명관"), (items[0]["item_key"], items[0]["title"], items[0]["author_raw"]))
        self.assertTrue(items[0]["intro_raw"].startswith("10년의 기다림을 깨고, 마침내 천명관의"))
        self.assertTrue(items[1]["intro_raw"].startswith("소설가 조남주의 신작 장편소설 『재이』"))

    def test_bookcube_long_explain_not_the_table_of_contents(self):
        total, items = c.parse_bookcube(page("bookcube-kb.xml"), "https://kbsmalllibrary.bookcube.biz/FxLibrary")
        self.assertEqual(5538, total)
        self.assertEqual("190700059", items[0]["item_key"])
        self.assertTrue(items[0]["intro_raw"].startswith("소프트웨어 교육 왜 해야 할까요?\n2019년부터"))
        self.assertNotIn("1일째 파이썬을 배우기 전에", items[0]["intro_raw"], "<contents> (목차) is not stored")
        self.assertTrue(items[1]["intro_raw"].startswith("(김홍도)금강사군첩"), "the site's own text, as it is")

    def test_bookcube_falls_back_to_short_explain(self):
        xml = (b"<r><totalItem>1</totalItem><Product><num>1</num><name><![CDATA[t]]></name>"
               b"<shortExplain><![CDATA[short <b>one</b>]]></shortExplain></Product></r>")
        _, items = c.parse_bookcube(xml, "https://x")
        self.assertEqual("short one", items[0]["intro_raw"])

    def test_aladin_list_has_no_intro(self):
        total, items = c.parse_aladin(page("aladin-seojeong.html"), "http://lib.seojeong.ac.kr:8888", "ebo1655356114")
        self.assertEqual(3220, total)
        self.assertEqual("기욤 피트롱 지음, 양영란 옮김", items[0]["author_raw"])
        self.assertIsNone(items[0].get("intro_raw"))

    def test_portal_list_has_no_intro(self):
        _, items = c.parse_portal(page("portal-busan.html"), "https://library.busan.go.kr/elib", "EBK", "14")
        self.assertIsNone(items[0].get("intro_raw"))


class SaveTests(unittest.TestCase):
    def test_migrate_adds_the_column_and_save_writes_it(self):
        con = sqlite3.connect(":memory:")
        con.executescript("""
            CREATE TABLE holding (library_id INTEGER NOT NULL, item_key TEXT NOT NULL, platform TEXT NOT NULL,
              title TEXT NOT NULL, author_raw TEXT, publisher TEXT, published_on TEXT, published_raw TEXT,
              supplier TEXT, content_kind TEXT, category_code TEXT, category_name TEXT, cover_url TEXT,
              detail_path TEXT, first_seen_at TEXT NOT NULL, last_seen_at TEXT NOT NULL, removed_at TEXT,
              last_run_id INTEGER NOT NULL, PRIMARY KEY (library_id, item_key));
            CREATE TABLE library (id INTEGER PRIMARY KEY, name TEXT NOT NULL, platform_type TEXT NOT NULL,
              base_url TEXT NOT NULL, host TEXT NOT NULL, seed_holdings_count INTEGER, last_full_success_at TEXT,
              last_full_run_id INTEGER);
            CREATE TABLE crawl_run (id INTEGER PRIMARY KEY, library_id INTEGER);
            INSERT INTO library (id, name, platform_type, base_url, host) VALUES (1, 'a', 'YES24_V2', 'https://a', 'a');
        """)
        c.migrate(con)
        c.migrate(con)  # twice is fine
        self.assertIn("intro_raw", [r[1] for r in con.execute("PRAGMA table_info(holding)")])
        items = [dict(item_key="1", platform="YES24", title="t", intro_raw="소개\n둘째 줄"),
                 dict(item_key="2", platform="YES24", title="u")]
        c.save(con, 1, 1, items, "2026-10-23T00:00:00")
        self.assertEqual([("1", "소개\n둘째 줄"), ("2", None)],
                         con.execute("SELECT item_key, intro_raw FROM holding ORDER BY item_key").fetchall())
        # the next crawl overwrites the text with what the list says then
        c.save(con, 1, 2, [dict(item_key="1", platform="YES24", title="t", intro_raw="새 소개")], "2026-10-24T00:00:00")
        self.assertEqual("새 소개", con.execute("SELECT intro_raw FROM holding WHERE item_key='1'").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
