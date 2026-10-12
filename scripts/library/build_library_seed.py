"""Works out the service libraries from a collection DB copy, for comparison only (plan cycle 13, R4-3, AC-6).

New libraries go in with the collection command library-insert (plan ADR-0084). This script was the seed builder of
cycles 07-09 (step "넣기", ADR-0052); it now writes no seed file and is kept to compare its result with the command's
preview on the same copy. It reads the same rules folder as the command (ADR-0085).

Usage (from the repository root):
  python scripts/library/build_library_seed.py [--rules <dir>] [--out <dir>] [--changes-only <db-state.tsv>]
      <snapshot.sqlite>

  snapshot.sqlite  collection DB copy, opened read-only (e.g. chaekcheck-data/snapshots/ebook-catalog-2026-10-12-0135.sqlite)
  --rules          rules folder, default scripts/library/rules/ (files and columns: rules/README.md)
  --out            also write the full result as three CSVs in the seed format (libraries.csv, eligibility_rules.csv,
                   links.csv) into this folder, for a diff. Never a folder under backend/.
  --changes-only   print what a changeset must change from the local db (plan cycle 09 C2). db-state.tsv is the
                   local db's library table without a header: id, name, platform_type, lending_model ('' for NULL),
                   enabled. Its names are the old names of the UPDATE, since earlier changesets (013) renamed some.

Prints the counts, the new libraries (not yet in the link seed) with their values, and the checks below.

Reads, besides the rules folder, the applied seed files under backend/src/main/resources/db/changelog/ (read only):
the registered libraries (002 SQL, 003-006 CSVs), library/008-library-candidates.csv and collection/002-collection-link.csv
(numbers and names already given), the library/008 UPDATE (lending_model of registered libraries), region/002 (none now:
public_join.tsv carries region ids and the summary, filled by resolve_regions.py).

Rules (plan handoff cycle 07, C5; cycle 09; cycle 13):
- Discovered libraries: collection id >= 100001 (FIRST_DISCOVERED_ID), no crawl_skip, last_full_success_at set. Left
  out, the first rule that matches: kyobo_rental_mixed, crawl_skip, no full success, demo_or_closed.tsv, same_site.tsv,
  kind TEST (library_kinds.tsv). A library left with no kind stops the run.
- Kyobo rental and mixed sites (KYOBO_SUBSCRIPTION_V1 with the probe lending_model RENTAL or MIXED, kyobo_probe.tsv)
  are left out (plan ADR-0067, cycle 08). Their service ids leave the link seed and are never given again.
- Numbers and names stay: a library already in the link seed keeps its service id and name. New libraries get
  "last service id + 1" onward, in collection id order.
- Name changes (cycle 08, R4): rules/name_overrides.tsv gives the new name of a service library, checked against an
  official source (docs/library-maintenance.md 4-12). It is applied after the kept names; the list's old name must be
  the kept name (or the new one already), else the script stops. Registered libraries change in memory only.
- Names are the body's name only (ADR-0071): a new library takes the collection DB name as it is.
- Vendor: a value the collection wrote for the library if any, else VENDORS by platform_type (ADR-0071).
- lending_model (cycle 09 spec R3): KYOBO subscription -> the probe value, SUBSCRIPTION when the probe has none;
  BOOKCUBE -> SUBSCRIPTION when total_volume is 0 and total_item > 0 (bookcube_volume.tsv), else OWNED; the portal ->
  NULL; every other platform -> OWNED. A registered library keeps its library/008 value.
- base_url and homepage_url: final_origins.tsv maps the listed address to the final address, else the listed one.
- order_no: all service libraries (disabled ones too) grouped by (name, vendor, lending_model), numbered 1, 2, 3 by
  service id. Numbers 2 and up are printed; the run ends with exit code 3 unless order_no_allowed.tsv lists them.
- join_survey: public libraries from public_join.tsv (REGION/OPEN -> SURVEYED with its rules and summary,
  UNVERIFIED -> NOT_FOUND, AFFILIATION -> MEMBERS_ONLY by user decision 2026-10-05); kinds SCHOOL/UNIV/COMPANY/GOV/ORG
  -> MEMBERS_ONLY; UNKNOWN -> NOT_SURVEYED.
- Same host (scheme and port ignored) with shared item keys: every discovered library against the registered ones, and
  (cycle 13, plan spec R2-7) every new library against all service libraries and the other new ones. Pairs whose
  library is in same_host_kept.tsv were read by a person; any other pair with shared keys is printed and the run ends
  with exit code 2.
"""
import csv
import glob
import io
import os
import re
import sqlite3
import sys
from urllib.parse import urlsplit

