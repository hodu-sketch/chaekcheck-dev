"""Saves the search plan (library ids, order, group, eligibility) for a fixed input set, and compares two saves
(cycle 08 handoff step 0-2 and step 6, AC-17).

Usage (from the repository root, the backend running):
  python scripts/catalog/save_plans.py save <out dir> [base url]
  python scripts/catalog/save_plans.py compare <before dir> <after dir>

The plan comes from the first event (targets) of the batch search stream; the rest of the stream is not read.
Login does not change the plan on the server: the web sends the account's regions and libraries as the same
parameters, so one input set covers both.
"""
import json
import os
import sys
import urllib.parse
import urllib.request

REGIONS = [
    ('none', {}),
    ('res', {'residenceRegionIds': '1101'}),  # 서울 종로구
    ('res-work', {'residenceRegionIds': '1101', 'workRegionIds': '4113'}),  # + 경기 남양주시
]
CHOSEN = [
    ('nochosen', {}),
    # 284 NOT_SURVEYED, 282 MEMBERS_ONLY (both only when chosen, ADR-0053), 1 a registered library
    ('chosen', {'libraryIds': '284,282,1'}),
]
SCOPES = [('all', {'scope': 'all'}), ('joinable', {'scope': 'joinable'})]
KEEP = ('id', 'priorityGroup', 'eligibility', 'chosen', 'deferred', 'visitRequired')


def first_event(url):
    request = urllib.request.Request(url, headers={'Accept': 'text/event-stream'})
    with urllib.request.urlopen(request, timeout=60) as response:
        lines = []
        for raw in response:
            line = raw.decode('utf-8').rstrip('\r\n')
            if not line and lines:
                break
            lines.append(line)
    return json.loads('\n'.join(l[5:].lstrip() for l in lines if l.startswith('data:')))


def cases():
    for rname, rparams in REGIONS:
        for cname, cparams in CHOSEN:
            for sname, sparams in SCOPES:
                yield f'{rname}-{cname}-{sname}', dict(rparams, **cparams, **sparams)


def save(out_dir, base='http://localhost:8080'):
    os.makedirs(out_dir, exist_ok=True)
    for name, params in cases():
        url = f'{base}/api/v1/ebook-searches/stream?{urllib.parse.urlencode(dict(params, title="사랑"))}'
        targets = first_event(url)
        plan = [{k: library.get(k) for k in KEEP} for library in targets['libraries']]
        json.dump(plan, open(f'{out_dir}/plan-{name}.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
        print(f'{name}\t{len(plan)}')


def compare(before, after):
    different = 0
    for name, _ in cases():
        a = json.load(open(f'{before}/plan-{name}.json', encoding='utf-8'))
        b = json.load(open(f'{after}/plan-{name}.json', encoding='utf-8'))
        same = a == b
        different += not same
        print(f'{name}\t{len(a)}\t{len(b)}\t{"same" if same else "DIFFERENT"}')
    sys.exit(1 if different else 0)


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    if len(sys.argv) < 3 or sys.argv[1] not in ('save', 'compare'):
        raise SystemExit(__doc__)
    if sys.argv[1] == 'save':
        save(*sys.argv[2:4])
    else:
        compare(sys.argv[2], sys.argv[3])
