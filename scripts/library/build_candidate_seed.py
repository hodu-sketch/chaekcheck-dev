"""Builds the candidate library seed: step "넣기" of the library collection (docs/maintenance/1-libraries.md, ADR-0052).

Usage (from the repository root):
  python scripts/library/build_candidate_seed.py [--changes-only <db-state.tsv>] <snapshot.sqlite> <public-join.tsv>
      <candidate-kinds.tsv> <kyobo-probe.tsv> <bookcube-volume.tsv>

  snapshot.sqlite      collection DB copy, opened read-only (e.g. chaekcheck-data/snapshots/ebook-catalog-2026-10-05-0745.sqlite)
  public-join.tsv      join research of the candidate public libraries (library-join/public-join-2026-10-05.tsv)
  candidate-kinds.tsv  kind of every candidate (library-join/candidate-kinds-2026-10-05.tsv)
  kyobo-probe.tsv      KYOBO institution probe; its "model" column gives lending_model
                       (other-platforms/kyobo-institutions-probe-2026-10-03.tsv)
  bookcube-volume.tsv  BOOKCUBE totalItem/totalVolume per collection id (scripts/library/bookcube-volume-2026-10-11.tsv,
                       plan cycle 09); totalVolume 0 with books listed -> SUBSCRIPTION
  --changes-only       write no seed file; print what a changeset must change instead (plan cycle 09 C2: applied seed
                       files stay as they are until the clean-up just before the first deployment). db-state.tsv is the
                       local db's library table without a header: id, name, platform_type, lending_model ('' for NULL),
                       enabled. Its names are the old names of the UPDATE, since earlier changesets (013) renamed some.

Writes, under backend/src/main/resources/db/changelog/:
  library/008-library-candidates.csv           new service libraries (ids after the registered ones)
  library/008-eligibility-rule-candidates.csv  their join rules
  collection/002-collection-link.csv           collection id -> service id: registered libraries (same id) + candidates
and prints the counts and the lending_model values of registered libraries (they go in library/008 by hand).
With --changes-only it writes none of them (see above).

Rules (plan handoff cycle 07, C5):
- Candidates: collection id >= 100001, no crawl_skip, last_full_success_at set. Left out: the demo or closed sites
  (DEMO_OR_CLOSED, user decision 2026-10-05) and the kind TEST.
- Kyobo rental and mixed candidates (KYOBO_SUBSCRIPTION_V1 with the probe model 대여 or 혼합) are left out as
  kyobo_rental_mixed (plan ADR-0067, cycle 08). Only candidates: registered libraries and their lending_model stay.
  The rule is checked before crawl_skip, so it still names them after the collection DB marks them crawl_skip.
  Their service ids leave the link CSV and are never given again.
- Numbers and names stay: a candidate already in the link CSV keeps its service id and name. New candidates get
  "last service id + 1" onward, in collection id order.
- Name changes (cycle 08, R4): scripts/library/name_overrides.tsv gives the new name of a service library after the
  body was renamed, checked against an official source (docs/library-maintenance.md 4-12). It is applied after the
  kept names, so the seed keeps the new name; a name not in the list never changes. The list's old name must be the
  kept name (or the new one already), else the script stops.
- Names are the body's name only (ADR-0071, cycle 09): a new candidate takes the collection DB name as it is; no
  vendor or lending label is added. The overrides apply to registered libraries too, in memory only (their seed files
  are not written).
- Vendor: a value the collection wrote for the library if any, else VENDORS by platform_type (ADR-0071).
- lending_model (spec R3): KYOBO subscription -> the probe model, SUBSCRIPTION when the probe has none; BOOKCUBE ->
  SUBSCRIPTION when totalVolume is 0 and totalItem > 0, else OWNED (no books, no answer); the portal -> NULL; every
  other platform -> OWNED. A registered library keeps its library/008 value.
- order_no: registered libraries and candidates together (disabled ones too) grouped by (name, vendor, lending_model),
  numbered 1, 2, 3 by service id and counted again on every run. Numbers 2 and up are printed and the run ends with
  exit code 3: a person checks the site names (ADR-0071).
- join_survey: public libraries from public-join.tsv (REGION/OPEN -> SURVEYED with rules, UNVERIFIED -> NOT_FOUND,
  AFFILIATION -> MEMBERS_ONLY by user decision 2026-10-05); kinds SCHOOL/UNIV/COMPANY/GOV/ORG -> MEMBERS_ONLY;
  UNKNOWN -> NOT_SURVEYED.
- Same site: a candidate whose host (scheme and port ignored) is a registered library's host is listed with its
  item_key overlap. The script does not leave these out (the threshold is not decided); it stops with exit code 2 so
  a person reads the list (plan handoff step 1).
"""
import csv
import glob
import io
import os
import re
import sqlite3
import sys
from urllib.parse import urlsplit

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apply_join_classification import summary_of  # noqa: E402  same summary wording as the registered libraries
from eligibility_rules import region  # noqa: E402  region name -> region id, same mapping as before

