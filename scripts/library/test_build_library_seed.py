"""Tests for build_library_seed.py on a small made-up snapshot and rules folder (plan cycle 07 step 1, cycle 13 step 4).

Run from the repository root: python -m unittest scripts/library/test_build_library_seed.py

The script writes no seed file any more (plan cycle 13 R4-3). run_seed() copies its --out files to the seed paths
of the temporary changelog, as applying the seed did, so later runs keep the numbers and names given.
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
import build_library_seed as seed  # noqa: E402
import resolve_regions  # noqa: E402

REAL_REGIONS = 'backend/src/main/resources/db/changelog/region/002-region-seed.csv'
KIND_HEADER = ['collection_id', 'name', 'kind', 'sure', 'note']
JOIN_HEADER = ['collection_id', 'name', 'is_public', 'eligibility', 'basis', 'scope', 'visit_required', 'join_method',
               'extra_condition', 'join_caution', 'quote', 'source_url', 'checked', 'memo', 'decision', 'rules',
               'eligibility_summary']
PROBE_HEADER = ['site_url', 'name', 'category', 'model', 'lending_model', 'checked_at']
VOLUME_HEADER = ['collection_id', 'name', 'base_url', 'total_item', 'total_volume', 'error', 'checked_on']
OVERRIDE_HEADER = ['library_id', 'old_name', 'new_name', 'changed_on', 'source_url', 'checked_on']
EMPTY_RULES = {'demo_or_closed.tsv': ['collection_id', 'name', 'decided_on', 'source'],
               'same_site.tsv': ['collection_id', 'name', 'library_id', 'decided_on', 'note'],
               'same_host_kept.tsv': ['collection_id', 'name', 'decided_on', 'note'],
               'order_no_allowed.tsv': ['collection_id', 'order_no', 'decided_on', 'note'],
               'scope_overrides.tsv': ['collection_id', 'residence', 'work_school', 'quote'],
               'final_origins.tsv': ['listed_base_url', 'base_url', 'checked_on', 'service_id'],
               'name_overrides.tsv': OVERRIDE_HEADER}

REGISTERED = [(1, '가나도서관', 'KYOBO_V1', 'https://ga.example.kr'),
              (2, '다라도서관', 'KYOBO_SUBSCRIPTION_V1', 'https://dara.dkyobobook.co.kr')]
# (id, name, platform, base_url, kind, crawl_skip, full success)
DISCOVERED = [
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
    (100015, '구독도서관', 'BOOKCUBE_FX', 'http://s.bookcube.biz/FxLibrary', 'ORG', None, True),
    (100016, '빈도서관', 'BOOKCUBE_FX', 'http://e.bookcube.biz/FxLibrary', 'ORG', None, True),
]
# BOOKCUBE (totalItem, totalVolume); None: the site did not answer
VOLUMES = {100001: (10, 5), 100011: None, 100015: (300, 0), 100016: (0, 0)}
JOINS = {
    100005: dict(eligibility='REGION', basis='거주,재직,재학', scope='서울특별시', visit_required='false',
                 join_method='홈페이지 가입', source_url='https://gana.example.kr/join'),
    100011: dict(eligibility='UNVERIFIED', visit_required='?', join_method=seed.JOIN_METHOD_UNKNOWN),
    100068: dict(eligibility='OPEN', visit_required='false'),
}


def text(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def write_tsv(path, header, rows):
    with open(path, 'w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=header, delimiter='\t', lineterminator='\n', restval='')
        w.writeheader()
        w.writerows(rows)


class BuildLibrarySeedTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        changelog = os.path.join(self.dir, 'changelog') + '/'
        os.makedirs(changelog + 'library')
        os.makedirs(changelog + 'collection')
        with open(changelog + 'library/002-library-seed.yaml', 'w', encoding='utf-8') as f:
            f.write('databaseChangeLog: []\n')
        with open(changelog + 'library/004-library.csv', 'w', encoding='utf-8', newline='') as f:
            w = csv.writer(f, lineterminator='\n')
            w.writerow(['id', 'name', 'platform_type', 'base_url'])
            w.writerows(REGISTERED)
        self.patches = {'CHANGELOG': changelog, 'LIBRARY_DIR': changelog + 'library/',
                        'LIBRARY_CSV': changelog + 'library/008-library-candidates.csv',
                        'LINK_CSV': changelog + 'collection/002-collection-link.csv'}
        self.saved = {k: getattr(seed, k) for k in self.patches}
        for k, v in self.patches.items():
            setattr(seed, k, v)
        # where the applied seed was; the script writes none of them (see the module docstring)
        self.seed_paths = {'LIBRARY_CSV': self.patches['LIBRARY_CSV'],
                           'RULE_CSV': changelog + 'library/008-eligibility-rule-candidates.csv',
                           'LINK_CSV': self.patches['LINK_CSV']}
        self.out = os.path.join(self.dir, 'out')
        self.rules = os.path.join(self.dir, 'rules') + '/'
        os.makedirs(self.rules)
        for name, header in EMPTY_RULES.items():
            write_tsv(self.rules + name, header, [])
        write_tsv(self.rules + 'demo_or_closed.tsv', EMPTY_RULES['demo_or_closed.tsv'],
                  [{'collection_id': 100068, 'name': '삼각산어린이도서관', 'decided_on': '2026-10-05'}])
        with open(self.rules + 'join_surveyed_on.txt', 'w', encoding='utf-8') as f:
            f.write('2026-10-05\n')
        self.snapshot = os.path.join(self.dir, 'snapshot.sqlite')
        self.write_snapshot(DISCOVERED)
        self.write_inputs(DISCOVERED)

    def tearDown(self):
        for k, v in self.saved.items():
            setattr(seed, k, v)
        shutil.rmtree(self.dir)

    def write_snapshot(self, discovered):
        if os.path.exists(self.snapshot):
            os.remove(self.snapshot)
        db = sqlite3.connect(self.snapshot)
        db.execute('CREATE TABLE library (id INTEGER PRIMARY KEY, name TEXT, platform_type TEXT, base_url TEXT, '
                   'last_full_success_at TEXT, crawl_skip TEXT)')
        db.execute('CREATE TABLE holding (library_id INTEGER, item_key TEXT, removed_at TEXT, '
                   'PRIMARY KEY (library_id, item_key))')
        for lid, name, platform, base in REGISTERED:
            db.execute('INSERT INTO library VALUES (?, ?, ?, ?, ?, NULL)', (lid, name, platform, base, '2026-10-01'))
        for lid, name, platform, base, _kind, skip, done in discovered:
            db.execute('INSERT INTO library VALUES (?, ?, ?, ?, ?, ?)',
                       (lid, name, platform, base, '2026-10-01' if done else None, skip))
        db.executemany('INSERT INTO holding VALUES (1, ?, NULL)', [('k1',), ('k2',)])
        db.commit()
        db.close()

    def write_inputs(self, discovered):
        write_tsv(self.rules + 'library_kinds.tsv', KIND_HEADER,
                  [{'collection_id': c[0], 'name': c[1], 'kind': c[4], 'sure': 'Y'} for c in discovered])
        joins = [dict(JOINS[c[0]], collection_id=str(c[0]), name=c[1], is_public='Y', checked='확인됨')
                 for c in discovered if c[0] in JOINS]
        names = resolve_regions.region_names()
        for join in joins:  # the columns resolve_regions.py fills
            for k in JOIN_HEADER:
                join.setdefault(k, '')
            join.update(resolve_regions.resolved_columns(join, {}, names))
        write_tsv(self.rules + 'public_join.tsv', JOIN_HEADER, joins)
        # every new library has a final address row (unchanged addresses map to themselves)
        write_tsv(self.rules + 'final_origins.tsv', EMPTY_RULES['final_origins.tsv'],
                  [{'listed_base_url': c[3], 'base_url': c[3], 'checked_on': '2026-10-12'} for c in discovered])
        write_tsv(self.rules + 'kyobo_probe.tsv', PROBE_HEADER, [
            {'site_url': 'https://a.dkyobobook.co.kr', 'name': '부여초등학교', 'model': '소장', 'lending_model': 'OWNED'},
            {'site_url': 'https://dara.dkyobobook.co.kr', 'name': '다라도서관', 'model': '대여',
             'lending_model': 'RENTAL'}])
        write_tsv(self.rules + 'bookcube_volume.tsv', VOLUME_HEADER, [
            {'collection_id': lid, 'total_item': v[0] if v else '', 'total_volume': v[1] if v else '',
             'error': '' if v else 'x'} for lid, v in VOLUMES.items()])

    def run_seed(self, db_state=None):
        """One run; without db_state the result goes to the seed paths, as applying the seed did."""
        out = io.StringIO()
        with redirect_stdout(out):
            code = seed.main(self.snapshot, rules_dir=self.rules, db_state=db_state, out_dir=self.out)
        if db_state is None:
            for key, name in (('LIBRARY_CSV', 'libraries.csv'), ('RULE_CSV', 'eligibility_rules.csv'),
                              ('LINK_CSV', 'links.csv')):
                shutil.copy(os.path.join(self.out, name), self.seed_paths[key])
        return code, out.getvalue()

    def seed_files(self):
        return [text(self.seed_paths[k]) for k in ('LIBRARY_CSV', 'RULE_CSV', 'LINK_CSV')]

    def write_db_state(self, names=None):
        """The db-state export of the seed applied last; names replaces some current names {id: name}."""
        rows = [(lid, name, platform) for lid, name, platform, _base in REGISTERED]
        rows += [(int(r['id']), r['name'], r['platform_type']) for r in self.read('LIBRARY_CSV')]
        path = os.path.join(self.dir, 'db-state.tsv')
        with open(path, 'w', encoding='utf-8', newline='') as f:
            for lid, name, platform in rows:
                f.write('\t'.join([str(lid), (names or {}).get(lid, name), platform, '', 't']) + '\n')
        return path

    def db_state_after_first_run(self):
        self.run_seed()
        return self.write_db_state()

    def read(self, key):
        return list(csv.DictReader(io.StringIO(text(self.seed_paths[key]))))

    def libraries(self):
        return {int(r['id']): r for r in self.read('LIBRARY_CSV')}

    def test_same_input_gives_same_output(self):
        self.run_seed()
        first = self.seed_files()
        self.run_seed()
        second = self.seed_files()
        self.assertEqual(first, second)

    def test_new_library_gets_the_next_number_and_others_keep_theirs(self):
        self.run_seed()
        before = self.libraries()
        added = DISCOVERED + [(100012, '새도서관', 'YES24_V2', 'http://new.yes24library.com', 'ORG', None, True)]
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

    def test_equal_names_get_no_label_and_other_vendors_keep_order_one(self):
        code, out = self.run_seed()
        names = {r['base_url']: r['name'] for r in self.libraries().values()}
        self.assertEqual(names['https://kd.bookcube.biz/FxLibrary'], '강동대학교')
        self.assertEqual(names['http://kd.example.ac.kr:9090'], '강동대학교')
        self.assertEqual(names['https://a.dkyobobook.co.kr'], '부여초등학교')  # KYOBO OWNED
        self.assertEqual(names['https://b.dkyobobook.co.kr'], '부여초등학교')  # KYOBO SUBSCRIPTION
        self.assertEqual(names['http://gana.yes24library.com'], '가나도서관')  # registered 1 is KYOBO
        self.assertEqual(names['http://m.yes24library.com'], '모름도서관')
        self.assertEqual(code, 0)
        self.assertIn('order_no 2 and up: 0', out)

    def test_same_name_vendor_and_lending_model_get_order_two_and_stop(self):
        added = DISCOVERED + [(100014, '모름도서관', 'YES24_V2', 'http://m2.yes24library.com', 'ORG', None, True)]
        self.write_snapshot(added)
        self.write_inputs(added)
        code, out = self.run_seed()
        libraries = self.libraries()
        second = next(k for k, v in libraries.items() if v['base_url'] == 'http://m2.yes24library.com')
        self.assertEqual(libraries[second]['name'], '모름도서관')
        self.assertEqual(code, 3)
        self.assertIn(f'order_no 2 and up: 1\n  ({second}, 2)', out)
        # a person allowed it: listed in order_no_allowed.tsv, the run goes on
        write_tsv(self.rules + 'order_no_allowed.tsv', EMPTY_RULES['order_no_allowed.tsv'],
                  [{'collection_id': 100014, 'order_no': 2, 'decided_on': '2026-10-12'}])
        code, out = self.run_seed()
        self.assertEqual(code, 0)
        self.assertIn('order_no 2 and up not in order_no_allowed.tsv: 0', out)

    def test_vendor_from_the_platform_table(self):
        self.assertEqual({seed.VENDORS[p] for p in ('KYOBO_V1', 'KYOBO_SUBSCRIPTION_V1')}, {'KYOBO'})
        self.assertEqual({seed.VENDORS[p] for p in ('YES24_V1', 'YES24_V2', 'YES24_V3')}, {'YES24'})
        self.assertEqual([seed.VENDORS[p] for p in ('BOOKCUBE_FX', 'ALADIN_V1', 'ELIB_PORTAL_V1', 'GANGNAM_V1')],
                         ['BOOKCUBE', 'ALADIN', 'PORTAL', 'IN_HOUSE'])
        code, out = self.run_seed(self.db_state_after_first_run())
        # only a vendor the collection wrote may differ from the table; this copy has no vendor column
        self.assertIn('-- vendor other than the platform table: 0', out)
        self.assertIn('all libraries by vendor: KYOBO 5, BOOKCUBE 4, YES24 2, ALADIN 1', out)

    def set_collection_vendor(self, collection_id, vendor):
        db = sqlite3.connect(self.snapshot)
        db.execute('ALTER TABLE library ADD COLUMN vendor TEXT')
        db.execute('UPDATE library SET vendor = ? WHERE id = ?', (vendor, collection_id))
        db.commit()
        db.close()

    def test_vendor_the_collection_wrote_wins_over_the_platform_table(self):
        state = self.db_state_after_first_run()
        self.set_collection_vendor(100002, 'IN_HOUSE')
        code, out = self.run_seed(state)
        self.assertRegex(out, r'-- vendor other than the platform table: 1\n  \d+ IN_HOUSE\n')
        self.assertIn('all libraries by vendor: KYOBO 4, BOOKCUBE 4, YES24 2, IN_HOUSE 1, ALADIN 1', out)

    def test_unknown_vendor_from_the_collection_stops(self):
        self.set_collection_vendor(100002, 'RIDI')
        db = sqlite3.connect(self.snapshot)
        try:
            with self.assertRaisesRegex(SystemExit, 'unknown vendor in the collection DB'):
                seed.collection_vendors(db)
        finally:
            db.close()

    def test_left_out_libraries(self):
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

    def test_lending_model_rules(self):
        code, out = self.run_seed()
        model = {r['base_url']: r['lending_model'] for r in self.libraries().values()}
        self.assertEqual(model['https://a.dkyobobook.co.kr'], 'OWNED')  # the probe
        self.assertEqual(model['https://b.dkyobobook.co.kr'], 'SUBSCRIPTION')  # KYOBO subscription, not in the probe
        self.assertEqual(model['http://kd.example.ac.kr:9090'], 'OWNED')  # KYOBO
        self.assertEqual(model['http://gana.yes24library.com'], 'OWNED')  # YES24
        self.assertEqual(model['http://x.aladinebook.co.kr?nav_code=e1'], 'OWNED')  # ALADIN
        self.assertEqual(model['http://s.bookcube.biz/FxLibrary'], 'SUBSCRIPTION')  # totalVolume 0, books listed
        self.assertEqual(model['https://kd.bookcube.biz/FxLibrary'], 'OWNED')  # volumes
        self.assertEqual(model['http://e.bookcube.biz/FxLibrary'], 'OWNED')  # no books
        self.assertEqual(model['http://u.bookcube.biz/FxLibrary'], 'OWNED')  # no answer
        self.assertIsNone(seed.lending_model_of('ELIB_PORTAL_V1', None, None))
        self.assertEqual(seed.lending_model_of('GANGNAM_V1', None, None), 'OWNED')
        self.assertIn("registered lending_model (library/008 UPDATE): (2, 'RENTAL')", out)

    def test_registered_kyobo_subscription_without_a_value_becomes_subscription(self):
        # registered 2 (KYOBO subscription) has no library/008 value here; registered 1 is KYOBO -> OWNED
        code, out = self.run_seed(self.db_state_after_first_run())
        self.assertIn("(1, 'OWNED'),\n(2, 'SUBSCRIPTION')", out)
        # a library/008 value stays
        with open(self.patches['LIBRARY_DIR'] + '008-library-seed-candidates.yaml', 'w', encoding='utf-8') as f:
            f.write("sql: UPDATE ... FROM (VALUES (2, 'RENTAL')) AS v (id, model)\n")
        code, out = self.run_seed(self.write_db_state())
        self.assertIn("(2, 'RENTAL')", out.split('-- lending_model UPDATE where NULL')[1])

    def test_kyobo_rental_and_mixed_sites_leave_and_their_numbers_are_not_given_again(self):
        self.run_seed()
        before = self.libraries()
        rental_id = next(k for k, v in before.items() if v['base_url'] == 'https://b.dkyobobook.co.kr')
        added = DISCOVERED + [(100012, '새도서관', 'YES24_V2', 'http://new.yes24library.com', 'ORG', None, True)]
        self.write_snapshot(added)
        self.write_inputs(added)
        with open(self.rules + 'kyobo_probe.tsv', 'a', encoding='utf-8', newline='') as f:
            csv.DictWriter(f, fieldnames=PROBE_HEADER, delimiter='\t', lineterminator='\n', restval='').writerow(
                {'site_url': 'https://b.dkyobobook.co.kr', 'name': '부여초등학교', 'model': '혼합',
                 'lending_model': 'MIXED'})
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
        write_tsv(self.rules + 'name_overrides.tsv', OVERRIDE_HEADER, rows)

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
        # the name of registered 1 (KYOBO): another vendor, so it goes in with order 1 ...
        self.write_overrides([{'library_id': lib, 'old_name': '모름도서관', 'new_name': '가나도서관'}])
        code, out = self.run_seed()
        self.assertEqual(self.libraries()[lib]['name'], '가나도서관')
        # ... but the YES24 library 가나도서관 has the same vendor and lending_model: order 2, the run stops
        self.assertEqual(code, 3)
        self.assertIn(f'({lib}, 2)', out)

    def test_registered_library_override_is_applied_in_memory_only(self):
        registered_csv = self.patches['LIBRARY_DIR'] + '004-library.csv'
        before = text(registered_csv)
        db_state = self.db_state_after_first_run()
        self.write_overrides([{'library_id': 1, 'old_name': '가나도서관', 'new_name': '가나시립도서관'}])
        code, out = self.run_seed(db_state)
        self.assertIn("(1, '가나도서관', '가나시립도서관')", out)
        self.assertEqual(text(registered_csv), before)

    def test_changes_only_writes_no_seed_file(self):
        db_state = self.db_state_after_first_run()
        before = self.seed_files()
        renamed = next(k for k, v in self.libraries().items() if v['name'] == '모름도서관')
        self.write_overrides([{'library_id': renamed, 'old_name': '모름도서관', 'new_name': '새이름도서관'}])
        added = DISCOVERED + [(100012, '새도서관', 'YES24_V2', 'http://new.yes24library.com', 'ORG', None, True)]
        self.write_snapshot(added)
        self.write_inputs(added)
        with self.assertRaises(SystemExit):  # the new library is not in the db state
            self.run_seed(db_state)
        self.assertEqual(self.seed_files(), before)
        self.write_snapshot(DISCOVERED)
        self.write_inputs(DISCOVERED)
        code, out = self.run_seed(db_state)
        self.assertEqual(self.seed_files(), before)
        self.assertIn(f"({renamed}, '모름도서관', '새이름도서관')", out)
        self.assertIn('-- for the collection session (service id, collection id, final name): 1\n'
                      f'{renamed}\t100007\t새이름도서관', out)

    def test_old_name_of_the_update_is_the_db_name(self):
        self.run_seed()
        renamed = next(k for k, v in self.libraries().items() if v['name'] == '모름도서관')
        db_state = self.write_db_state({renamed: "모름도서관 (YES24's)"})  # an earlier changeset renamed it in the db
        code, out = self.run_seed(db_state)
        self.assertIn(f"({renamed}, '모름도서관 (YES24''s)', '모름도서관')", out)

    def test_same_host_with_shared_keys_stops_until_read(self):
        extra = DISCOVERED + [(100013, '가나분관', 'BOOKCUBE_FX', 'http://ga.example.kr:9000/FxLibrary', 'PUBLIC',
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

    def add_library_on_a_host(self, lid, name, base_url, shared_with):
        """Adds a YES24 library sharing item key k1 with the library shared_with."""
        added = DISCOVERED + [(lid, name, 'YES24_V2', base_url, 'UNIV', None, True)]
        self.write_snapshot(added)
        self.write_inputs(added)
        db = sqlite3.connect(self.snapshot)
        db.executemany("INSERT INTO holding VALUES (?, 'k1', NULL)", [(shared_with,), (lid,)])
        db.commit()
        db.close()
        return added

    def test_new_library_on_the_host_of_a_discovered_service_library_stops_until_kept(self):
        # cycle 13 (plan spec R2-7): 100002 is in the service already; before, only registered hosts were checked
        self.run_seed()
        self.add_library_on_a_host(100020, '강동대학교', 'http://kd.example.ac.kr', 100002)
        code, out = self.run_seed()
        self.assertEqual(code, 2)
        self.assertIn('same host, new library against the service and the other new ones: 1 pairs, '
                      'with shared item keys 1, not read yet 1\n  100020 강동대학교', out)
        write_tsv(self.rules + 'same_host_kept.tsv', EMPTY_RULES['same_host_kept.tsv'],
                  [{'collection_id': 100020, 'name': '강동대학교', 'decided_on': '2026-10-12'}])
        code, out = self.run_seed()
        self.assertEqual(code, 0)

    def test_two_new_libraries_on_one_host_stop(self):
        # like Yeoju 100584 / 102084 (plan spec R2-7-1): both new, one host
        added = DISCOVERED + [(100020, '여주대학교', 'YES24_V2', 'http://y.example.ac.kr', 'UNIV', None, True),
                              (100021, '여주대학교', 'KYOBO_V1', 'http://y.example.ac.kr:8091', 'UNIV', None, True)]
        self.write_snapshot(added)
        self.write_inputs(added)
        db = sqlite3.connect(self.snapshot)
        db.executemany("INSERT INTO holding VALUES (?, 'k1', NULL)", [(100020,), (100021,)])
        db.commit()
        db.close()
        code, out = self.run_seed()
        self.assertEqual(code, 2)
        self.assertIn('  100021 여주대학교 http://y.example.ac.kr:8091 ~ 100020 여주대학교', out)

    def test_new_libraries_are_listed_with_their_values(self):
        self.run_seed()
        self.add_library_on_a_host(100020, '새대학교', 'http://new.example.ac.kr', 100002)
        code, out = self.run_seed()
        self.assertEqual(code, 0)  # another host: no pair
        self.assertRegex(out, r'new libraries: 1\n.*\n  \d+\t100020\t새대학교\tYES24_V2\tYES24\tOWNED\t1\t'
                              r'MEMBERS_ONLY\thttp://new.example.ac.kr\tNULL\t-\n')
        self.assertIn('join surveyed on: 2026-10-05', out)

    def test_rules_file_without_a_column_stops(self):
        write_tsv(self.rules + 'same_site.tsv', ['collection_id', 'name'], [])
        with self.assertRaisesRegex(SystemExit, r"same_site.tsv: missing columns \['library_id'\]"):
            self.run_seed()

    def test_public_join_without_resolved_rules_stops(self):
        rows = list(csv.DictReader(io.StringIO(text(self.rules + 'public_join.tsv')), delimiter='\t'))
        for row in rows:
            row['rules'] = ''
        write_tsv(self.rules + 'public_join.tsv', JOIN_HEADER, rows)
        with self.assertRaisesRegex(SystemExit, 'run scripts/library/resolve_regions.py'):
            self.run_seed()

    def test_out_under_backend_is_refused(self):
        with self.assertRaisesRegex(SystemExit, 'must not be under backend/'):
            seed.main(self.snapshot, rules_dir=self.rules, out_dir='backend/build/x')
        self.assertFalse(os.path.exists('backend/build/x'))

    def test_new_ids_start_after_the_last_identity_restart(self):
        self.run_seed()
        last = max(self.libraries())
        # an applied changelog moved the identity past ids left empty on purpose (like 2664-2674, ADR-0067)
        with open(self.patches['LIBRARY_DIR'] + '008-library-seed-candidates.yaml', 'w', encoding='utf-8') as f:
            f.write(f'sql: ALTER TABLE library.library ALTER COLUMN id RESTART WITH {last + 1}\n'
                    f'sql: ALTER TABLE library.library ALTER COLUMN id RESTART WITH {last + 10}\n')
        added = DISCOVERED + [(100013, '새도서관', 'YES24_V2', 'http://new.yes24library.com', 'ORG', None, True),
                              (100012, '둘째도서관', 'YES24_V2', 'http://new2.yes24library.com', 'ORG', None, True)]
        self.write_snapshot(added)
        self.write_inputs(added)
        self.run_seed()
        links = {int(r['collection_library_id']): int(r['library_id']) for r in self.read('LINK_CSV')}
        self.assertEqual((links[100012], links[100013]), (last + 10, last + 11))  # collection id order
        # a restart below the highest id does not move the numbers back
        with open(self.patches['LIBRARY_DIR'] + '008-library-seed-candidates.yaml', 'w', encoding='utf-8') as f:
            f.write('sql: ALTER TABLE library.library ALTER COLUMN id RESTART WITH 3\n')
        self.assertEqual(seed.identity_restart(), 3)
        added.append((100014, '셋째도서관', 'YES24_V2', 'http://new3.yes24library.com', 'ORG', None, True))
        self.write_snapshot(added)
        self.write_inputs(added)
        self.run_seed()
        links = {int(r['collection_library_id']): int(r['library_id']) for r in self.read('LINK_CSV')}
        self.assertEqual(links[100014], last + 12)

    def test_new_library_without_a_final_origin_stops(self):
        rows = [r for r in csv.DictReader(io.StringIO(text(self.rules + 'final_origins.tsv')), delimiter='	')
                if r['listed_base_url'] not in ('http://m.yes24library.com', 'http://gana.yes24library.com')]
        write_tsv(self.rules + 'final_origins.tsv', EMPTY_RULES['final_origins.tsv'], rows)
        with self.assertRaisesRegex(SystemExit, r'without a final_origins.tsv row \(collection ids\): '
                                    r'\[100005, 100007\]'):
            self.run_seed()
        # a library already in the service needs no row
        write_tsv(self.rules + 'final_origins.tsv', EMPTY_RULES['final_origins.tsv'], rows + [
            {'listed_base_url': u, 'base_url': u} for u in ('http://m.yes24library.com', 'http://gana.yes24library.com')])
        self.run_seed()
        write_tsv(self.rules + 'final_origins.tsv', EMPTY_RULES['final_origins.tsv'], [])
        code, _out = self.run_seed()
        self.assertEqual(code, 0)

    def test_final_origin_gives_base_and_homepage_url(self):
        rows = [{'listed_base_url': 'http://m.yes24library.com', 'base_url': 'https://m.yes24library.com:446',
                 'checked_on': '2026-10-12'}]
        rows += [{'listed_base_url': c[3], 'base_url': c[3]} for c in DISCOVERED if c[3] != 'http://m.yes24library.com']
        write_tsv(self.rules + 'final_origins.tsv', EMPTY_RULES['final_origins.tsv'], rows)
        self.run_seed()
        lib = next(v for v in self.libraries().values() if v['name'] == '모름도서관')
        self.assertEqual((lib['base_url'], lib['homepage_url']),
                         ('https://m.yes24library.com:446', 'https://m.yes24library.com:446'))


class RulesFolderTest(unittest.TestCase):
    """The committed rules folder (scripts/library/rules/) loads and is up to date."""

    def test_real_rules_folder(self):
        rules = seed.load_rules()
        self.assertEqual(rules['join_surveyed_on'], '2026-10-05')
        self.assertTrue({100584, 102084} <= rules['same_host_kept'])  # plan spec R2-7-1
        self.assertEqual(len(rules['same_host_kept']), 24)
        self.assertEqual(rules['same_site'][100668], 279)
        self.assertEqual(set(rules['demo_or_closed']), {100048, 100068, 100083, 100301})
        self.assertEqual(rules['joins'][100008]['rules'], [('RESIDENCE', 41), ('WORK', 41), ('SCHOOL', 41)])
        self.assertEqual(rules['joins'][100008]['eligibility_summary'], '경기 거주·재직·재학')

    def test_public_join_regions_are_up_to_date(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = resolve_regions.main(['--check'])
        self.assertEqual(code, 0, out.getvalue())


if __name__ == '__main__':
    unittest.main()