CHANGELOG = 'backend/src/main/resources/db/changelog/'
LIBRARY_DIR = CHANGELOG + 'library/'
LIBRARY_CSV = LIBRARY_DIR + '008-library-candidates.csv'
LINK_CSV = CHANGELOG + 'collection/002-collection-link.csv'
RULES_DIR = 'scripts/library/rules/'
# collection ids from here on are the libraries found after the first 278 (registered ids are the collection ids)
FIRST_DISCOVERED_ID = 100001

MEMBERS_ONLY_KINDS = {'SCHOOL', 'UNIV', 'COMPANY', 'GOV', 'ORG'}
KINDS = MEMBERS_ONLY_KINDS | {'PUBLIC', 'UNKNOWN', 'TEST'}
# platform_type -> vendor (ADR-0071; the values of the backend's library.Vendor)
VENDORS = {'KYOBO_V1': 'KYOBO', 'KYOBO_SUBSCRIPTION_V1': 'KYOBO', 'YES24_V1': 'YES24', 'YES24_V2': 'YES24',
           'YES24_V3': 'YES24', 'BOOKCUBE_FX': 'BOOKCUBE', 'ALADIN_V1': 'ALADIN', 'ELIB_PORTAL_V1': 'PORTAL',
           'GANGNAM_V1': 'IN_HOUSE'}
PROBE_MODELS = {'OWNED', 'MIXED', 'RENTAL'}
JOIN_METHOD_UNKNOWN = '도서관 홈페이지의 회원가입 안내 확인'

LIBRARY_FIELDS = ['id', 'name', 'platform_type', 'base_url', 'eligibility_summary', 'extra_condition_text',
                  'visit_required', 'join_method', 'join_url', 'join_caution', 'join_notice', 'homepage_url',
                  'holdings_count', 'holdings_counted_on', 'deferred', 'join_survey', 'lending_model']
RULE_FIELDS = ['library_id', 'basis', 'scope_type', 'scope_region_id']
LINK_FIELDS = ['collection_library_id', 'library_id']


def read_tsv(path):
    with open(path, encoding='utf-8', newline='') as f:
        return list(csv.DictReader(f, delimiter='\t', quoting=csv.QUOTE_NONE))


def read_csv(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding='utf-8') as f:
        return list(csv.DictReader(f))


def write_csv(path, fields, rows):
    out = io.StringIO()
    w = csv.DictWriter(out, fieldnames=fields, lineterminator='\n')
    w.writeheader()
    w.writerows(rows)
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(out.getvalue())


def rule_rows(rules_dir, name, columns):
    """Rows of one rules file; stops when the file or a column is missing."""
    path = os.path.join(rules_dir, name)
    if not os.path.exists(path):
        raise SystemExit(f'rules file missing: {path}')
    with open(path, encoding='utf-8') as f:
        header = f.readline().rstrip('\n').split('\t')
    missing = [c for c in columns if c not in header]
    if missing:
        raise SystemExit(f'{path}: missing columns {missing}')
    return read_tsv(path)


def parse_rules(text):
    """'RESIDENCE:41;WORK:41' -> [('RESIDENCE', 41), ('WORK', 41)]; NATIONWIDE stays a word."""
    rules = []
    for part in filter(None, text.split(';')):
        basis, scope = part.split(':')
        rules.append((basis, scope if scope == 'NATIONWIDE' else int(scope)))
    return rules


