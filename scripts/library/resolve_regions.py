"""Fills the region columns of scripts/library/rules/public_join.tsv (plan ADR-0085, cycle 13).

Usage (from the repository root):
  python scripts/library/resolve_regions.py [--check] [<public-join source.tsv>]

  (no argument)  re-resolve rules/public_join.tsv in place: its "rules" and "eligibility_summary" columns
  <source.tsv>   take the rows of a join survey file (e.g. chaekcheck-data library-join/public-join-2026-10-05.tsv,
                 first column "id") and write them as rules/public_join.tsv with the two columns filled
  --check        write nothing; exit 1 when the file is not what this helper would write

The join survey writes areas as names ("경기도", "송파구"). The insert command reads region ids only, since the
collection module does not depend on region (ADR-0085), so this helper turns the names into ids once:
- rules: "BASIS:region id" joined by ";" in rule order, "RESIDENCE:NATIONWIDE" for OPEN and OPEN_CAUTION, empty for
  UNVERIFIED and AFFILIATION (they get no rules). rules/scope_overrides.tsv gives the areas a person read from the
  quoted text when residence and work/school areas differ.
- eligibility_summary: the screen text of REGION rows ("경기 거주 / 수원시 재직·재학"), the same wording as the
  registered libraries (apply_join_classification.summary_of); empty otherwise.
Region names map to ids with eligibility_rules.region over backend/.../region/002-region-seed.csv, the mapping the
seed script used. A name that maps to no region or to more than one stops the helper.
"""
import csv
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from apply_join_classification import summary_of  # noqa: E402  same summary wording as the registered libraries
from eligibility_rules import region  # noqa: E402  region name -> region id, same mapping as before

RULES_DIR = 'scripts/library/rules/'
REGIONS = 'backend/src/main/resources/db/changelog/region/002-region-seed.csv'
BASES = {'거주': 'RESIDENCE', '재직': 'WORK', '재학': 'SCHOOL'}
RESOLVED = ['rules', 'eligibility_summary']


def read_tsv(path):
    with open(path, encoding='utf-8', newline='') as f:
        return list(csv.DictReader(f, delimiter='\t', quoting=csv.QUOTE_NONE))


def write_tsv(path, header, rows):
    with open(path, 'w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=header, delimiter='\t', lineterminator='\n', quoting=csv.QUOTE_NONE,
                           quotechar=None)
        w.writeheader()
        w.writerows(rows)


def region_names():
    """{region id: short name or name}: the words of the summary."""
    with open(REGIONS, encoding='utf-8') as f:
        return {int(r['id']): (r['short_name'] if r['short_name'] != 'NULL' else r['name'])
                for r in csv.DictReader(f)}


def resolve(names):
    rid = [region(n) for n in names.split(',') if n.strip()]
    if not rid or None in rid:
        raise SystemExit(f'cannot resolve region names: {names}')
    return rid


def scope_overrides(rules_dir):
    """{collection id: [(basis, region id)]} from scope_overrides.tsv (residence areas, then work/school areas)."""
    found = {}
    for r in read_tsv(rules_dir + 'scope_overrides.tsv'):
        rules = [('RESIDENCE', rid) for rid in resolve(r['residence'])]
        rules += [(b, rid) for rid in resolve(r['work_school']) for b in ('WORK', 'SCHOOL')]
        found[r['collection_id']] = rules
    return found


def rules_of(join, overrides):
    """[(basis, region id | 'NATIONWIDE')] of one public_join row (the seed script's rules_of, unchanged)."""
    if join['eligibility'] in ('OPEN', 'OPEN_CAUTION'):
        return [('RESIDENCE', 'NATIONWIDE')]
    if join['collection_id'] in overrides:
        return overrides[join['collection_id']]
    bases = [BASES[b] for b in BASES if b in join['basis']]
    regions = [region(t) for t in re.split(r'[,/]', join['scope']) if t.strip()]
    if not bases or not regions or None in regions:
        raise SystemExit(f"cannot read scope of {join['collection_id']} {join['name']}: {join['basis']} | "
                         f"{join['scope']}")
    return [(b, r) for r in dict.fromkeys(regions) for b in bases]


def resolved_columns(join, overrides, names):
    if join['eligibility'] not in ('REGION', 'OPEN', 'OPEN_CAUTION'):
        return {'rules': '', 'eligibility_summary': ''}
    rules = rules_of(join, overrides)
    summary = ''
    if join['eligibility'] == 'REGION':
        summary = summary_of([(b, 'REGION', r) for b, r in rules], names)
    return {'rules': ';'.join(f'{b}:{r}' for b, r in rules), 'eligibility_summary': summary}


def main(args):
    check = args[:1] == ['--check']
    args = args[1:] if check else args
    if len(args) > 1:
        raise SystemExit(__doc__)
    target = RULES_DIR + 'public_join.tsv'
    if args:
        rows = read_tsv(args[0])
        header = list(rows[0].keys()) if rows else []
        if header[:1] != ['id']:
            raise SystemExit(f'{args[0]}: the first column must be id')
        header = ['collection_id'] + header[1:] + RESOLVED
        rows = [dict({'collection_id': r['id']}, **{k: v for k, v in r.items() if k != 'id'}) for r in rows]
    else:
        rows = read_tsv(target)
        with open(target, encoding='utf-8') as f:
            header = f.readline().rstrip('\n').split('\t')
    overrides = scope_overrides(RULES_DIR)
    names = region_names()
    out = [dict(r, **resolved_columns(r, overrides, names)) for r in rows]
    if check:
        current = read_tsv(target) if os.path.exists(target) else []
        stale = [r['collection_id'] for r, c in zip(out, current) if r != c]
        if stale or len(out) != len(current):
            print(f'{target} is not up to date: {stale or "row count"}; run resolve_regions.py')
            return 1
        print(f'{target}: {len(out)} rows up to date')
        return 0
    write_tsv(target, header, out)
    print(f'{target}: {len(out)} rows, with rules {sum(1 for r in out if r["rules"])}')
    return 0


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    sys.exit(main(sys.argv[1:]))
