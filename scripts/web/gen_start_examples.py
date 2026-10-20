"""Writes web/src/features/search/startExamples.ts: the first screen's example card regions (user 2026-10-20: "한 100개
정도 해서 항상 랜덤값이 뜨게").

For every 시군구 it asks the public library list (GET /api/v1/libraries?residenceRegionIds=...) of a running backend and
keeps every region (the random pick on the web takes those naming two places or more). A pill is the place in a library's name
(강동구립도서관 -> 강동구); names shared by several 시도 (중구, 서구, ...) get the 시도 in front (인천 서구).
Run it again after a catalog or library change:

    python -X utf8 scripts/web/gen_start_examples.py [http://localhost:8095]
"""
import json
import re
import sys
import urllib.request
from collections import Counter
from pathlib import Path

BASE = sys.argv[1] if len(sys.argv) > 1 else 'http://localhost:8095'
OUT = Path(__file__).resolve().parents[2] / 'web/src/features/search/startExamples.ts'


def get(path):
    with urllib.request.urlopen(BASE + path) as response:
        return json.load(response)


tree = get('/api/v1/regions')
sidos = [(s['name'], s['shortName']) for s in tree]
gu_names = Counter(c['name'] for s in tree for c in s.get('children', []))
sido_gus = {s['shortName']: {c['name'] for c in s.get('children', [])} for s in tree}
gu_sido = {c['name']: s['shortName'] for s in tree for c in s.get('children', [])}


# old or long 시도 names in library names -> the current short name
SIDO_ALIASES = {
    '서울특별시': '서울', '부산광역시': '부산', '대구광역시': '대구', '인천광역시': '인천', '광주광역시': '전남광주',
    '대전광역시': '대전', '울산광역시': '울산', '세종특별자치시': '세종', '경기도': '경기', '강원특별자치도': '강원',
    '강원도': '강원', '충청북도': '충북', '충청남도': '충남', '전북특별자치도': '전북', '전라북도': '전북',
    '전라남도': '전남광주', '전남': '전남광주', '경상북도': '경북', '경상남도': '경남', '제주특별자치도': '제주',
    '제주도': '제주',
}
# the 시도 as the screens name it (web useRegionTree SHORT_NAME_SHOWN)
SIDO_SHOWN = {'전남광주': '전남'}
# the former 광주광역시 구 keep 「광주」 in front (광주 남구, not 전남 남구): people still say it that way (user 2026-10-20);
# web useRegionTree FORMER_GWANGJU does the same for the region names
FORMER_GWANGJU = {'동구', '서구', '남구', '북구', '광산구'}


def shown(sido, gu=None):
    if sido == '전남광주' and gu in FORMER_GWANGJU:
        return '광주'
    return SIDO_SHOWN.get(sido, sido)


def strip_sido(name):
    """(the 시도 short name the library's name starts with or None, the rest of the name)."""
    prefixes = [(full, short) for full, short in sidos] + [(short, short) for _, short in sidos]
    prefixes += list(SIDO_ALIASES.items())
    for prefix, short in sorted(prefixes, key=lambda x: -len(x[0])):
        if name.startswith(prefix):
            return short, name[len(prefix):].lstrip()
    return None, name


def stem(gu):
    """강서구 -> 강서, 포항시 -> 포항; None for a one-letter stem (중구)."""
    word = gu[:-1] if gu[-1] in '시군구' else gu
    return word if len(word) >= 2 else None


# libraries whose names hide or mislead the place (checked 2026-10-20): name start -> (시도 short name, 시군구)
LIBRARY_PLACES = {
    '선경도서관': ('경기', '수원시'),
    '녹양도서관': ('경기', '의정부시'),
    '성남중앙도서관': ('경기', '성남시'),
    '성남수정도서관': ('경기', '성남시'),
    '이진아기념도서관': ('서울', '서대문구'),
    '여의샛강': ('서울', '영등포구'),
    '화도진도서관': ('인천', '동구'),
    '한밭도서관': ('대전', '중구'),
    '월평도서관': ('대전', '서구'),
    '레인보우영동도서관': ('충북', '영동군'),
    '진영도서관': ('경남', '김해시'),
    '부산남지도서관': ('경남', '창녕군'),
    '마한교육문화회관': ('전북', '익산시'),
}


def named_place(name):
    for start, where in LIBRARY_PLACES.items():
        if name.startswith(start):
            return where
    return None