def load_rules(rules_dir=None):
    """The rules folder (ADR-0085) as plain values. Columns: rules/README.md."""
    d = rules_dir or RULES_DIR
    rules = {
        'demo_or_closed': {int(r['collection_id']): r['name']
                           for r in rule_rows(d, 'demo_or_closed.tsv', ['collection_id', 'name'])},
        'same_site': {int(r['collection_id']): int(r['library_id'])
                      for r in rule_rows(d, 'same_site.tsv', ['collection_id', 'library_id'])},
        'same_host_kept': {int(r['collection_id']) for r in rule_rows(d, 'same_host_kept.tsv', ['collection_id'])},
        'order_no_allowed': {int(r['collection_id']): int(r['order_no'])
                             for r in rule_rows(d, 'order_no_allowed.tsv', ['collection_id', 'order_no'])},
        'kinds': {int(r['collection_id']): r['kind']
                  for r in rule_rows(d, 'library_kinds.tsv', ['collection_id', 'kind'])},
        'probe': {r['site_url'].rstrip('/'): r['lending_model']
                  for r in rule_rows(d, 'kyobo_probe.tsv', ['site_url', 'lending_model'])},
        'finals': {r['listed_base_url']: r['base_url']
                   for r in rule_rows(d, 'final_origins.tsv', ['listed_base_url', 'base_url'])},
        'name_overrides': {int(r['library_id']): (r['old_name'], r['new_name'])
                           for r in rule_rows(d, 'name_overrides.tsv', ['library_id', 'old_name', 'new_name'])},
    }
    volumes = {}
    for r in rule_rows(d, 'bookcube_volume.tsv', ['collection_id', 'total_item', 'total_volume']):
        items, volume = r['total_item'].strip(), r['total_volume'].strip()
        volumes[int(r['collection_id'])] = (int(items), int(volume)) if items and volume else None
    rules['volumes'] = volumes
    joins = {}
    columns = ['collection_id', 'name', 'eligibility', 'visit_required', 'join_method', 'extra_condition',
               'join_caution', 'source_url', 'rules', 'eligibility_summary']
    for r in rule_rows(d, 'public_join.tsv', columns):
        r = dict(r, rules=parse_rules(r['rules']))
        if r['eligibility'] in ('REGION', 'OPEN', 'OPEN_CAUTION') and not r['rules']:
            raise SystemExit(f"public_join.tsv {r['collection_id']}: no rules; run scripts/library/resolve_regions.py")
        joins[int(r['collection_id'])] = r
    rules['joins'] = joins
    path = os.path.join(d, 'join_surveyed_on.txt')
    if not os.path.exists(path):
        raise SystemExit(f'rules file missing: {path}')
    with open(path, encoding='utf-8') as f:
        rules['join_surveyed_on'] = f.read().strip()
    bad = {
        'kind': sorted({k for k in rules['kinds'].values() if k not in KINDS}),
        'probe lending_model': sorted({m for m in rules['probe'].values() if m not in PROBE_MODELS}),
        'join_surveyed_on': [] if re.fullmatch(r'\d{4}-\d{2}-\d{2}', rules['join_surveyed_on'])
        else [rules['join_surveyed_on']],
    }
    bad = {k: v for k, v in bad.items() if v}
    if bad:
        raise SystemExit(f'unknown values in the rules folder {d}: {bad}')
    return rules


def collection_vendors(db):
    """{collection id: vendor} the collection wrote in library.vendor (collection session, 2026-10-12). Copies made
    before that column (e.g. 2026-10-05) have none: empty. A value outside VENDORS stops the run."""
    if 'vendor' not in {r[1] for r in db.execute('PRAGMA table_info(library)')}:
        return {}
    vendors = {r[0]: r[1] for r in db.execute('SELECT id, vendor FROM library WHERE vendor IS NOT NULL')}
    unknown = sorted((lid, v) for lid, v in vendors.items() if v not in VENDORS.values())
    if unknown:
        raise SystemExit(f'unknown vendor in the collection DB: {unknown}')
    return vendors


