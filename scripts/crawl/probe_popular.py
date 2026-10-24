"""Cycle 23 pre-test (plan cycle 23 spec R2-2, R6-1a; request from the plan cloud session 2026-10-24): the popular
lists of a few library sites, matched to our books. Read only: GET requests to the sites (one at a time, GAP seconds
apart, sites whose list crawl already finished in the running crawl) and SELECTs on the PC's service DB.

  python -X utf8 scripts/crawl/probe_popular.py [--out data/cycle23-pretest]

Writes <out>/popular.tsv (one row per site, order and rank) and prints a summary. Kyobo subscription sites are all on
*.dkyobobook.co.kr, which is never requested (cycle 13 R1-6), so only Kyobo general sites are asked.
"""
from __future__ import annotations

import argparse
import re
import sys
import time
import urllib.request
from pathlib import Path

from bs4 import BeautifulSoup

sys.path.insert(0, str(Path(__file__).resolve().parent))
import crawl_catalog as cc  # noqa: E402
import recommended_lists as rl  # noqa: E402

GAP = 2.0  # one request at a time in this script; at least 1 s per server (IP) is the rule (user 2026-10-24)
TOP_WIDE = 400  # "사이트당 앞 5쪽" in the request: 5 pages of 80 (the most Kyobo offers), 8 pages of 50 on YES24 V1
KYOBO_PAGE = 80
# collection library id, base url, platform; chosen 2026-10-24: done in the 2026-10-23 15:15 crawl, own servers
KYOBO_SITES = [(79, 'https://ebook.seocholib.or.kr', '서초구립반포도서관'),
               (99, 'https://nm.yeoncheon.go.kr', '연천군도서관'),
               (60, 'https://elib.dobong.kr', '도봉문화정보도서관'),
               (73, 'https://ebook.bcl.go.kr:444', '부천시립도서관')]
YES24_V1_SITES = [(263, 'http://ebook.nls.go.kr', '국립세종도서관'),
                  (229, 'http://vodbook.snlib.go.kr:8081', '성남시도서관사업소')]
KYOBO_TERMS = 'https://www.ghlib.go.kr:446/elibrary-front/member/memberPrivacy.ink'
COLUMNS = ['site', 'collection_library_id', 'platform', 'order', 'rank', 'item_key', 'title', 'author', 'publisher',
           'published', 'holdings', 'loans_now', 'reservations', 'loans_total', 'book_id', 'match']


def busy_ips() -> set[str]:
    """IPs the running list crawl still uses: never asked here (fetch_covers.busy_ips)."""
    import fetch_covers
    return fetch_covers.busy_ips()


class Fetch:
    def __init__(self, avoid: set[str]):
        self.last = 0.0
        self.count = 0
        self.avoid = avoid

    def __call__(self, url: str) -> bytes:
        import socket
        from urllib.parse import urlsplit
        if cc.is_blocked_host(url):
            raise cc.BlockedHost(url)
        if socket.gethostbyname(urlsplit(url).hostname) in self.avoid:
            raise RuntimeError(f'{urlsplit(url).hostname}: the running crawl uses this server; not asked')
        wait = self.last + GAP - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        try:
            request = urllib.request.Request(url, headers={'User-Agent': cc.USER_AGENT})
            with urllib.request.urlopen(request, timeout=90, context=cc.SSL) as response:
                return response.read()
        finally:
            self.last = time.monotonic()
            self.count += 1


def kyobo_rows(body: bytes) -> tuple[int | None, list[dict]]:
    doc = BeautifulSoup(body.decode('utf-8', 'replace'), 'lxml')
    total = cc.total_of(cc.txt(doc.select_one('div.book_resultTxt')) or '', r'총\s*([\d,]+)\s*개')
    rows = []
    for li in doc.select('ul.book_resultList > li'):
        a = li.select_one('li.tit a')
        m = re.search(r"fnContentClick\(\s*this\s*,\s*'([^']*)'\s*,\s*'([^']*)'", a.get('onclick', '')) if a else None
        if not m:
            continue
        writer = li.select_one('li.writer')
        span = writer.find('span') if writer else None
        text = writer.get_text(' ', strip=True) if writer else ''
        date = cc.date_of(text)
        author = text.split(span.get_text(strip=True))[0].strip() if span else text
        use = li.select_one('p.use')
        u = use.get_text(' ', strip=True) if use else ''
        lm = re.search(r'대출\s*:\s*(\d+)\s*/\s*(\d+)', u)
        rm = re.search(r'예약\s*:\s*(\d+)', u)
        rows.append(dict(item_key=m.group(2), title=cc.txt(a), author=author, publisher=cc.txt(span), published=date,
                         loans_now=lm.group(1) if lm else '', holdings=lm.group(2) if lm else '',
                         reservations=rm.group(1) if rm else '', loans_total=''))
    return total, rows