def place(name, home_sido):
    """The place a library's name names: a 시군구 (its full name or its stem, 광양공공도서관 -> 광양시) of the library's own
    시도 when its name starts with one, else of the person's 시도; or that 시도 itself for a 시도-wide library
    (경상북도교육청 -> 경북). A name shared by several 시도 gets its 시도 in front when not the person's own. None when the
    name names no place."""
    known = named_place(name)
    if known:
        sido, gu = known
        if gu_names[gu] > 1 and sido != home_sido:
            return f'{shown(sido, gu)} {gu}'
        return gu
    lib_sido, rest = strip_sido(name)
    scope = lib_sido or home_sido
    found = None
    for gu in sido_gus.get(scope, ()):
        for word in (gu, stem(gu)):
            if word and rest.startswith(word) and (found is None or len(word) > len(found[1])):
                found = (gu, word)
    if found is None and lib_sido is None:
        # a unique 시군구 name of another 시도 (송파구 for 성남 사람)
        for gu, count in gu_names.items():
            if count == 1 and rest.startswith(gu) and (found is None or len(gu) > len(found[1])):
                found = (gu, gu)
                scope = gu_sido[gu]
    if found is None:
        return SIDO_SHOWN.get(lib_sido, lib_sido) if lib_sido else None
    gu = found[0]
    if gu_names[gu] > 1 and scope != home_sido:
        return f'{shown(scope, gu)} {gu}'
    return gu


def rows_for(region_id, field='residenceRegionIds'):
    data = get(f'/api/v1/libraries?{field}={region_id}')
    return data['libraries'] if isinstance(data, dict) else data


examples = []
for s in tree:
    for c in s.get('children', []):
        rows = rows_for(c['id'])
        eligible = [r for r in rows if r['eligibility'] == 'ELIGIBLE']
        opened = [r for r in rows if r['eligibility'] == 'OPEN']
        own = c['name']
        places = []
        for r in eligible:
            p = place(r['name'], s['shortName'])
            if p and p not in places:
                places.append(p)
        # the person's own place first
        if own in places:
            places.remove(own)
            places.insert(0, own)
        open_places = []
        for r in opened:
            p = place(r['name'], None)
            if p and p not in open_places and p not in places:
                open_places.append(p)
        # every region is kept: the card of someone who picked it uses it (user 2026-10-20: "거주지를 선택해도 아래 박스가
        # 이 형태"); the random pick takes only regions with two places or more (web, EXAMPLES_BY_SIDO)
        # the same for working there (user 2026-10-20: "재직 하나만 있으면 재직 정보로 어디어디 일하면")
        work_eligible = [r for r in rows_for(c['id'], 'workRegionIds') if r['eligibility'] == 'ELIGIBLE']
        work_places = []
        for r in work_eligible:
            p = place(r['name'], s['shortName'])
            if p and p not in work_places:
                work_places.append(p)
        if own in work_places:
            work_places.remove(own)
            work_places.insert(0, own)
        if len(open_places) >= 3:
            examples.append({
                'id': c['id'],
                'place': f"{s['shortName']} {c['name']}",
                'places': places,
                'eligible': len(eligible),
                'openPlaces': open_places,
                'open': len(opened),
                'workPlaces': work_places,
                'workEligible': len(work_eligible),
            })

lines = [
    '// Generated by scripts/web/gen_start_examples.py from the public library list; do not edit by hand.',
    '// The first screen example card picks one of these at random on every visit (user 2026-10-20).',
    '',
    'export interface StartExample {',
    "  /** The 시군구 id of GET /api/v1/regions (found by it: the screen's own 시도 names may differ). */",
    '  id: number',
    '  /** "서울 강서구" */',
    '  place: string',
    '  /** The places in the names of the libraries joined by living there; the own place first when it has one. */',
    '  places: readonly string[]',
    '  /** Libraries joined by living there (the numbers panel\'s count). */',
    '  eligible: number',
    '  /** Places of libraries anyone may join. */',
    '  openPlaces: readonly string[]',
    '  /** Libraries anyone may join. */',
    '  open: number',
    '  /** The same two for working there. */',
    '  workPlaces: readonly string[]',
    '  workEligible: number',
    '}',
    '',
    'export const START_EXAMPLES: readonly StartExample[] = [',
]
for e in examples:
    lines.append(
        '  { id: %d, place: %s, places: %s, eligible: %d, openPlaces: %s, open: %d, workPlaces: %s, workEligible: %d },'
        % (e['id'], json.dumps(e['place'], ensure_ascii=False), json.dumps(e['places'], ensure_ascii=False), e['eligible'],
           json.dumps(e['openPlaces'], ensure_ascii=False), e['open'],
           json.dumps(e['workPlaces'], ensure_ascii=False), e['workEligible'])
    )
lines += [']', '']
OUT.write_text('\n'.join(lines), encoding='utf-8', newline='\n')
print(len(examples), 'examples ->', OUT)
print(Counter(e['place'].split()[0] for e in examples))