def registered_libraries():
    """Registered libraries {id: (name, platform_type)} from the seed files before 008 (CSVs and the pilot SQL in 002)."""
    found = {}
    for path in sorted(glob.glob(LIBRARY_DIR + '*-library*.csv')):
        if os.path.basename(path).startswith('008-'):
            continue
        rows = read_csv(path)
        if rows and 'name' not in rows[0]:  # e.g. 009-library-logos.csv
            continue
        for row in rows:
            found[int(row['id'])] = (row['name'], row['platform_type'])
    with open(LIBRARY_DIR + '002-library-seed.yaml', encoding='utf-8') as f:
        pilot = f.read()
    for lid, name, platform in re.findall(r"\((\d+), '([^']+)', '([A-Z0-9_]+)', 'https?://", pilot):
        found[int(lid)] = (name, platform)
    return found


def registered_lending_models():
    """{id: lending_model} of registered libraries from the library/008 UPDATE (the KYOBO probe values)."""
    path = LIBRARY_DIR + '008-library-seed-candidates.yaml'
    if not os.path.exists(path):
        return {}
    with open(path, encoding='utf-8') as f:
        text = f.read()
    return {int(lid): m for lid, m in re.findall(r"\((\d+), '(OWNED|SUBSCRIPTION|MIXED|RENTAL)'\)", text)}


def lending_model_of(platform, probe_model, volume):
    """spec R3 (cycle 09). volume is (total_item, total_volume) or None."""
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


def join_columns(library, kind, join):
    """(library columns, rules) for the join part of one discovered library."""
    empty = {'eligibility_summary': 'NULL', 'extra_condition_text': 'NULL', 'visit_required': 'false',
             'join_method': 'NULL', 'join_url': 'NULL', 'join_caution': 'NULL'}
    if join is None:
        if kind == 'UNKNOWN':
            return dict(empty, join_survey='NOT_SURVEYED'), []
        assert kind in MEMBERS_ONLY_KINDS, (library['id'], kind)
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
    assert eligibility in ('REGION', 'OPEN', 'OPEN_CAUTION'), (library['id'], eligibility)
    summary = (join['eligibility_summary'] or 'NULL') if eligibility == 'REGION' else 'NULL'
    return {'eligibility_summary': summary, 'extra_condition_text': text['extra_condition'], 'visit_required': visit,
            'join_method': text['join_method'] if text['join_method'] != 'NULL' else JOIN_METHOD_UNKNOWN,
            'join_url': text['source_url'], 'join_caution': text['join_caution'], 'join_survey': 'SURVEYED'}, \
        join['rules']


def apply_name_overrides(overrides, names, service_ids, registered_names):
    """Puts the listed new names in place: names is keyed by collection id, registered_names by service id (changed in
    memory only). Stops on a stale entry. Equal names are told apart by vendor, lending_model and order_no (ADR-0071)."""
    by_service = {sid: cid for cid, sid in service_ids.items()}
    for sid, (old, new) in overrides.items():
        if sid in by_service:
            target, key = names, by_service[sid]
        elif sid in registered_names:
            target, key = registered_names, sid
        else:
            raise SystemExit(f'name override for {sid}: not a service library')
        if target[key] not in (old, new):
            raise SystemExit(f'name override for {sid}: the name is {target[key]!r}, the list says {old!r}')
        target[key] = new


def kyobo_rental_mixed(row, models):
    """A Kyobo subscription site whose probe model is rental or mixed (ADR-0067)."""
    return (row['platform_type'] == 'KYOBO_SUBSCRIPTION_V1'
            and models.get(row['base_url'].rstrip('/')) in ('RENTAL', 'MIXED'))


def same_host(db, checks):
    """checks: [(library, [other library])]. [(library, other, keys, other keys, shared)] for each other library on
    the same host, with the item_key overlap."""
    def keys(lid):
        return db.execute('SELECT count(*) FROM holding WHERE library_id = ? AND removed_at IS NULL',
                          (lid,)).fetchone()[0]
    found = []
    for library, others in checks:
        host = host_of(library['base_url'])
        for other in others:
            if host_of(other['base_url']) != host:
                continue
            shared = db.execute('SELECT count(*) FROM holding a JOIN holding b ON b.library_id = ? AND '
                                'b.item_key = a.item_key AND b.removed_at IS NULL '
                                'WHERE a.library_id = ? AND a.removed_at IS NULL',
                                (other['id'], library['id'])).fetchone()[0]
            found.append((library, other, keys(library['id']), keys(other['id']), shared))
    return found


