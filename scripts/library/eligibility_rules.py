"""Turns eligibility-by-library.md rows into eligibility rules for the library seed (docs/libraries/library-maintenance.md).

Usage (from the repository root):
  python scripts/library/eligibility_rules.py <eligibility-by-library.md> <out.json> [platform ...]
  platform: the table's 플랫폼 value, default "교보" "교보 구독형" (e.g. "YES24" for YES24 sites)

Only rows whose "원문 확인" starts with 확인됨 or 다름 (the original page was read) become rules. Scope text is mapped
to region ids from backend/.../region/002-region-seed.csv; a scope that cannot be mapped exactly gives no rules (the
library is then "eligibility not verified", ADR-0013) and is printed for a person or a later pass to read.
Rows that need the original text read by hand go in OVERRIDES, with the quoted text as a comment.
Output: {name: {"rules": [[basis, regionId | "NATIONWIDE"]...], "qual", "scope", "extra", "visit", "joinUrl", "note"}}
"""
import csv
import json
import re
import sys

REGION = 'backend/src/main/resources/db/changelog/region/002-region-seed.csv'

sido, sigungu = {}, {}
for r in csv.DictReader(open(REGION, encoding='utf-8')):
    if r['status'] != 'ACTIVE':
        continue
    if r['level'] == 'SIDO':
        sido[r['name']] = int(r['id'])
        if r['short_name'] != 'NULL':
            sido[r['short_name']] = int(r['id'])
    else:
        sigungu.setdefault(r['name'], []).append(int(r['id']))
# common spellings
for full, short in [('서울특별시', '서울시'), ('부산광역시', '부산시'), ('대구광역시', '대구시'), ('인천광역시', '인천시'),
                    ('광주광역시', '광주시'), ('대전광역시', '대전시'), ('울산광역시', '울산시'), ('경기도', '경기도'),
                    ('강원특별자치도', '강원도'), ('충청북도', '충청북도'), ('충청남도', '충청남도'),
                    ('전북특별자치도', '전라북도'), ('경상북도', '경상북도'), ('경상남도', '경상남도'),
                    ('제주특별자치도', '제주도'), ('세종특별자치시', '세종시')]:
    if full in sido:
        sido[short] = sido[full]


def region(token):
    t = re.sub(r'\(.*?\)', '', token).strip()
    t = re.sub(r'\s*(거주자?|주민|전역|관내)$', '', t).strip()
    if not t:
        return None
    if t in sido:
        return sido[t]
    ids = sigungu.get(t)
    if ids and len(ids) == 1:
        return ids[0]
    for suffix in ('시', '군', '구'):
        ids = sigungu.get(t + suffix)
        if ids and len(ids) == 1:
            return ids[0]
    return None



def main(eligibility_md, out_path, platforms):
    sys.stdout.reconfigure(encoding='utf-8')
    out = {}
    for line in open(eligibility_md, encoding='utf-8'):
        if not line.startswith('| ') or line.startswith('| 전자도서관') or line.startswith('|---'):
            continue
        cols = [c.strip() for c in line.strip().strip('|').split('|')]
        if len(cols) < 8:
            continue
        name, platform, _state, qual, scope, _how, confirm, source = cols[:8]
        if platform not in platforms:
            continue
        if not (confirm.startswith('확인됨') or confirm.startswith('다름')):
            continue
        bases = [b for k, b in (('거주', 'RESIDENCE'), ('재직', 'WORK'), ('재학', 'SCHOOL')) if k in qual]
        tokens = [t.strip() for t in re.split(r'\+|,|/', scope) if t.strip()]
        ids, extra, unresolved = [], None, []
        for token in tokens:
            if '책이음' in token:
                extra = '책이음 회원 가입 가능'
                continue
            rid = region(token)
            if rid is None:
                unresolved.append(token)
            else:
                ids.append(rid)
        rules, note = [], ''
        if unresolved:
            note = 'unresolved scope: ' + ', '.join(unresolved)
        elif not bases:
            note = 'no residence/work/school basis: ' + qual
        elif not ids:
            note = 'no region: ' + scope
        else:
            rules = [[b, rid] for rid in dict.fromkeys(ids) for b in bases]
        url = source.split()[-1] if source.startswith('http') or ' http' in source else None
        out[name] = {'rules': rules, 'qual': qual,
                     'scope': scope, 'extra': extra, 'visit': '방문' in qual, 'joinUrl': url, 'note': note}

    # Rows whose scope text needs reading by hand (original text quoted in eligibility-by-library.md)
    def rules_for(residence, work_school):
        res = [['RESIDENCE', region(n)] for n in residence]
        ws = [[b, region(n)] for n in work_school for b in ('WORK', 'SCHOOL')]
        assert all(r[1] for r in res + ws), (residence, work_school)
        return res + ws

    OVERRIDES = {
        # "경기도민 및 인접시군(천안, 진천, 음성) 주민", "안성시 관내의 직장 또는 학교에 다니는 사람"
        '안성시립도서관': dict(rules=rules_for(['경기', '천안시', '진천군', '음성군'], ['안성시'])),
        # "천안시에 주소를 둔 천안시민", "천안시 소재 재직자 및 재학생", "천안 인접지역 시민(세종, 아산, 공주, 평택, 안성, 청주, 진천)"
        '천안시중앙도서관': dict(rules=rules_for(['천안시', '세종', '아산시', '공주시', '평택시', '안성시', '청주시', '진천군'], ['천안시'])),
        '김해시청': dict(rules=[['RESIDENCE', 'NATIONWIDE']]),  # "회원가입 자격 : 전 국민"
        '양산도서관': dict(rules=[['RESIDENCE', 'NATIONWIDE']]),  # "… 주민등록상 타지역 주민"
        '국립세종도서관': dict(rules=[['RESIDENCE', 'NATIONWIDE']], visit=True),  # 대출증 발급을 위해 직접 방문
        '국립세종도서관(구독형)': dict(rules=[['RESIDENCE', 'NATIONWIDE']], visit=True),
        '국회도서관': dict(rules=[['RESIDENCE', 'NATIONWIDE']], visit=True, extra='18세 이상·대학생 등, 장기열람증 방문 발급'),
        # 온라인 정회원 "주민등록상 경기도민", 방문 정회원(책이음회원증) "누구나"
        '의왕시중앙도서관': dict(rules=rules_for(['경기'], []), extra='방문 가입(책이음 회원증)은 누구나'),
    }
    for name, o in OVERRIDES.items():
        if name in out:
            out[name].update(o)
            out[name]['note'] = ''

    json.dump(out, open(out_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print(len(out), 'confirmed rows;', sum(1 for v in out.values() if v['rules']), 'with rules')
    for k, v in out.items():
        if v['note']:
            print('  -', k, '|', v['note'])


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], tuple(sys.argv[3:]) or ('교보', '교보 구독형'))
