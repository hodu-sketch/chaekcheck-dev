"""The checkable rules of the tab blurbs (spec R8-4). No network, no API."""
import unittest

from foryou_blurbs import check, copied_run

GOOD = ('지상이 무너진 뒤 지하 도시로 쫓겨난 아이들이 서로를 지키며 자라는 세 편의 연작 소설이에요. '
        '작은 생명과 우정, 돌봄을 차분하게 따라가는 이야기를 찾는 분께 잘 맞아요.')


class CheckTests(unittest.TestCase):

    def test_a_good_blurb_passes(self):
        self.assertTrue(80 <= len(GOOD) <= 150)
        self.assertIsNone(check(GOOD, '전혀 다른 소개 글입니다.'))

    def test_rules(self):
        self.assertEqual(check('NONE', ''), 'none')
        self.assertEqual(check('짧아요.', ''), 'length')
        self.assertEqual(check(GOOD.replace('맞아요.', '맞는 최고의 책이에요.'), ''), 'banned_word')
        self.assertEqual(check(GOOD.replace('맞아요.', '맞습니다.'), ''), 'not_haeyo')
        self.assertEqual(check(GOOD, '...지하 도시로 쫓겨난 아이들...'), 'copied')

    def test_copied_run_ignores_spaces(self):
        self.assertEqual(copied_run('가나다라 마바사아', '가나다라마바사아자'), '가나다라마바사아')
        self.assertIsNone(copied_run('가나다라마바사', '가나다라마바사아'))  # seven characters only


if __name__ == '__main__':
    unittest.main()