CHANGELOG = 'backend/src/main/resources/db/changelog/'
LIBRARY_DIR = CHANGELOG + 'library/'
LIBRARY_CSV = LIBRARY_DIR + '008-library-candidates.csv'
RULE_CSV = LIBRARY_DIR + '008-eligibility-rule-candidates.csv'
LINK_CSV = CHANGELOG + 'collection/002-collection-link.csv'
REGIONS = CHANGELOG + 'region/002-region-seed.csv'
FIRST_CANDIDATE_ID = 100001
# Final addresses after redirects (docs/library-maintenance.md 4-7 step 1), written by CheckFinalOrigins.java:
# the service does not follow redirects, so base_url is the address the site sends the search to.
FINAL_ORIGINS = 'scripts/library/final-origins.tsv'
NAME_OVERRIDES = 'scripts/library/name_overrides.tsv'

# User decision 2026-10-05 (plan easy/2026-10-05-candidate-join-decisions.html 6번): demo or closed sites
DEMO_OR_CLOSED = {100068: '삼각산어린이도서관', 100048: '대구동구안심도서관', 100301: '순창군립도서관 북라이브',
                  100083: '양평군립도서관 전자도서관'}
# User decision 2026-10-05: the same KYOBO site as a registered library, opened on another port (the item lists and
# the detail addresses are equal but for the port) -> left out. candidate id: registered id
SAME_SITE = {100668: 279, 100681: 2, 100701: 172, 100714: 3, 100722: 176, 100755: 136, 100761: 165, 100788: 166}
# Same host as a registered library with some item_key overlap, read on 2026-10-05 and kept: other sites of the same
# body (BOOKCUBE next to KYOBO/YES24, front pages open) or different libraries on one server (overlap 26% or less).
SAME_HOST_KEPT = {100006, 100022, 100024, 100037, 100058, 100070, 100072, 100074, 100082, 100085, 100115, 100519,
                  100543, 100568, 100573, 100590, 100594, 100608, 100615, 100661, 100665, 100747}
MEMBERS_ONLY_KINDS = {'SCHOOL', 'UNIV', 'COMPANY', 'GOV', 'ORG'}
# platform_type -> vendor (ADR-0071; the values of the backend's library.Vendor)
VENDORS = {'KYOBO_V1': 'KYOBO', 'KYOBO_SUBSCRIPTION_V1': 'KYOBO', 'YES24_V1': 'YES24', 'YES24_V2': 'YES24',
           'YES24_V3': 'YES24', 'BOOKCUBE_FX': 'BOOKCUBE', 'ALADIN_V1': 'ALADIN', 'ELIB_PORTAL_V1': 'PORTAL',
           'GANGNAM_V1': 'IN_HOUSE'}
# TODO(확인 필요): where the collection writes a vendor of its own (to be decided with the collection session). Until
# then no library has one. collection id -> vendor
COLLECTION_VENDORS = {}
LENDING_MODELS = {'소장': 'OWNED', '혼합': 'MIXED', '대여': 'RENTAL'}
BASES = {'거주': 'RESIDENCE', '재직': 'WORK', '재학': 'SCHOOL'}
JOIN_METHOD_UNKNOWN = '도서관 홈페이지의 회원가입 안내 확인'

