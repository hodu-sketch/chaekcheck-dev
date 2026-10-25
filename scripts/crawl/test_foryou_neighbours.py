"""Other editions and volumes of one work among the for-you neighbours (user 2026-10-25). No network, no model."""
import unittest

from foryou_neighbours import first_author, same_work, work_key


class WorkKeyTests(unittest.TestCase):

    def test_editions_and_volumes_come_to_one_key(self):
        self.assertEqual(work_key('마션'), work_key('마션 (THE MARTIAN)'))
        self.assertEqual(work_key('마션'), work_key('마션(스폐셜 에디션)'))
        self.assertEqual(work_key('토지. 1(1부 1권)'), work_key('토지. 16(5부 1권)'))
        self.assertEqual(work_key('달러구트 꿈 백화점'), work_key('달러구트 꿈 백화점 2'))
        self.assertEqual(work_key('달러구트 꿈 백화점'), work_key('달러구트 꿈 백화점. 2'))
        self.assertEqual(work_key('이기적 유전자'), work_key('이기적 유전자(40주년 기념판)'))
        self.assertEqual(work_key('삼국지 III'), work_key('삼국지'))

    def test_other_titles_stay_apart(self):
        self.assertNotEqual(work_key('아르테미스'), work_key('마션'))
        self.assertNotEqual(work_key('공부가 되는 셰익스피어 4대 비극'), work_key('셰익스피어'))
        self.assertEqual(work_key('Taxi'), 'taxi')
        self.assertEqual(work_key('1984'), '1984')


class SameWorkTests(unittest.TestCase):

    def test_first_author_without_role(self):
        self.assertEqual(first_author('앤디 위어 지음, 박아람 옮김'), '앤디위어')

    def test_same_title_same_author_is_one_work(self):
        self.assertTrue(same_work((1, '마션', '앤디 위어'), (2, '마션 (THE MARTIAN)', '앤디 위어 지음')))
        self.assertTrue(same_work((1, '햄릿', '윌리엄 셰익스피어'), (2, '햄릿(Hamlet)', '셰익스피어(William Shakespeare)')))
        self.assertTrue(same_work((1, '토지. 1', '박경리'), (2, '토지. 2', None)))

    def test_same_title_other_author_is_another_book(self):
        self.assertFalse(same_work((1, '토지', '박경리'), (2, '토지', '김철수')))
        self.assertFalse(same_work((1, '마션', '앤디 위어'), (2, '아르테미스', '앤디 위어')))


if __name__ == '__main__':
    unittest.main()
