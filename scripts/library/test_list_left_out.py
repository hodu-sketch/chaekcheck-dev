"""Tests for list_left_out.py on a small made-up report, snapshot, rules folder and service export.

Run from the repository root: python -m unittest scripts/library/test_list_left_out.py
"""
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import list_left_out  # noqa: E402

# (id, name, platform, base_url, crawl_skip, last_full_success_at)
COLLECTED = [
    (1, '가나도서관', 'KYOBO_V1', 'https://ga.example.kr', None, '2026-10-01T00:00:00+09:00'),
    (2, '다라도서관', 'YES24_V2', 'http://dara.example.kr', 'site resets scripted connections | not bypassed',
     None),
    (3, '꺼진대학교', 'YES24_V2', 'http://off.example.kr', None, '2026-10-08T00:00:00+09:00'),
    (100001, '대여도서관', 'KYOBO_SUBSCRIPTION_V1', 'https://rent.dkyobobook.co.kr/', None, None),
    (100002, '혼합도서관', 'KYOBO_SUBSCRIPTION_V1', 'https://mix.dkyobobook.co.kr', None, None),
    (100003, '같은사이트', 'KYOBO_V1', 'http://ga.example.kr:88', 'same site as registered library 1',
     '2026-10-01T00:00:00+09:00'),
    (100004, '미완도서관', 'BOOKCUBE_FX', 'http://slow.bookcube.biz/FxLibrary', None, None),
    (100005, '체험도서관', 'BOOKCUBE_FX', 'http://demo.bookcube.biz/FxLibrary', None, '2026-10-01T00:00:00+09:00'),
    (100006, '테스트도서관', 'BOOKCUBE_FX', 'http://test.bookcube.biz/FxLibrary', None, '2026-10-01T00:00:00+09:00'),
    (100007, '새도서관', 'KYOBO_V1', 'https://new.example.kr', None, '2026-10-01T00:00:00+09:00'),
]
REPORT = {
    'reportId': 'abc123', 'createdAt': '2026-10-12T01:00:00Z', 'snapshot': 'copy.sqlite',
    'leftOut': {'kyobo_rental_mixed': [100002, 100001], 'crawl_skip': [100003], 'no_full_success': [100004],
                'demo_or_closed': [100005], 'same_site': [], 'test': [100006]},
    'goneKnown': [2],
    'newLibraries': [{'collectionId': 100007, 'library': {'id': 10, 'name': '새도서관'}}],
}
RULES = {
    'kyobo_probe.tsv': [['site_url', 'name', 'category', 'model', 'lending_model', 'checked_at'],
                        ['https://rent.dkyobobook.co.kr', '대여도서관', '공공', '대여', 'RENTAL', '2026-10-03T12:00:00+09:00'],
                        ['https://mix.dkyobobook.co.kr', '혼합도서관', '공공', '혼합', 'MIXED', '2026-10-03T12:00:00+09:00']],
    'demo_or_closed.tsv': [['collection_id', 'name', 'decided_on', 'source'],
                           ['100005', '체험도서관', '2026-10-05', 'plan easy 6번']],
    'same_site.tsv': [['collection_id', 'name', 'library_id', 'decided_on', 'note'],
                      ['100003', '같은사이트', '1', '2026-10-05', 'other port']],
    'library_kinds.tsv': [['collection_id', 'name', 'kind', 'sure', 'note'],
                          ['100006', '테스트도서관', 'TEST', 'Y', "이름에 '테스트'"]],
    'gone_known.tsv': [['collection_id', 'name', 'decided_on', 'checked_on', 'state'],
                       ['2', '다라도서관', '2026-10-12', '2026-10-12', 'resets a scripted connection']],
}
SERVICE = ('id\tname\tenabled\tbase_url\tplatform_type\tcollection_library_id\n'
           '1\t가나도서관\tt\thttps://ga.example.kr\tKYOBO_V1\t1\n'
           '2\t다라도서관\tt\thttp://dara.example.kr\tYES24_V2\t2\n'
           '3\t꺼진대학교\tf\thttp://off.example.kr\tYES24_V2\t3\n'
           '4\t사라진도서관\tf\thttps://gone.example.kr\tKYOBO_V1\t4\n')
DISABLED = ('library_id\tname\tdisabled_on\treason\tsource\n'
            '3\t꺼진대학교\t2026-10-05\t시간 초과 세 번\tlibrary/010\n')


class ListLeftOutTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        rules = os.path.join(self.dir, 'rules')
        os.mkdir(rules)
        for name, rows in RULES.items():
            with open(os.path.join(rules, name), 'w', encoding='utf-8', newline='\n') as f:
                f.write(''.join('\t'.join(row) + '\n' for row in rows))
        self.snapshot = os.path.join(self.dir, 'copy.sqlite')
        con = sqlite3.connect(self.snapshot)
        con.execute('CREATE TABLE library (id INTEGER PRIMARY KEY, name TEXT, platform_type TEXT, base_url TEXT, '
                    'host TEXT, last_full_success_at TEXT, crawl_skip TEXT, vendor TEXT)')
        con.executemany('INSERT INTO library (id, name, platform_type, base_url, crawl_skip, last_full_success_at) '
                        'VALUES (?, ?, ?, ?, ?, ?)', COLLECTED)
        con.commit()
        con.close()
        self.report = os.path.join(self.dir, 'report.json')
        with open(self.report, 'w', encoding='utf-8') as f:
            json.dump(REPORT, f, ensure_ascii=False)
        self.service = os.path.join(self.dir, 'service.tsv')
        with open(self.service, 'w', encoding='utf-8', newline='\n') as f:
            f.write(SERVICE)
        self.disabled = os.path.join(self.dir, 'disabled.tsv')
        with open(self.disabled, 'w', encoding='utf-8', newline='\n') as f:
            f.write(DISABLED)
        self.out = os.path.join(self.dir, 'out.md')

    def tearDown(self):
        shutil.rmtree(self.dir)

    def run_tool(self, *extra):
        list_left_out.main(['--report', self.report, '--snapshot', self.snapshot, '--service', self.service,
                            '--rules', os.path.join(self.dir, 'rules'), '--disabled', self.disabled,
                            '--out', self.out, *extra])
        with open(self.out, encoding='utf-8') as f:
            return f.read()

    def section(self, text, start, end):
        return text[text.index(start):text.index(end)]

    def test_header_and_sources(self):
        text = self.run_tool()
        self.assertIn('- 작성일: 2026-10-12', text)
        self.assertIn('`abc123`', text)
        self.assertIn('`copy.sqlite`', text)
        self.assertIn('기본으로 서비스에 그대로 둡니다', text)
        self.assertIn('100007 새도서관', text)
        self.assertIn('- 작성일: 2026-10-13', self.run_tool('--date', '2026-10-13'))

    def test_section_a_by_reason(self):
        text = self.run_tool()
        self.assertIn('## A. 수집 DB에 있지만 넣지 않는 곳 (6곳)', text)
        kyobo = self.section(text, '### A-1.', '### A-2.')
        self.assertIn('(2곳)', kyobo)
        self.assertIn('MIXED 1곳, RENTAL 1곳', kyobo)
        # trailing / of base_url is ignored when matching the probe; rows sorted by collection id
        self.assertIn('probe 대여 (RENTAL), 2026-10-03', kyobo)
        self.assertLess(kyobo.index('| 100001 |'), kyobo.index('| 100002 |'))
        skip = self.section(text, '### A-2.', '### A-3.')
        self.assertIn('same site as registered library 1 / `same_site.tsv`: 서비스 1와 같은 사이트', skip)
        self.assertIn('`last_full_success_at` 없음', self.section(text, '### A-3.', '### A-4.'))
        self.assertIn('plan easy 6번 (2026-10-05)', self.section(text, '### A-4.', '### A-5.'))
        same = self.section(text, '### A-5.', '### A-6.')
        self.assertIn('(0곳)', same)
        self.assertIn('1곳은 앞 규칙', same)
        self.assertIn("이름에 '테스트'", self.section(text, '### A-6.', '## B.'))

    def test_section_b(self):
        text = self.run_tool()
        disabled = self.section(text, '### B-1.', '### B-2.')
        self.assertIn('(`enabled = false`, 2곳)', disabled)
        self.assertIn('시간 초과 세 번', disabled)
        self.assertIn('**이유 기록 없음 (확인 필요)**', disabled)
        self.assertIn('4: 수집 DB에 없음', disabled)
        self.assertIn('끈 날 뒤에도 수집 DB의 전체 수집이 끝난 곳: 3', disabled)
        self.assertIn('`disabled_reasons.tsv`에 없는 곳: 4', disabled)
        gone = self.section(text, '### B-2.', '### B-3.')
        self.assertIn('resets a scripted connection', gone)
        self.assertIn('site resets scripted connections \\| not bypassed', gone)  # pipe escaped
        self.assertIn('(0곳)', text[text.index('### B-3.'):])

    def test_new_problem_is_listed(self):
        with open(self.service, 'a', encoding='utf-8', newline='\n') as f:
            f.write('5\t새문제도서관\tt\thttps://new.example.kr\tKYOBO_V1\t100004\n')
        b3 = self.run_tool()[self.run_tool().index('### B-3.'):]
        self.assertIn('(1곳)', b3)
        self.assertIn('100004: 마지막 전체 수집 없음', b3)

    def test_service_without_link_column(self):
        with open(self.service, 'w', encoding='utf-8', newline='\n') as f:
            f.write('\n'.join('\t'.join(line.split('\t')[:5]) for line in SERVICE.strip().split('\n')) + '\n')
        text = self.run_tool()
        self.assertIn('`collection_library_id` 칸이 없어', text)


if __name__ == '__main__':
    unittest.main()
