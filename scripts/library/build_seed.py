"""Builds library seed CSVs from site checks and eligibility rules (docs/libraries/library-maintenance.md, "추가").

Usage (from the repository root):
  python scripts/library/build_seed.py <search.tsv> <holdings.tsv> <eligibility.json> <first id> <out prefix> <counted on>
    search.tsv    CheckKyoboSites.java output, mode search (only OK rows are used)
    holdings.tsv  CheckKyoboSites.java output, mode holdings (count -> holdings_count); may be an empty file
    eligibility.json  eligibility_rules.py output
    first id      first library id to use (max id in existing seed CSVs + 1)
    out prefix    e.g. backend/src/main/resources/db/changelog/library/004 -> 004-library.csv, 004-eligibility-rule.csv
    counted on    date of the holdings check, e.g. 2026-09-29

holdings_count is NULL when the blank search gives 0 (subscription sites). Skips a site when its final origin or its name is already in an existing seed (every *.csv and the pilot seed in
library/), or when its name has no "도서관" (the KYOBO list mixes in city halls and other bodies; check those by
hand first). Prints what it skipped and why.
"""
import csv
import glob
import json
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')
search_tsv, holdings_tsv, eligibility_json, first_id, prefix, counted_on = sys.argv[1:7]
LIBRARY_DIR = 'backend/src/main/resources/db/changelog/library/'
JOIN_METHOD_UNKNOWN = '도서관 홈페이지의 회원가입 안내 확인'
BASIS_WORDS = {'RESIDENCE': '거주', 'WORK': '재직', 'SCHOOL': '재학'}

existing_urls, existing_names = set(), set()
for path in glob.glob(LIBRARY_DIR + '*.csv'):
    for row in csv.DictReader(open(path, encoding='utf-8')):
        if 'base_url' in row:
            existing_urls.add(row['base_url'].rstrip('/'))
            existing_names.add(row['name'])
for path in glob.glob(LIBRARY_DIR + '*.yaml'):  # the pilot seed is SQL inside YAML
    text = open(path, encoding='utf-8').read()
    existing_urls.update(u.rstrip('/') for u in re.findall(r"'(https?://[^']+)'", text))
    existing_names.update(re.findall(r"\(\d+, '([^']+)', '[A-Z0-9_]+', 'https?://", text))

holdings = {}
for line in open(holdings_tsv, encoding='utf-8'):
    cols = line.rstrip('\n').split('\t')
    if len(cols) > 4 and cols[3] == 'OK':
        holdings[cols[0]] = int(cols[4])

eligibility = json.load(open(eligibility_json, encoding='utf-8'))

region_names = {}
for row in csv.DictReader(open('backend/src/main/resources/db/changelog/region/002-region-seed.csv', encoding='utf-8')):
    region_names[int(row['id'])] = row['short_name'] if row['short_name'] != 'NULL' else row['name']


def summary_of(library_rules):
    """The rules as one line, e.g. "경기 거주 / 과천시 재직·재학"; None for anyone-can-join or no rules."""
    by_region = {}
    for basis, region in library_rules:
        if region != 'NATIONWIDE':
            by_region.setdefault(region, set()).add(basis)
    groups = {}
    for region, bases in by_region.items():
        key = tuple(b for b in BASIS_WORDS if b in bases)
        groups.setdefault(key, []).append(region_names[region])
    return ' / '.join('·'.join(names) + ' ' + '·'.join(BASIS_WORDS[b] for b in key)
                      for key, names in groups.items()) or None

libraries, rules, skipped = [], [], []
next_id = int(first_id)
seen_urls, seen_names = set(existing_urls), set(existing_names)
for line in open(search_tsv, encoding='utf-8'):
    cols = line.rstrip('\n').split('\t')
    if len(cols) < 6 or cols[3] != 'OK':
        continue
    name, kind, origin = cols[0], cols[2], cols[5].rstrip('/')
    if '도서관' not in name:
        skipped.append((name, 'name without 도서관: check public use by hand'))
        continue
    if origin in seen_urls:
        skipped.append((name, 'base_url already in a seed: ' + origin))
        continue
    if name in seen_names:
        skipped.append((name, 'name already in a seed'))
        continue
    seen_urls.add(origin)
    seen_names.add(name)
    e = eligibility.get(name, {})
    library_rules = e.get('rules') or []
    summary = summary_of(library_rules)
    count = holdings.get(name) or None  # subscription sites answer a blank search with 0: not a count
    libraries.append({
        'id': next_id, 'name': name,
        'platform_type': 'KYOBO_SUBSCRIPTION_V1' if kind == 'SUB' else 'KYOBO_V1',
        'base_url': origin, 'eligibility_summary': summary or 'NULL',
        'extra_condition_text': e.get('extra') or 'NULL', 'visit_required': str(bool(e.get('visit'))).lower(),
        'join_method': JOIN_METHOD_UNKNOWN, 'join_url': (e.get('joinUrl') if library_rules else None) or 'NULL',
        'holdings_count': count if count is not None else 'NULL',
        'holdings_counted_on': counted_on if count is not None else 'NULL', 'deferred': 'false'})
    for basis, region in library_rules:
        rules.append({'library_id': next_id, 'basis': basis,
                      'scope_type': 'NATIONWIDE' if region == 'NATIONWIDE' else 'REGION',
                      'scope_region_id': 'NULL' if region == 'NATIONWIDE' else region})
    next_id += 1

with open(prefix + '-library.csv', 'w', encoding='utf-8', newline='') as f:
    w = csv.DictWriter(f, fieldnames=list(libraries[0].keys()), lineterminator='\n')
    w.writeheader()
    w.writerows(libraries)
with open(prefix + '-eligibility-rule.csv', 'w', encoding='utf-8', newline='') as f:
    w = csv.DictWriter(f, fieldnames=['library_id', 'basis', 'scope_type', 'scope_region_id'], lineterminator='\n')
    w.writeheader()
    w.writerows(rules)
print(f'{len(libraries)} libraries (ids {first_id}..{next_id - 1}), {len(rules)} rules, next id {next_id}')
for name, why in skipped:
    print('  skipped:', name, '|', why)
