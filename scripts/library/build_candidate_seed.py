"""Builds the candidate library seed: step "넣기" of the library collection (docs/maintenance/1-libraries.md, ADR-0052).

Usage (from the repository root):
  python scripts/library/build_candidate_seed.py <snapshot.sqlite> <public-join.tsv> <candidate-kinds.tsv> <kyobo-probe.tsv>

  snapshot.sqlite      collection DB copy, opened read-only (e.g. chaekcheck-data/snapshots/ebook-catalog-2026-10-05-0745.sqlite)
  public-join.tsv      join research of the candidate public libraries (library-join/public-join-2026-10-05.tsv)
  candidate-kinds.tsv  kind of every candidate (library-join/candidate-kinds-2026-10-05.tsv)
  kyobo-probe.tsv      KYOBO institution probe; its "model" column gives lending_model
                       (other-platforms/kyobo-institutions-probe-2026-10-03.tsv)

Writes, under backend/src/main/resources/db/changelog/:
  library/008-library-candidates.csv           new service libraries (ids after the registered ones)
  library/008-eligibility-rule-candidates.csv  their join rules
  collection/002-collection-link.csv           collection id -> service id: registered libraries (same id) + candidates
and prints the counts and the lending_model values of registered libraries (they go in library/008 by hand).

Rules (plan handoff cycle 07, C5):
- Candidates: collection id >= 100001, no crawl_skip, last_full_success_at set. Left out: the demo or closed sites
  (DEMO_OR_CLOSED, user decision 2026-10-05) and the kind TEST.
- Numbers and names stay: a candidate already in the link CSV keeps its service id and name. New candidates get
  "last service id + 1" onward, in collection id order.
- Name clash (with a registered library or another candidate): "이름 (북큐브|교보|YES24|알라딘)", still clashing:
  "이름 (라벨, 호스트)".
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
LABELS = {'BOOKCUBE_FX': '북큐브', 'KYOBO_V1': '교보', 'KYOBO_SUBSCRIPTION_V1': '교보', 'YES24_V1': 'YES24',
          'YES24_V2': 'YES24', 'YES24_V3': 'YES24', 'ALADIN_V1': '알라딘'}
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
    """Registered libraries {id: name} from the seed files before 008 (CSVs and the pilot SQL in 002)."""
    found = {}
    for path in sorted(glob.glob(LIBRARY_DIR + '*-library*.csv')):
        if os.path.basename(path).startswith('008-'):
            continue
        rows = list(csv.DictReader(open(path, encoding='utf-8')))
        if rows and 'name' not in rows[0]:  # e.g. 009-library-logos.csv
            continue
        for row in rows:
            found[int(row['id'])] = row['name']
    pilot = open(LIBRARY_DIR + '002-library-seed.yaml', encoding='utf-8').read()
    for lid, name in re.findall(r"\((\d+), '([^']+)', '[A-Z0-9_]+', 'https?://", pilot):
        found[int(lid)] = name
    return found


def host_of(url):
    return (urlsplit(url).hostname or '').lower()


def host_port_of(url):
    parts = urlsplit(url)
    return parts.hostname.lower() + (f':{parts.port}' if parts.port else '')


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


def name_candidates(new, taken):
    """Names for new candidates (dicts with id, name, platform_type, base_url) that clash with nothing in taken."""
    names = {}
    base_count = {}
    for c in new:
        base_count[c['name']] = base_count.get(c['name'], 0) + 1
    labelled = {}
    for c in new:
        if base_count[c['name']] > 1 or c['name'] in taken:
            labelled[c['id']] = f"{c['name']} ({LABELS[c['platform_type']]})"
    label_count = {}
    for name in labelled.values():
        label_count[name] = label_count.get(name, 0) + 1
    plain = {c['name'] for c in new if c['id'] not in labelled}
    for c in new:
        name = labelled.get(c['id'], c['name'])
        if c['id'] in labelled and (label_count[name] > 1 or name in taken or name in plain):
            name = f"{c['name']} ({LABELS[c['platform_type']]}, {host_of(c['base_url'])})"
        names[c['id']] = name
    # last resort: the port tells two sites on one host apart
    counts = {}
    for name in names.values():
        counts[name] = counts.get(name, 0) + 1
    for c in new:
        if counts[names[c['id']]] > 1 or names[c['id']] in taken:
            names[c['id']] = f"{c['name']} ({LABELS[c['platform_type']]}, {host_port_of(c['base_url'])})"
    final = list(names.values())
    clash = {n for n in final if final.count(n) > 1 or n in taken}
    if clash:
        raise SystemExit('name clash left: ' + ', '.join(sorted(clash)))
    return names


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


def main(snapshot, public_join, kinds_tsv, probe_tsv):
    db = sqlite3.connect(f'file:{snapshot}?mode=ro', uri=True)
    db.row_factory = sqlite3.Row
    rows = [dict(r) for r in db.execute('SELECT id, name, platform_type, base_url, last_full_success_at, crawl_skip '
                                        'FROM library ORDER BY id')]
    registered = registered_libraries()
    region_names = {int(r['id']): (r['short_name'] if r['short_name'] != 'NULL' else r['name'])
                    for r in csv.DictReader(open(REGIONS, encoding='utf-8'))}
    kinds = {int(r['id']): r['kind'] for r in read_tsv(kinds_tsv)}
    joins = {int(r['id']): r for r in read_tsv(public_join)}
    models = {}
    for r in read_tsv(probe_tsv):
        if r['platform'] == 'KYOBO_SUBSCRIPTION_V1' and r['model'] in LENDING_MODELS:
            models[(r['base_url'] or r['url']).rstrip('/')] = LENDING_MODELS[r['model']]

    left_out = {'crawl_skip': [], 'no_full_success': [], 'demo_or_closed': [], 'same_site': [], 'test': []}
    candidates = []
    for row in rows:
        if row['id'] < FIRST_CANDIDATE_ID:
            continue
        if row['crawl_skip']:
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
    dropped = sorted(set(kept_links) - candidate_ids)
    if dropped:
        raise SystemExit(f'candidates in the link CSV are no longer candidates (ids never move): {dropped}')
    last_id = max([*registered, *kept_links.values()])
    new = [c for c in candidates if c['id'] not in kept_links]
    taken = set(registered.values()) | set(kept_names.values())
    names = dict(kept_names)
    names.update(name_candidates(new, taken))
    service_ids = dict(kept_links)
    for c in new:
        last_id += 1
        service_ids[c['id']] = last_id

    libraries, rules = [], []
    for c in sorted(candidates, key=lambda c: service_ids[c['id']]):
        sid = service_ids[c['id']]
        columns, library_rules = join_columns(c, kinds[c['id']], joins.get(c['id']), region_names)
        model = models.get(c['base_url'].rstrip('/')) if c['platform_type'] == 'KYOBO_SUBSCRIPTION_V1' else None
        libraries.append(dict(columns, id=sid, name=names[c['id']], platform_type=c['platform_type'],
                              base_url=c['base_url'], join_notice='NULL', homepage_url=c['base_url'],
                              holdings_count='NULL', holdings_counted_on='NULL', deferred='false',
                              lending_model=model or 'NULL'))
        for basis, scope in library_rules:
            rules.append({'library_id': sid, 'basis': basis,
                          'scope_type': 'NATIONWIDE' if scope == 'NATIONWIDE' else 'REGION',
                          'scope_region_id': 'NULL' if scope == 'NATIONWIDE' else scope})
    links = [{'collection_library_id': lid, 'library_id': lid} for lid in sorted(registered)]
    links += [{'collection_library_id': cid, 'library_id': service_ids[cid]} for cid in sorted(service_ids)]

    write_csv(LIBRARY_CSV, LIBRARY_FIELDS, libraries)
    write_csv(RULE_CSV, ['library_id', 'basis', 'scope_type', 'scope_region_id'], rules)
    write_csv(LINK_CSV, ['collection_library_id', 'library_id'], links)

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
    labelled = [lib['name'] for lib in libraries if lib['name'] != next(c['name'] for c in candidates
                                                                         if service_ids[c['id']] == lib['id'])]
    print(f"names with a label: {sum(1 for n in labelled if ', ' not in n.rsplit('(', 1)[-1])}, "
          f"with label and host: {sum(1 for n in labelled if ', ' in n.rsplit('(', 1)[-1])}")
    registered_models = []
    for r in registered_rows:
        if r['platform_type'] == 'KYOBO_SUBSCRIPTION_V1' and r['base_url'].rstrip('/') in models:
            registered_models.append((r['id'], models[r['base_url'].rstrip('/')]))
    print('registered lending_model (library/008 UPDATE): '
          + ', '.join(f"({lid}, '{m}')" for lid, m in sorted(registered_models)))
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
    return 0


if __name__ == '__main__':
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)
    sys.stdout.reconfigure(encoding='utf-8')
    sys.exit(main(*sys.argv[1:5]))
