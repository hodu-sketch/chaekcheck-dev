"""Saves /api/v1/books responses and stream targets for the cycle 07 measurement set (plan handoff step 0, step 15).

Usage (from the repository root, the backend running):
  python scripts/catalog/measure_books.py <out dir> [base url] [extra query, e.g. libraryIds=281,300]

The measurement set (plan design-delta "검색 변경"): titles "사랑", "소년", "해리", "역사", author "김영하",
publisher "민음사", two title+author ANDs and one sort by publication date. Each runs under three conditions
(no region, one region, scope=joinable), pages 1 and 2. Writes one JSON file per request, the first stream event
(targets) per condition, and times.tsv (client wall time, ms; the server's own time is in the query plan log).
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

QUERIES = [
    ('love', {'title': '사랑'}),
    ('boy', {'title': '소년'}),
    ('harry', {'title': '해리'}),
    ('history', {'title': '역사'}),
    ('kimyoungha', {'author': '김영하'}),
    ('minumsa', {'publisher': '민음사'}),
    ('and-farewell', {'title': '작별하지 않는다', 'author': '한강'}),
    ('and-behave', {'title': '행동', 'author': '새폴스키'}),
    ('history-newest', {'title': '역사', 'sort': 'newest'}),
]
CONDITIONS = [
    ('none', {}),
    ('region', {'residenceRegionIds': '1101'}),  # 서울 종로구
    ('joinable', {'scope': 'joinable'}),
]


def get(url):
    started = time.perf_counter()
    with urllib.request.urlopen(url, timeout=60) as response:
        body = response.read()
    return body, (time.perf_counter() - started) * 1000


def first_event(url):
    """The first SSE event (targets) of a search stream; the rest is not read."""
    request = urllib.request.Request(url, headers={'Accept': 'text/event-stream'})
    with urllib.request.urlopen(request, timeout=60) as response:
        lines = []
        for raw in response:
            line = raw.decode('utf-8').rstrip('\r\n')
            if not line and lines:
                break
            lines.append(line)
    data = '\n'.join(l[5:].lstrip() for l in lines if l.startswith('data:'))
    return json.loads(data)


def main(out_dir, base='http://localhost:8080', extra=''):
    sys.stdout.reconfigure(encoding='utf-8')
    os.makedirs(out_dir, exist_ok=True)
    extra_params = dict(urllib.parse.parse_qsl(extra))
    times = ['query\tcondition\tpage\tms\ttotal']
    for cname, cparams in CONDITIONS:
        params = dict(cparams, **extra_params, title='사랑')
        targets = first_event(f'{base}/api/v1/ebook-searches/stream?{urllib.parse.urlencode(params)}')
        json.dump(targets, open(f'{out_dir}/targets-{cname}.json', 'w', encoding='utf-8'), ensure_ascii=False,
                  indent=1)
        for qname, qparams in QUERIES:
            for page in (1, 2):
                params = dict(qparams, **cparams, **extra_params, page=page)
                body, ms = get(f'{base}/api/v1/books?{urllib.parse.urlencode(params)}')
                result = json.loads(body)
                json.dump(result, open(f'{out_dir}/books-{qname}-{cname}-p{page}.json', 'w', encoding='utf-8'),
                          ensure_ascii=False, indent=1)
                total = result.get('totalCount', result.get('total', ''))
                times.append(f'{qname}\t{cname}\t{page}\t{ms:.0f}\t{total}')
                print(times[-1])
    open(f'{out_dir}/times.tsv', 'w', encoding='utf-8').write('\n'.join(times) + '\n')


if __name__ == '__main__':
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    main(*sys.argv[1:4])