def print_pairs(title, pairs, kept):
    overlapping = [x for x in pairs if x[4] > 0]
    unread = [x for x in overlapping if x[0]['id'] not in kept]
    print(f'{title}: {len(pairs)} pairs, with shared item keys {len(overlapping)}, not read yet {len(unread)}')
    for c, r, keys_c, keys_r, shared in unread:
        print(f"  {c['id']} {c['name']} {c['base_url']} ~ {r['id']} {r['name']} {r['base_url']}: "
              f"keys {keys_c} / {keys_r}, shared {shared} "
              f"({shared / keys_c if keys_c else 0:.0%} of the library, {shared / keys_r if keys_r else 0:.0%} of the other)")
    return unread


def main(snapshot, rules_dir=None, db_state=None, out_dir=None):
    rules = load_rules(rules_dir)
    if out_dir is not None:
        backend = os.path.abspath('backend')
        if os.path.commonpath([os.path.abspath(out_dir), backend]) == backend:
            raise SystemExit('--out must not be under backend/: this script writes no seed file (plan cycle 13 R4-3)')
    db = sqlite3.connect(f'file:{snapshot}?mode=ro', uri=True)
    db.row_factory = sqlite3.Row
    rows = [dict(r) for r in db.execute('SELECT id, name, platform_type, base_url, last_full_success_at, crawl_skip '
                                        'FROM library ORDER BY id')]
    written_vendors = collection_vendors(db)
    registered = registered_libraries()
    registered_names = {lid: name for lid, (name, _platform) in registered.items()}
    volumes, kinds, joins, models = rules['volumes'], rules['kinds'], rules['joins'], rules['probe']
    demo_or_closed, same_site_ids = rules['demo_or_closed'], rules['same_site']

    left_out = {'kyobo_rental_mixed': [], 'crawl_skip': [], 'no_full_success': [], 'demo_or_closed': [],
                'same_site': [], 'test': []}
    discovered = []
    for row in rows:
        if row['id'] < FIRST_DISCOVERED_ID:
            continue
        if kyobo_rental_mixed(row, models):
            left_out['kyobo_rental_mixed'].append(row)
        elif row['crawl_skip']:
            left_out['crawl_skip'].append(row)
        elif not row['last_full_success_at']:
            left_out['no_full_success'].append(row)
        elif row['id'] in demo_or_closed:
            left_out['demo_or_closed'].append(row)
        elif row['id'] in same_site_ids:
            left_out['same_site'].append(row)
        elif kinds.get(row['id']) == 'TEST':
            left_out['test'].append(row)
        else:
            if row['id'] not in kinds:
                raise SystemExit(f"no kind for library {row['id']} {row['name']}")
            discovered.append(row)

    # keep numbers and names already given (plan handoff C5: they never change)
    kept_links = {int(r['collection_library_id']): int(r['library_id']) for r in read_csv(LINK_CSV)
                  if int(r['collection_library_id']) >= FIRST_DISCOVERED_ID}
    kept_names = {int(r['id']): r['name'] for r in read_csv(LIBRARY_CSV)}
    kept_names = {cid: kept_names[sid] for cid, sid in kept_links.items() if sid in kept_names}
    discovered_ids = {c['id'] for c in discovered}
    # ADR-0067: the Kyobo rental and mixed sites leave the service; their ids are not given again (the new ids
    # start after the highest id ever linked, below)
    rental_mixed = {row['id'] for row in left_out['kyobo_rental_mixed']}
    dropped = sorted(set(kept_links) - discovered_ids - rental_mixed)
    if dropped:
        raise SystemExit(f'libraries in the link seed are left out now (ids never move): {dropped}')
    last_id = max([*registered, *kept_links.values()])
    kept_links = {cid: sid for cid, sid in kept_links.items() if cid in discovered_ids}
    kept_names = {cid: name for cid, name in kept_names.items() if cid in discovered_ids}
    new = [c for c in discovered if c['id'] not in kept_links]

    registered_rows = [r for r in rows if r['id'] < FIRST_DISCOVERED_ID]
    clashes = same_host(db, [(c, registered_rows) for c in discovered])
    # cycle 13 (plan spec R2-7): a new library against all service libraries and the other new ones
    in_service = [c for c in discovered if c['id'] in kept_links]
    new_clashes = same_host(db, [(c, in_service + [n for n in new if n['id'] < c['id']]) for c in new])
    db.close()

    names = dict(kept_names)
    names.update({c['id']: c['name'] for c in new})
    service_ids = dict(kept_links)
    for c in new:
        last_id += 1
        service_ids[c['id']] = last_id
    apply_name_overrides(rules['name_overrides'], names, service_ids, registered_names)

    libraries, rule_list = [], []
    for c in sorted(discovered, key=lambda c: service_ids[c['id']]):
        sid = service_ids[c['id']]
        columns, library_rules = join_columns(c, kinds[c['id']], joins.get(c['id']))
        probe = models.get(c['base_url'].rstrip('/')) if c['platform_type'] == 'KYOBO_SUBSCRIPTION_V1' else None
        model = lending_model_of(c['platform_type'], probe, volumes.get(c['id']))
        base_url = rules['finals'].get(c['base_url'], c['base_url'])
        libraries.append(dict(columns, id=sid, name=names[c['id']], platform_type=c['platform_type'],
                              base_url=base_url, join_notice='NULL', homepage_url=base_url,
                              holdings_count='NULL', holdings_counted_on='NULL', deferred='false',
                              lending_model=model or 'NULL'))
        for basis, scope in library_rules:
            rule_list.append({'library_id': sid, 'basis': basis,
                              'scope_type': 'NATIONWIDE' if scope == 'NATIONWIDE' else 'REGION',
                              'scope_region_id': 'NULL' if scope == 'NATIONWIDE' else scope})
    links = [{'collection_library_id': lid, 'library_id': lid} for lid in sorted(registered)]
    links += [{'collection_library_id': cid, 'library_id': service_ids[cid]} for cid in sorted(service_ids)]

    # classification of every library: registered and discovered, disabled ones too (ADR-0071)
    registered_models = registered_lending_models()
    classified = [{'id': lid, 'collection_id': lid, 'name': registered_names[lid], 'platform_type': platform,
                   'vendor': written_vendors.get(lid, VENDORS[platform]),
                   'lending_model': registered_models.get(lid) or lending_model_of(platform, None, volumes.get(lid))}
                  for lid, (_name, platform) in registered.items()]
    collection_ids = {sid: cid for cid, sid in service_ids.items()}
    for lib in libraries:
        cid = collection_ids[lib['id']]
        classified.append({'id': lib['id'], 'collection_id': cid, 'name': lib['name'],
                           'platform_type': lib['platform_type'],
                           'vendor': written_vendors.get(cid, VENDORS[lib['platform_type']]),
                           'lending_model': None if lib['lending_model'] == 'NULL' else lib['lending_model']})
    second = assign_order_no(classified)

    if out_dir is not None:
        os.makedirs(out_dir, exist_ok=True)
        write_csv(os.path.join(out_dir, 'libraries.csv'), LIBRARY_FIELDS, libraries)
        write_csv(os.path.join(out_dir, 'eligibility_rules.csv'), RULE_FIELDS, rule_list)
        write_csv(os.path.join(out_dir, 'links.csv'), LINK_FIELDS, links)
    if db_state is not None:
        print_changes(classified, read_db_state(db_state))

    def count(values):
        out = {}
        for v in values:
            out[v] = out.get(v, 0) + 1
        return ', '.join(f'{k} {v}' for k, v in sorted(out.items(), key=lambda kv: -kv[1]))

    print(f'discovered libraries in the service: {len(libraries)} (service ids {min(service_ids.values())}..{last_id}, '
          f'kept {len(kept_links)}, new {len(new)}), rules {len(rule_list)}, links {len(links)}')
    print(f'left out: ' + ', '.join(f'{k} {len(v)}' for k, v in left_out.items()))
    for reason in ('demo_or_closed', 'same_site', 'test'):
        for row in left_out[reason]:
            pair = f' = registered {same_site_ids[row["id"]]}' if reason == 'same_site' else ''
            print(f"  {row['id']} {row['name']} ({reason}{pair})")
    print('join_survey: ' + count(lib['join_survey'] for lib in libraries))
    print('lending_model: ' + count(lib['lending_model'] for lib in libraries))
    print('platform: ' + count(lib['platform_type'] for lib in libraries))
    print('all libraries by vendor: ' + count(row['vendor'] for row in classified))
    print('all libraries by lending_model: ' + count(row['lending_model'] or 'NULL' for row in classified))
    print('BOOKCUBE SUBSCRIPTION service ids: ' + ', '.join(
        str(row['id']) for row in sorted(classified, key=lambda r: r['id'])
        if row['vendor'] == 'BOOKCUBE' and row['lending_model'] == 'SUBSCRIPTION'))
    by_id = {row['id']: row for row in classified}
    stopping = [(lid, n) for lid, n in second if rules['order_no_allowed'].get(by_id[lid]['collection_id']) != n]
    print(f'order_no 2 and up: {len(second)}' + ''.join(f'\n  ({lid}, {n})' for lid, n in second)
          + (f'\norder_no 2 and up not in order_no_allowed.tsv: {len(stopping)}' if len(stopping) != len(second) else ''))
    probe_registered = []
    for r in registered_rows:
        if r['platform_type'] == 'KYOBO_SUBSCRIPTION_V1' and r['base_url'].rstrip('/') in models:
            probe_registered.append((r['id'], models[r['base_url'].rstrip('/')]))
    print('registered lending_model (library/008 UPDATE): '
          + ', '.join(f"({lid}, '{m}')" for lid, m in sorted(probe_registered)))
    print_new_libraries(new, service_ids, libraries, rule_list, by_id)
    print(f"join surveyed on: {rules['join_surveyed_on']}")
    unread = print_pairs('same host as a registered library', clashes, rules['same_host_kept'])
    unread += print_pairs('same host, new library against the service and the other new ones', new_clashes,
                          rules['same_host_kept'])
    if unread:
        return 2
    return 3 if stopping else 0