LIBRARY_FIELDS = ['id', 'name', 'platform_type', 'base_url', 'eligibility_summary', 'extra_condition_text',
                  'visit_required', 'join_method', 'join_url', 'join_caution', 'join_notice', 'homepage_url',
                  'holdings_count', 'holdings_counted_on', 'deferred', 'join_survey', 'lending_model']


def scope_rules(residence, work_school):
    rules = [('RESIDENCE', region(n)) for n in residence]
    rules += [(b, region(n)) for n in work_school for b in ('WORK', 'SCHOOL')]
    assert all(r[1] for r in rules), (residence, work_school)
    return rules


# Scopes whose residence and work/school areas differ, read from the quoted text in public-join.tsv.
# Areas smaller than a 시·군·구 (면, 혁신도시) cannot be rules and are left out.
SCOPE_OVERRIDES = {
    # "서울시에 주민등록이 되어 있는 자, 광명시에 주민등록이 되어 있는 자 / 서울시 소재 학교에 재학 중이거나 서울시 소재 직장에 재직 중인 자"
    '100031': scope_rules(['서울', '광명시'], ['서울']),
    # "경기도에 주민등록이 되어 있는 자 / 김포시 소재 직장 또는 학교에 재직하거나 재학 중인 자"
    '100036': scope_rules(['경기'], ['김포시']),
    # "주민등록상 경기도, 송파구민 …", "경기도, 송파구 소재 직장인이나 학생(방문만 가능, 증명서 필요)"
    '100071': scope_rules(['경기', '송파구'], ['경기', '송파구']),
    '100072': scope_rules(['경기', '송파구'], ['경기', '송파구']),
    # "경기도민", "경기도민 외 수원시 소재 학교에 재학 중인 학생", "경기도민 외 수원시 소재 직장에 재직 중인 직장인"
    '100070': scope_rules(['경기'], ['수원시']),
    # "증평군 주민등록자 증평군 소재 학교·직장에 재학·재직하는 사람 … 인접생활권 주민등록자(청주시 북이면, …)": 면은 뺌
    '100111': scope_rules(['증평군'], ['증평군']),
    # "충청북도나 인접 시·군(천안시, 안성시)에 주민등록 … 진천군이나 충북 혁신도시에 소재한 직장 … 학교": 혁신도시는 뺌
    '100114': scope_rules(['충북', '천안시', '안성시'], ['진천군']),
    # "천안시에 주소를 둔 천안시민 … 천안시 소재 재직자 및 재학생 … 천안 인접지역 시민(세종, 아산, 공주, 평택, 안성, 청주, 진천)"
    '100117': scope_rules(['천안시', '세종', '아산시', '공주시', '평택시', '안성시', '청주시', '진천군'], ['천안시']),
}
SUMMARY_OVERRIDES = {}


def read_tsv(path):
    return list(csv.DictReader(open(path, encoding='utf-8'), delimiter='\t'))


def read_csv(path):
    return list(csv.DictReader(open(path, encoding='utf-8'))) if os.path.exists(path) else []


