"""Tests for build_candidate_seed.py on a small made-up snapshot (plan handoff step 1).

Run from the repository root: python -m unittest scripts/library/test_build_candidate_seed.py
"""
import csv
import io
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_candidate_seed as seed  # noqa: E402

REAL_REGIONS = 'backend/src/main/resources/db/changelog/region/002-region-seed.csv'
KIND_HEADER = ['id', 'name', 'kind', 'sure', 'note']
JOIN_HEADER = ['id', 'name', 'is_public', 'eligibility', 'basis', 'scope', 'visit_required', 'join_method',
               'extra_condition', 'join_caution', 'quote', 'source_url', 'checked', 'memo', 'decision']
PROBE_HEADER = ['category', 'name', 'url', 'status', 'platform', 'base_url', 'titles', 'model', 'note', 'checked_at']

REGISTERED = [(1, '가나도서관', 'KYOBO_V1', 'https://ga.example.kr'),
              (2, '다라도서관', 'KYOBO_SUBSCRIPTION_V1', 'https://dara.dkyobobook.co.kr')]
# (id, name, platform, base_url, kind, crawl_skip, full success)
CANDIDATES = [
    (100001, '강동대학교', 'BOOKCUBE_FX', 'https://kd.bookcube.biz/FxLibrary', 'UNIV', None, True),
    (100002, '강동대학교', 'KYOBO_V1', 'http://kd.example.ac.kr:9090', 'UNIV', None, True),
    (100003, '부여초등학교', 'KYOBO_SUBSCRIPTION_V1', 'https://a.dkyobobook.co.kr', 'SCHOOL', None, True),
    (100004, '부여초등학교', 'KYOBO_SUBSCRIPTION_V1', 'https://b.dkyobobook.co.kr', 'SCHOOL', None, True),
    (100005, '가나도서관', 'YES24_V2', 'http://gana.yes24library.com', 'PUBLIC', None, True),
    (100006, '어느회사', 'ALADIN_V1', 'http://x.aladinebook.co.kr?nav_code=e1', 'COMPANY', None, True),
    (100007, '모름도서관', 'YES24_V1', 'http://m.yes24library.com', 'UNKNOWN', None, True),
    (100008, '같은사이트', 'KYOBO_V1', 'http://ga.example.kr:8080', 'PUBLIC', 'same site as registered library 1',
     True),
    (100009, '덜받은곳', 'KYOBO_V1', 'http://half.example.kr', 'GOV', None, False),
    (100010, '테스트도서관', 'KYOBO_V1', 'http://test.example.kr', 'TEST', None, True),
    (100068, '삼각산어린이도서관', 'BOOKCUBE_FX', 'http://ssg.bookcube.biz/FxLibrary', 'PUBLIC', None, True),
    (100011, '미확인도서관', 'BOOKCUBE_FX', 'http://u.bookcube.biz/FxLibrary', 'PUBLIC', None, True),
]
JOINS = {
    100005: dict(eligibility='REGION', basis='거주,재직,재학', scope='서울특별시', visit_required='false',
                 join_method='홈페이지 가입', source_url='https://gana.example.kr/join'),
    100011: dict(eligibility='UNVERIFIED', visit_required='?', join_method=seed.JOIN_METHOD_UNKNOWN),
    100068: dict(eligibility='OPEN', visit_required='false'),
}