def yes24_v1_rows(body: bytes) -> tuple[int | None, list[dict]]:
    total, items = cc.parse_yes24_v1(body, '')
    doc = BeautifulSoup(cc.decode(body, 'euc-kr'), 'lxml')
    infos = [li.select_one('div.rentinfo') for li in doc.select('div#booklist ul.list > li.line')
             if cc.GOODS.search((li.select_one('span.book_tit > a') or {}).get('href', '') or '')]
    rows = []
    for item, info in zip(items, infos):
        nums = dict(re.findall(r'(보유|대출|예약|누적대출|누적예약)\s*(\d+)', info.get_text(' ', strip=True) if info else ''))
        rows.append(dict(item_key=item['item_key'], title=item['title'], author=item['author_raw'],
                         publisher=item['publisher'], published=item['published_on'], holdings=nums.get('보유', ''),
                         loans_now=nums.get('대출', ''), reservations=nums.get('예약', ''),
                         loans_total=nums.get('누적대출', '')))
    return total, rows


class Books:
    """(collection library id, item key) → service book id; Kyobo and YES24 codes are one numbering per vendor."""

    def __init__(self, env: dict):
        import psycopg
        self.db = psycopg.connect(host='localhost', port=env.get('DB_PORT', '5432'), dbname=env['DB_NAME'],
                                  user=env['DB_USERNAME'], password=env.get('DB_PASSWORD', ''), autocommit=True)

    def match(self, collection_id: int, item_key: str, platform: str) -> tuple[str, str]:
        with self.db.cursor() as cur:
            cur.execute("""SELECT h.book_id FROM catalog.holding h JOIN collection.collection_link l
                           ON l.library_id = h.library_id WHERE l.collection_library_id = %s AND h.item_key = %s""",
                        (collection_id, item_key))
            row = cur.fetchone()
            if row:
                return str(row[0]), 'same library'
            cur.execute('SELECT book_id FROM catalog.holding WHERE item_key = %s AND platform = %s LIMIT 1',
                        (item_key, platform))
            row = cur.fetchone()
            return (str(row[0]), 'same code elsewhere') if row else ('', 'none')


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', default=str(rl.ROOT / 'data' / 'cycle23-pretest'))
    parser.add_argument('--kyobo-only', action='store_true', help='keep the YES24 rows of popular.tsv, ask Kyobo again')
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    fetch = Fetch(busy_ips())
    print(f'servers the running crawl uses (not asked): {len(fetch.avoid)}')
    books = Books(rl.read_env(rl.ROOT / '.env'))
    rows: list[dict] = []
    if args.kyobo_only:
        _, kept = rl.read_tsv(out / 'popular.tsv')
        rows = [r for r in kept if r['platform'] == 'YES24']
    for lib_id, base, name in KYOBO_SITES:
        total = None
        for page in range(1, TOP_WIDE // KYOBO_PAGE + 1):
            url = (f'{base}/elibrary-front/content/contentList.ink?cttsDvsnCode=001&contentAll=Y&ctgrId='
                   f'&orderByKey=loanNmvl&pageIndex={page}&recordCount={KYOBO_PAGE}')
            total, got = kyobo_rows(fetch(url))
            for r in got:
                r.update(site=name, collection_library_id=lib_id, platform='KYOBO', order='loanNmvl', rank=len(
                    [x for x in rows if x['site'] == name]) + 1)
                rows.append(r)
        print(f'{name}: total {total}, read {len([x for x in rows if x["site"] == name])}')
    for lib_id, base, name in ([] if args.kyobo_only else YES24_V1_SITES):
        total = None
        for page in range(1, TOP_WIDE // 50 + 1):
            total, got = yes24_v1_rows(fetch(f'{base}/search/?src_key=&srch_order=title&sort=lending&view=50'
                                             f'&page_num={page}'))
            for r in got:
                r.update(site=name, collection_library_id=lib_id, platform='YES24', order='lending', rank=len(
                    [x for x in rows if x['site'] == name]) + 1)
                rows.append(r)
        print(f'{name}: total {total}, read {len([x for x in rows if x["site"] == name])}')
    for r in rows:
        r['book_id'], r['match'] = books.match(r['collection_library_id'], r['item_key'], r['platform'])
    rl.write_tsv(out / 'popular.tsv', COLUMNS, rows)
    if args.kyobo_only:
        print(f'requests: {fetch.count}')
        return 0
    terms = fetch(KYOBO_TERMS).decode('utf-8', 'replace')
    (out / 'kyobo-terms-ghlib.html').write_text(terms, encoding='utf-8')
    text = re.sub(r'\s+', ' ', BeautifulSoup(terms, 'lxml').get_text(' '))
    for word in ('자동', '수집', '크롤', '로봇', '스크래', '프로그램', '복제', '영리'):
        for m in re.finditer(word, text):
            print(f'terms [{word}]: …{text[max(0, m.start() - 80):m.end() + 80]}…')
    print(f'requests: {fetch.count}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