def write_csv(path, fields, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out = io.StringIO()
    w = csv.DictWriter(out, fieldnames=fields, lineterminator='\n')
    w.writeheader()
    w.writerows(rows)
    open(path, 'w', encoding='utf-8', newline='').write(out.getvalue())


def registered_libraries():
    """Registered libraries {id: (name, platform_type)} from the seed files before 008 (CSVs and the pilot SQL in 002)."""
    found = {}
    for path in sorted(glob.glob(LIBRARY_DIR + '*-library*.csv')):
        if os.path.basename(path).startswith('008-'):
            continue
        rows = list(csv.DictReader(open(path, encoding='utf-8')))
        if rows and 'name' not in rows[0]:  # e.g. 009-library-logos.csv
            continue
        for row in rows:
            found[int(row['id'])] = (row['name'], row['platform_type'])
    pilot = open(LIBRARY_DIR + '002-library-seed.yaml', encoding='utf-8').read()
    for lid, name, platform in re.findall(r"\((\d+), '([^']+)', '([A-Z0-9_]+)', 'https?://", pilot):
        found[int(lid)] = (name, platform)
    return found


def registered_lending_models():
    """{id: lending_model} of registered libraries from the library/008 UPDATE (the KYOBO probe values)."""
    path = LIBRARY_DIR + '008-library-seed-candidates.yaml'
    if not os.path.exists(path):
        return {}
    text = open(path, encoding='utf-8').read()
    return {int(lid): m for lid, m in re.findall(r"\((\d+), '(OWNED|SUBSCRIPTION|MIXED|RENTAL)'\)", text)}


def read_bookcube_volumes(path):
    """{collection id: (totalItem, totalVolume)}, None for a site that did not answer."""
    volumes = {}
    for r in read_tsv(path):
        items, volume = r['totalItem'].strip(), r['totalVolume'].strip()
        volumes[int(r['id'])] = (int(items), int(volume)) if items and volume else None
    return volumes


def lending_model_of(platform, probe_model, volume):
    """spec R3 (cycle 09). volume is (totalItem, totalVolume) or None."""
    if platform == 'ELIB_PORTAL_V1':
        return None
    if platform == 'KYOBO_SUBSCRIPTION_V1':
        return probe_model or 'SUBSCRIPTION'
    if platform == 'BOOKCUBE_FX':
        return 'SUBSCRIPTION' if volume and volume[1] == 0 and volume[0] > 0 else 'OWNED'
    return 'OWNED'


def assign_order_no(rows):
    """Sets row['order_no'] in each (name, vendor, lending_model) group by service id; returns [(id, n)] for n >= 2."""
    groups = {}
    second = []
    for row in sorted(rows, key=lambda r: r['id']):
        key = (row['name'], row['vendor'], row['lending_model'])
        groups[key] = groups.get(key, 0) + 1
        row['order_no'] = groups[key]
        if row['order_no'] > 1:
            second.append((row['id'], row['order_no']))
    return second


def host_of(url):
    return (urlsplit(url).hostname or '').lower()


def rules_of(join):
    """[(basis, region id | 'NATIONWIDE')] of one public-join row."""
    if join['eligibility'] in ('OPEN', 'OPEN_CAUTION'):
        return [('RESIDENCE', 'NATIONWIDE')]
    if join['id'] in SCOPE_OVERRIDES:
        return SCOPE_OVERRIDES[join['id']]
    bases = [BASES[b] for b in BASES if b in join['basis']]
    regions = [region(t) for t in re.split(r'[,/]', join['scope']) if t.strip()]
    if not bases or not regions or None in regions:
        raise SystemExit(f"cannot read scope of {join['id']} {join['name']}: {join['basis']} | {join['scope']}")
    return [(b, r) for r in dict.fromkeys(regions) for b in bases]


def join_columns(candidate, kind, join, region_names):
    """(library columns, rules) for the join part of one candidate."""
    empty = {'eligibility_summary': 'NULL', 'extra_condition_text': 'NULL', 'visit_required': 'false',
             'join_method': 'NULL', 'join_url': 'NULL', 'join_caution': 'NULL'}
    if join is None:
        if kind == 'UNKNOWN':
            return dict(empty, join_survey='NOT_SURVEYED'), []
        assert kind in MEMBERS_ONLY_KINDS, (candidate['id'], kind)
        return dict(empty, join_survey='MEMBERS_ONLY'), []
    text = {k: (join[k].strip() or 'NULL') for k in ('join_method', 'extra_condition', 'join_caution', 'source_url')}
    visit = 'true' if join['visit_required'] == 'true' else 'false'
    eligibility = join['eligibility']
    if eligibility == 'UNVERIFIED':
        method = text['join_method'] if text['join_method'] != JOIN_METHOD_UNKNOWN else 'NULL'
        return dict(empty, join_survey='NOT_FOUND', join_method=method), []
    if eligibility == 'AFFILIATION':  # user decision 2026-10-05: members of an institution only
        return dict(empty, join_survey='MEMBERS_ONLY', visit_required=visit, join_method=text['join_method'],
                    join_url=text['source_url'], join_caution=text['join_caution']), []
    assert eligibility in ('REGION', 'OPEN', 'OPEN_CAUTION'), (candidate['id'], eligibility)
    rules = rules_of(join)
    summary = SUMMARY_OVERRIDES.get(join['id'], 'NULL')
    if eligibility == 'REGION' and rules:
        summary = summary_of([(b, 'REGION', r) for b, r in rules], region_names)
    return {'eligibility_summary': summary, 'extra_condition_text': text['extra_condition'], 'visit_required': visit,
            'join_method': text['join_method'] if text['join_method'] != 'NULL' else JOIN_METHOD_UNKNOWN,
            'join_url': text['source_url'], 'join_caution': text['join_caution'], 'join_survey': 'SURVEYED'}, rules


def read_name_overrides():
    """{service id: (old name, new name)} from NAME_OVERRIDES; empty when the file is missing."""
    if not os.path.exists(NAME_OVERRIDES):
        return {}
    return {int(r['library_id']): (r['old_name'], r['new_name']) for r in read_tsv(NAME_OVERRIDES)}


def apply_name_overrides(names, service_ids, registered_names):
    """Puts the listed new names in place: names is keyed by collection id, registered_names by service id (changed in
    memory only). Stops on a stale entry. Equal names are told apart by vendor, lending_model and order_no (ADR-0071)."""
    overrides = read_name_overrides()
    by_service = {sid: cid for cid, sid in service_ids.items()}
    for sid, (old, new) in overrides.items():
        if sid in by_service:
            target, key = names, by_service[sid]
        elif sid in registered_names:
            target, key = registered_names, sid
        else:
            raise SystemExit(f'name override for {sid}: not a library in this seed')
        if target[key] not in (old, new):
            raise SystemExit(f'name override for {sid}: the seed name is {target[key]!r}, the list says {old!r}')
        target[key] = new


def kyobo_rental_mixed(row, models):
    """A Kyobo subscription site whose probe model is rental or mixed (ADR-0067)."""
    return (row['platform_type'] == 'KYOBO_SUBSCRIPTION_V1'
            and models.get(row['base_url'].rstrip('/')) in ('RENTAL', 'MIXED'))


def same_site(db, candidates, registered_rows):
    """Candidates whose host equals a registered library's host, with the item_key overlap."""
    by_host = {}
    for r in registered_rows:
        by_host.setdefault(host_of(r['base_url']), []).append(r)
    found = []
    for c in candidates:
        for r in by_host.get(host_of(c['base_url']), []):
            keys_c = db.execute('SELECT count(*) FROM holding WHERE library_id = ? AND removed_at IS NULL',
                                (c['id'],)).fetchone()[0]
            keys_r = db.execute('SELECT count(*) FROM holding WHERE library_id = ? AND removed_at IS NULL',
                                (r['id'],)).fetchone()[0]
            shared = db.execute('SELECT count(*) FROM holding a JOIN holding b ON b.library_id = ? AND '
                                'b.item_key = a.item_key AND b.removed_at IS NULL '
                                'WHERE a.library_id = ? AND a.removed_at IS NULL', (r['id'], c['id'])).fetchone()[0]
            found.append((c, r, keys_c, keys_r, shared))
    return found


def main(snapshot, public_join, kinds_tsv, probe_tsv, volume_tsv, db_state=None):
    db = sqlite3.connect(f'file:{snapshot}?mode=ro', uri=True)
    db.row_factory = sqlite3.Row
    rows = [dict(r) for r in db.execute('SELECT id, name, platform_type, base_url, last_full_success_at, crawl_skip '
                                        'FROM library ORDER BY id')]
    registered = registered_libraries()
    registered_names = {lid: name for lid, (name, _platform) in registered.items()}
    volumes = read_bookcube_volumes(volume_tsv)
    region_names = {int(r['id']): (r['short_name'] if r['short_name'] != 'NULL' else r['name'])
                    for r in csv.DictReader(open(REGIONS, encoding='utf-8'))}
    kinds = {int(r['id']): r['kind'] for r in read_tsv(kinds_tsv)}
    joins = {int(r['id']): r for r in read_tsv(public_join)}
    models = {}
    for r in read_tsv(probe_tsv):
        if r['platform'] == 'KYOBO_SUBSCRIPTION_V1' and r['model'] in LENDING_MODELS:
            models[(r['base_url'] or r['url']).rstrip('/')] = LENDING_MODELS[r['model']]

    finals = {r['listed_base_url']: r['base_url'] for r in read_tsv(FINAL_ORIGINS)}         if os.path.exists(FINAL_ORIGINS) else {}

    left_out = {'kyobo_rental_mixed': [], 'crawl_skip': [], 'no_full_success': [], 'demo_or_closed': [],
                'same_site': [], 'test': []}
    candidates = []
    for row in rows:
        if row['id'] < FIRST_CANDIDATE_ID:
            continue
        if kyobo_rental_mixed(row, models):
            left_out['kyobo_rental_mixed'].append(row)
        elif row['crawl_skip']:
            left_out['crawl_skip'].append(row)
        elif not row['last_full_success_at']:
            left_out['no_full_success'].append(row)
        elif row['id'] in DEMO_OR_CLOSED:
            left_out['demo_or_closed'].append(row)
        elif row['id'] in SAME_SITE:
            left_out['same_site'].append(row)
        elif kinds.get(row['id']) == 'TEST':
            left_out['test'].append(row)
        else:
            if row['id'] not in kinds:
                raise SystemExit(f"no kind for candidate {row['id']} {row['name']}")
            candidates.append(row)

    registered_rows = [r for r in rows if r['id'] < FIRST_CANDIDATE_ID]
    clashes = same_site(db, candidates, registered_rows)
    db.close()

    # keep numbers and names already given (plan handoff C5: they never change)
    kept_links = {int(r['collection_library_id']): int(r['library_id']) for r in read_csv(LINK_CSV)
                  if int(r['collection_library_id']) >= FIRST_CANDIDATE_ID}
    kept_names = {int(r['id']): r['name'] for r in read_csv(LIBRARY_CSV)}
    kept_names = {cid: kept_names[sid] for cid, sid in kept_links.items() if sid in kept_names}
    candidate_ids = {c['id'] for c in candidates}
    # ADR-0067: the Kyobo rental and mixed candidates leave the service; their ids are not given again (the new ids
    # start after the highest id ever linked, below)
    rental_mixed = {row['id'] for row in left_out['kyobo_rental_mixed']}
    dropped = sorted(set(kept_links) - candidate_ids - rental_mixed)
    if dropped:
        raise SystemExit(f'candidates in the link CSV are no longer candidates (ids never move): {dropped}')
    last_id = max([*registered, *kept_links.values()])
    kept_links = {cid: sid for cid, sid in kept_links.items() if cid in candidate_ids}
    kept_names = {cid: name for cid, name in kept_names.items() if cid in candidate_ids}
    new = [c for c in candidates if c['id'] not in kept_links]
    names = dict(kept_names)
    names.update({c['id']: c['name'] for c in new})
    service_ids = dict(kept_links)
    for c in new:
        last_id += 1
        service_ids[c['id']] = last_id
    apply_name_overrides(names, service_ids, registered_names)

    libraries, rules = [], []
    for c in sorted(candidates, key=lambda c: service_ids[c['id']]):
        sid = service_ids[c['id']]
        columns, library_rules = join_columns(c, kinds[c['id']], joins.get(c['id']), region_names)
        probe = models.get(c['base_url'].rstrip('/')) if c['platform_type'] == 'KYOBO_SUBSCRIPTION_V1' else None
        model = lending_model_of(c['platform_type'], probe, volumes.get(c['id']))
        base_url = finals.get(c['base_url'], c['base_url'])
        libraries.append(dict(columns, id=sid, name=names[c['id']], platform_type=c['platform_type'],
                              base_url=base_url, join_notice='NULL', homepage_url=base_url,
                              holdings_count='NULL', holdings_counted_on='NULL', deferred='false',
                              lending_model=model or 'NULL'))
        for basis, scope in library_rules:
            rules.append({'library_id': sid, 'basis': basis,
                          'scope_type': 'NATIONWIDE' if scope == 'NATIONWIDE' else 'REGION',
                          'scope_region_id': 'NULL' if scope == 'NATIONWIDE' else scope})
    links = [{'collection_library_id': lid, 'library_id': lid} for lid in sorted(registered)]
    links += [{'collection_library_id': cid, 'library_id': service_ids[cid]} for cid in sorted(service_ids)]

    # classification of every library: registered and candidates, disabled ones too (ADR-0071)
    registered_models = registered_lending_models()
    classified = [{'id': lid, 'collection_id': lid, 'name': registered_names[lid], 'platform_type': platform,
                   'vendor': COLLECTION_VENDORS.get(lid, VENDORS[platform]),
                   'lending_model': registered_models.get(lid) or lending_model_of(platform, None, volumes.get(lid))}
                  for lid, (_name, platform) in registered.items()]
    collection_ids = {sid: cid for cid, sid in service_ids.items()}
    for lib in libraries:
        cid = collection_ids[lib['id']]
        classified.append({'id': lib['id'], 'collection_id': cid, 'name': lib['name'],
                           'platform_type': lib['platform_type'],
                           'vendor': COLLECTION_VENDORS.get(cid, VENDORS[lib['platform_type']]),
                           'lending_model': None if lib['lending_model'] == 'NULL' else lib['lending_model']})
    second = assign_order_no(classified)

    if db_state is None:
        write_csv(LIBRARY_CSV, LIBRARY_FIELDS, libraries)
        write_csv(RULE_CSV, ['library_id', 'basis', 'scope_type', 'scope_region_id'], rules)
        write_csv(LINK_CSV, ['collection_library_id', 'library_id'], links)
    else:
        print_changes(classified, read_db_state(db_state))

    def count(values):
        out = {}
        for v in values:
            out[v] = out.get(v, 0) + 1
        return ', '.join(f'{k} {v}' for k, v in sorted(out.items(), key=lambda kv: -kv[1]))

    print(f'candidates in the seed: {len(libraries)} (service ids {min(service_ids.values())}..{last_id}, '
          f'kept {len(kept_links)}, new {len(new)}), rules {len(rules)}, links {len(links)}')
    print(f'left out: ' + ', '.join(f'{k} {len(v)}' for k, v in left_out.items()))
    for reason in ('demo_or_closed', 'same_site', 'test'):
        for row in left_out[reason]:
            pair = f' = registered {SAME_SITE[row["id"]]}' if reason == 'same_site' else ''
            print(f"  {row['id']} {row['name']} ({reason}{pair})")
    print('join_survey: ' + count(lib['join_survey'] for lib in libraries))
    print('lending_model: ' + count(lib['lending_model'] for lib in libraries))
    print('platform: ' + count(lib['platform_type'] for lib in libraries))
    print('all libraries by vendor: ' + count(row['vendor'] for row in classified))
    print('all libraries by lending_model: ' + count(row['lending_model'] or 'NULL' for row in classified))
    print('BOOKCUBE SUBSCRIPTION service ids: ' + ', '.join(
        str(row['id']) for row in sorted(classified, key=lambda r: r['id'])
        if row['vendor'] == 'BOOKCUBE' and row['lending_model'] == 'SUBSCRIPTION'))
    print(f'order_no 2 and up: {len(second)}' + ''.join(f'\n  ({lid}, {n})' for lid, n in second))
    probe_registered = []
    for r in registered_rows:
        if r['platform_type'] == 'KYOBO_SUBSCRIPTION_V1' and r['base_url'].rstrip('/') in models:
            probe_registered.append((r['id'], models[r['base_url'].rstrip('/')]))
    print('registered lending_model (library/008 UPDATE): '
          + ', '.join(f"({lid}, '{m}')" for lid, m in sorted(probe_registered)))
    overlapping = [x for x in clashes if x[4] > 0]
    unread = [x for x in overlapping if x[0]['id'] not in SAME_HOST_KEPT]
    print(f'same host as a registered library: {len(clashes)} pairs, with shared item keys {len(overlapping)}, '
          f'not read yet {len(unread)}')
    if unread:
        for c, r, keys_c, keys_r, shared in unread:
            print(f"  {c['id']} {c['name']} {c['base_url']} ~ {r['id']} {r['name']} {r['base_url']}: "
                  f"keys {keys_c} / {keys_r}, shared {shared} "
                  f"({shared / keys_c:.0%} of candidate, {shared / keys_r if keys_r else 0:.0%} of registered)")
        return 2
    return 3 if second else 0


def read_db_state(path):
    """{id: (name, platform_type, lending_model or None)} from the db-state export (see the usage)."""
    state = {}
    for line in open(path, encoding='utf-8'):
        lid, name, platform, model, _enabled = line.rstrip('\n').split('\t')
        state[int(lid)] = (name, platform, model or None)
    return state


def sql_text(value):
    return "'" + value.replace("'", "''") + "'"


def print_changes(classified, state):
    """What a changeset must change from the db state (plan cycle 09 step 2-6), and the list for the collection."""
    rows = {row['id']: row for row in classified}
    missing, extra = sorted(set(rows) - set(state)), sorted(set(state) - set(rows))
    if missing or extra:
        raise SystemExit(f'db state and seed differ: not in the db {missing}, not in the seed {extra}')
    platform = sorted(lid for lid in rows if rows[lid]['platform_type'] != state[lid][1])
    if platform:
        raise SystemExit(f'platform_type differs from the db: {platform}')
    renamed = [lid for lid in sorted(rows) if rows[lid]['name'] != state[lid][0]]
    print(f'-- name UPDATE VALUES (id, old_name, new_name): {len(renamed)}')
    print(',\n'.join(f"({lid}, {sql_text(state[lid][0])}, {sql_text(rows[lid]['name'])})" for lid in renamed))
    fill = [lid for lid in sorted(rows) if state[lid][2] is None and rows[lid]['lending_model'] is not None]
    print(f'-- lending_model UPDATE where NULL (id, lending_model): {len(fill)}')
    print(',\n'.join(f"({lid}, '{rows[lid]['lending_model']}')" for lid in fill))
    differs = [lid for lid in sorted(rows) if state[lid][2] is not None and state[lid][2] != rows[lid]['lending_model']]
    print(f'-- lending_model in the db that the rules would set otherwise (left as it is): {len(differs)}'
          + ''.join(f"\n  {lid} db {state[lid][2]} rules {rows[lid]['lending_model']}" for lid in differs))
    own = [lid for lid in sorted(rows) if rows[lid]['vendor'] != VENDORS[rows[lid]['platform_type']]]
    print(f'-- vendor other than the platform table: {len(own)}'
          + ''.join(f"\n  {lid} {rows[lid]['vendor']}" for lid in own))
    print(f'-- for the collection session (service id, collection id, final name): {len(renamed)}')
    for lid in renamed:
        print(f"{lid}\t{rows[lid]['collection_id']}\t{rows[lid]['name']}")


if __name__ == '__main__':
    args = sys.argv[1:]
    db_state = None
    if args[:1] == ['--changes-only'] and len(args) > 1:
        db_state, args = args[1], args[2:]
    if len(args) != 5:
        raise SystemExit(__doc__)
    sys.stdout.reconfigure(encoding='utf-8')
    sys.exit(main(*args, db_state=db_state))
