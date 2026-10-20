"""Builds backend/src/main/resources/geo/kr-sido.csv.gz: the Korean address ranges of DB-IP's free "IP to City Lite"
list with the 시도 short name each is in (user 2026-10-20: "예시 카드의 시도만 IP로 고르기").

Source: https://db-ip.com/db/download/ip-to-city-lite (CC BY 4.0, attribution "IP Geolocation by DB-IP" shown on the
소개 page). Download the month's CSV into data/geo/ first (git-ignored), then:

    python -X utf8 scripts/geo/build_kr_sido.py data/geo/dbip-city-lite-YYYY-MM.csv.gz

Output lines: `4|start|end|시도` (IPv4 as numbers) or `6|start|end|시도` (IPv6 as 32 hex digits); neighbouring ranges of
the same 시도 are joined. A region DB-IP names that is not in the map below stops the script.
"""
import csv
import gzip
import ipaddress
import sys
from collections import Counter
from pathlib import Path

OUT = Path(__file__).resolve().parents[2] / 'backend/src/main/resources/geo/kr-sido.csv.gz'

# DB-IP's region names -> the 시도 short names of GET /api/v1/regions
SIDO = {
    'Seoul': '서울',
    'Busan': '부산',
    'Daegu': '대구',
    'Incheon': '인천',
    'Gwangju': '전남광주',
    'Jeollanam-do': '전남광주',
    'Daejeon': '대전',
    'Ulsan': '울산',
    'Sejong-si': '세종',
    'Gyeonggi-do': '경기',
    'Gangwon-do': '강원',
    'North Chungcheong': '충북',
    'Chungcheongnam-do': '충남',
    'Jeollabuk-do': '전북',
    'Gyeongsangbuk-do': '경북',
    'Gyeongsangnam-do': '경남',
    'Jeju-do': '제주',
}


def main(source):
    ranges = {4: [], 6: []}
    with gzip.open(source, 'rt', encoding='utf-8', newline='') as f:
        for row in csv.reader(f):
            if row[3] != 'KR':
                continue
            if row[4] not in SIDO:
                sys.exit(f'unknown region: {row[4]!r} ({row[0]})')
            start, end = ipaddress.ip_address(row[0]), ipaddress.ip_address(row[1])
            ranges[start.version].append((int(start), int(end), SIDO[row[4]]))
    lines, counts = [], Counter()
    for version, items in ranges.items():
        items.sort()
        joined = []
        for start, end, sido in items:
            if joined and joined[-1][2] == sido and joined[-1][1] + 1 == start:
                joined[-1] = (joined[-1][0], end, sido)
            else:
                joined.append((start, end, sido))
        for start, end, sido in joined:
            if version == 4:
                lines.append(f'4|{start}|{end}|{sido}')
            else:
                lines.append(f'6|{start:032x}|{end:032x}|{sido}')
            counts[sido] += 1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(OUT, 'wt', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    print(len(lines), 'ranges ->', OUT, OUT.stat().st_size, 'bytes')
    print(dict(counts))


if __name__ == '__main__':
    main(sys.argv[1])