def write_tsv(path, header, rows):
    with open(path, 'w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=header, delimiter='\t', lineterminator='\n', restval='')
        w.writeheader()
        w.writerows(rows)


class BuildCandidateSeedTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        changelog = os.path.join(self.dir, 'changelog') + '/'
        os.makedirs(changelog + 'library')
        os.makedirs(changelog + 'region')
        shutil.copy(REAL_REGIONS, changelog + 'region/002-region-seed.csv')
        with open(changelog + 'library/002-library-seed.yaml', 'w', encoding='utf-8') as f:
            f.write('databaseChangeLog: []\n')
        with open(changelog + 'library/004-library.csv', 'w', encoding='utf-8', newline='') as f:
            w = csv.writer(f, lineterminator='\n')
            w.writerow(['id', 'name', 'platform_type', 'base_url'])
            w.writerows(REGISTERED)
        self.patches = {'CHANGELOG': changelog, 'LIBRARY_DIR': changelog + 'library/',
                        'LIBRARY_CSV': changelog + 'library/008-library-candidates.csv',
                        'RULE_CSV': changelog + 'library/008-eligibility-rule-candidates.csv',
                        'LINK_CSV': changelog + 'collection/002-collection-link.csv',
                        'REGIONS': changelog + 'region/002-region-seed.csv',
                        'NAME_OVERRIDES': os.path.join(self.dir, 'name_overrides.tsv')}
        self.saved = {k: getattr(seed, k) for k in self.patches}
        for k, v in self.patches.items():
            setattr(seed, k, v)
        self.snapshot = os.path.join(self.dir, 'snapshot.sqlite')
        self.write_snapshot(CANDIDATES)
        self.kinds = os.path.join(self.dir, 'kinds.tsv')
        self.joins = os.path.join(self.dir, 'join.tsv')
        self.probe = os.path.join(self.dir, 'probe.tsv')
        self.write_inputs(CANDIDATES)

    def tearDown(self):
        for k, v in self.saved.items():
            setattr(seed, k, v)
        shutil.rmtree(self.dir)

    def write_snapshot(self, candidates):
        if os.path.exists(self.snapshot):
            os.remove(self.snapshot)
        db = sqlite3.connect(self.snapshot)
        db.execute('CREATE TABLE library (id INTEGER PRIMARY KEY, name TEXT, platform_type TEXT, base_url TEXT, '
                   'last_full_success_at TEXT, crawl_skip TEXT)')
        db.execute('CREATE TABLE holding (library_id INTEGER, item_key TEXT, removed_at TEXT, '
                   'PRIMARY KEY (library_id, item_key))')
        for lid, name, platform, base in REGISTERED:
            db.execute('INSERT INTO library VALUES (?, ?, ?, ?, ?, NULL)', (lid, name, platform, base, '2026-10-01'))
        for lid, name, platform, base, _kind, skip, done in candidates:
            db.execute('INSERT INTO library VALUES (?, ?, ?, ?, ?, ?)',
                       (lid, name, platform, base, '2026-10-01' if done else None, skip))
        db.executemany('INSERT INTO holding VALUES (1, ?, NULL)', [('k1',), ('k2',)])
        db.commit()
        db.close()

    def write_inputs(self, candidates):
        write_tsv(self.kinds, KIND_HEADER, [{'id': c[0], 'name': c[1], 'kind': c[4], 'sure': 'Y'} for c in candidates])
        write_tsv(self.joins, JOIN_HEADER, [dict(JOINS[c[0]], id=c[0], name=c[1], is_public='Y', checked='확인됨')
                                            for c in candidates if c[0] in JOINS])
        write_tsv(self.probe, PROBE_HEADER, [
            {'name': '부여초등학교', 'status': 'OK', 'platform': 'KYOBO_SUBSCRIPTION_V1',
             'base_url': 'https://a.dkyobobook.co.kr', 'model': '소장'},
            {'name': '다라도서관', 'status': 'OK', 'platform': 'KYOBO_SUBSCRIPTION_V1',
             'base_url': 'https://dara.dkyobobook.co.kr', 'model': '대여'}])

    def run_seed(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = seed.main(self.snapshot, self.joins, self.kinds, self.probe)
        return code, out.getvalue()

    def read(self, key):
        return list(csv.DictReader(open(self.patches[key], encoding='utf-8')))

    def libraries(self):
        return {int(r['id']): r for r in self.read('LIBRARY_CSV')}

    def test_same_input_gives_same_output(self):
        self.run_seed()
        first = [open(self.patches[k], encoding='utf-8').read() for k in ('LIBRARY_CSV', 'RULE_CSV', 'LINK_CSV')]
        self.run_seed()
        second = [open(self.patches[k], encoding='utf-8').read() for k in ('LIBRARY_CSV', 'RULE_CSV', 'LINK_CSV')]
        self.assertEqual(first, second)

    def test_new_candidate_gets_the_next_number_and_others_keep_theirs(self):
        self.run_seed()
        before = self.libraries()
        added = CANDIDATES + [(100012, '새도서관', 'YES24_V2', 'http://new.yes24library.com', 'ORG', None, True)]
        self.write_snapshot(added)
        self.write_inputs(added)
        self.run_seed()
        after = self.libraries()
        self.assertEqual({k: v['name'] for k, v in before.items()}, {k: after[k]['name'] for k in before})
        self.assertEqual(max(after), max(before) + 1)
        self.assertEqual(after[max(after)]['name'], '새도서관')
        links = {int(r['collection_library_id']): int(r['library_id']) for r in self.read('LINK_CSV')}
        self.assertEqual(links[100012], max(after))

    def test_numbers_start_after_the_registered_libraries_and_link_them_to_themselves(self):
        self.run_seed()
        links = {int(r['collection_library_id']): int(r['library_id']) for r in self.read('LINK_CSV')}
        self.assertEqual(links[1], 1)
        self.assertEqual(links[2], 2)
        self.assertEqual(links[100001], 3)

    def test_names_get_a_label_then_a_host(self):
        self.run_seed()
        names = {r['base_url']: r['name'] for r in self.libraries().values()}
        self.assertEqual(names['https://kd.bookcube.biz/FxLibrary'], '강동대학교 (북큐브)')
        self.assertEqual(names['http://kd.example.ac.kr:9090'], '강동대학교 (교보)')
        self.assertEqual(names['https://a.dkyobobook.co.kr'], '부여초등학교 (교보, a.dkyobobook.co.kr)')
        self.assertEqual(names['https://b.dkyobobook.co.kr'], '부여초등학교 (교보, b.dkyobobook.co.kr)')
        self.assertEqual(names['http://gana.yes24library.com'], '가나도서관 (YES24)')  # registered 1 keeps its name
        self.assertEqual(names['http://m.yes24library.com'], '모름도서관')

    def test_left_out_candidates(self):
        code, out = self.run_seed()
        bases = {r['base_url'] for r in self.libraries().values()}
        self.assertNotIn('http://ga.example.kr:8080', bases)  # crawl_skip
        self.assertNotIn('http://half.example.kr', bases)  # no full success
        self.assertNotIn('http://test.example.kr', bases)  # TEST kind
        self.assertNotIn('http://ssg.bookcube.biz/FxLibrary', bases)  # demo or closed
        self.assertIn('left out: kyobo_rental_mixed 0, crawl_skip 1, no_full_success 1, demo_or_closed 1, same_site 0, '
                      'test 1', out)

    def test_join_survey_and_rules(self):
        self.run_seed()
        by_url = {r['base_url']: r for r in self.libraries().values()}
        surveyed = by_url['http://gana.yes24library.com']
        self.assertEqual(surveyed['join_survey'], 'SURVEYED')
        self.assertEqual(surveyed['join_method'], '홈페이지 가입')
        self.assertEqual(surveyed['eligibility_summary'], '서울 거주·재직·재학')
        self.assertEqual(by_url['http://u.bookcube.biz/FxLibrary']['join_survey'], 'NOT_FOUND')
        self.assertEqual(by_url['http://u.bookcube.biz/FxLibrary']['join_method'], 'NULL')
        self.assertEqual(by_url['http://x.aladinebook.co.kr?nav_code=e1']['join_survey'], 'MEMBERS_ONLY')
        self.assertEqual(by_url['http://m.yes24library.com']['join_survey'], 'NOT_SURVEYED')
        rules = [r for r in self.read('RULE_CSV') if int(r['library_id']) == int(surveyed['id'])]
        self.assertEqual({(r['basis'], r['scope_region_id']) for r in rules},
                         {('RESIDENCE', '11'), ('WORK', '11'), ('SCHOOL', '11')})

    def test_lending_model_from_the_probe(self):
        code, out = self.run_seed()
        by_url = {r['base_url']: r for r in self.libraries().values()}
        self.assertEqual(by_url['https://a.dkyobobook.co.kr']['lending_model'], 'OWNED')
        self.assertEqual(by_url['https://b.dkyobobook.co.kr']['lending_model'], 'NULL')
        self.assertIn("registered lending_model (library/008 UPDATE): (2, 'RENTAL')", out)

    def test_kyobo_rental_and_mixed_candidates_leave_and_their_numbers_are_not_given_again(self):
        self.run_seed()
        before = self.libraries()
        rental_id = next(k for k, v in before.items() if v['base_url'] == 'https://b.dkyobobook.co.kr')
        added = CANDIDATES + [(100012, '새도서관', 'YES24_V2', 'http://new.yes24library.com', 'ORG', None, True)]
        self.write_snapshot(added)
        self.write_inputs(added)
        with open(self.probe, 'a', encoding='utf-8', newline='') as f:
            csv.DictWriter(f, fieldnames=PROBE_HEADER, delimiter='\t', lineterminator='\n', restval='').writerow(
                {'name': '부여초등학교', 'status': 'OK', 'platform': 'KYOBO_SUBSCRIPTION_V1',
                 'base_url': 'https://b.dkyobobook.co.kr', 'model': '혼합'})
        code, out = self.run_seed()
        after = self.libraries()
        self.assertNotIn(rental_id, after)
        self.assertEqual({k: v['name'] for k, v in before.items() if k != rental_id},
                         {k: after[k]['name'] for k in after if k in before})
        self.assertEqual(after[max(after)]['name'], '새도서관')
        self.assertEqual(max(after), max(before) + 1)  # the rental site's number is not given to the new one
        self.assertNotIn(rental_id, {int(r['library_id']) for r in self.read('LINK_CSV')})
        self.assertIn('left out: kyobo_rental_mixed 1,', out)
        # registered libraries keep their lending_model
        self.assertIn("registered lending_model (library/008 UPDATE): (2, 'RENTAL')", out)

    def write_overrides(self, rows):
        write_tsv(self.patches['NAME_OVERRIDES'],
                  ['library_id', 'old_name', 'new_name', 'changed_on', 'source_url', 'checked_on'], rows)

    def test_listed_names_change_and_keep_their_numbers(self):
        self.run_seed()
        before = self.libraries()
        renamed = next(k for k, v in before.items() if v['name'] == '모름도서관')
        self.write_overrides([{'library_id': renamed, 'old_name': '모름도서관', 'new_name': '새이름도서관',
                               'changed_on': '2026-01-01', 'source_url': 'https://x.test', 'checked_on': '2026-10-11'}])
        self.run_seed()
        after = self.libraries()
        self.assertEqual(after[renamed]['name'], '새이름도서관')
        self.assertEqual(set(after), set(before))
        # names not in the list stay
        self.assertEqual({k: v['name'] for k, v in before.items() if k != renamed},
                         {k: v['name'] for k, v in after.items() if k != renamed})
        # running again keeps the new name (the kept name is now the new one)
        self.run_seed()
        self.assertEqual(self.libraries()[renamed]['name'], '새이름도서관')

    def test_stale_or_clashing_override_stops(self):
        self.run_seed()
        lib = next(k for k, v in self.libraries().items() if v['name'] == '모름도서관')
        self.write_overrides([{'library_id': lib, 'old_name': '다른이름', 'new_name': '새이름'}])
        with self.assertRaises(SystemExit):
            self.run_seed()
        self.write_overrides([{'library_id': lib, 'old_name': '모름도서관', 'new_name': '가나도서관'}])  # registered 1
        with self.assertRaises(SystemExit):
            self.run_seed()

    def test_same_host_with_shared_keys_stops_until_read(self):
        extra = CANDIDATES + [(100013, '가나분관', 'BOOKCUBE_FX', 'http://ga.example.kr:9000/FxLibrary', 'PUBLIC',
                               None, True)]
        self.write_snapshot(extra)
        self.write_inputs(extra)
        db = sqlite3.connect(self.snapshot)
        db.execute("INSERT INTO holding VALUES (100013, 'k1', NULL)")
        db.commit()
        db.close()
        joins = dict(JOINS)
        JOINS[100013] = dict(eligibility='UNVERIFIED', visit_required='?')
        try:
            self.write_inputs(extra)
            code, out = self.run_seed()
        finally:
            JOINS.clear()
            JOINS.update(joins)
        self.assertEqual(code, 2)
        self.assertIn('100013 가나분관', out)


if __name__ == '__main__':
    unittest.main()