def print_new_libraries(new, service_ids, libraries, rule_list, by_id):
    """The libraries an insert would add, with every value the insert command decides (AC-6 comparison)."""
    rows = {lib['id']: lib for lib in libraries}
    print(f'new libraries: {len(new)}')
    print('  ' + '\t'.join(['id', 'collection_id', 'name', 'platform_type', 'vendor', 'lending_model', 'order_no',
                            'join_survey', 'base_url', 'eligibility_summary', 'rules']))
    for c in new:
        sid = service_ids[c['id']]
        lib, cls = rows[sid], by_id[sid]
        lib_rules = ';'.join(f"{r['basis']}:{r['scope_region_id']}" for r in rule_list if r['library_id'] == sid)
        print('  ' + '\t'.join(str(v) for v in [sid, c['id'], lib['name'], lib['platform_type'], cls['vendor'],
                                                lib['lending_model'], cls['order_no'], lib['join_survey'],
                                                lib['base_url'], lib['eligibility_summary'], lib_rules or '-']))


def read_db_state(path):
    """{id: (name, platform_type, lending_model or None)} from the db-state export (see the usage)."""
    state = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
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
        raise SystemExit(f'db state and the result differ: not in the db {missing}, not in the result {extra}')
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


def parse_args(args):
    options = {'rules_dir': None, 'db_state': None, 'out_dir': None}
    flags = {'--rules': 'rules_dir', '--changes-only': 'db_state', '--out': 'out_dir'}
    rest = []
    while args:
        if args[0] in flags and len(args) > 1:
            options[flags[args[0]]], args = args[1], args[2:]
        else:
            rest, args = rest + [args[0]], args[1:]
    if len(rest) != 1 or any(a.startswith('--') for a in rest):
        raise SystemExit(__doc__)
    return rest[0], options


if __name__ == '__main__':
    snapshot_path, opts = parse_args(sys.argv[1:])
    sys.stdout.reconfigure(encoding='utf-8')
    sys.exit(main(snapshot_path, **opts))
