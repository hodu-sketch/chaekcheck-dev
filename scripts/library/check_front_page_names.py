"""Does each library's front page name that library? (user request 2026-10-05: a library's site showed another
institution's name). One GET per library (homepage_url, else base_url), redirects followed, Windows curl (see
docs/libraries/library-maintenance.md 4-7 4: it handles more TLS setups than Python). Libraries on the same host are asked one
at a time 1.1s apart; at most 12 hosts at once. Judging is left to a person: the output only says whether a key part
of the name is on the page and which other institution names appear in the page title.

Usage (from the repository root):
  python scripts/library/check_front_page_names.py <in.tsv> <out.tsv> [--mobile]
--mobile: send a phone browser's User-Agent. Some sites send phones to a separate mobile site (e.g. YES24 V1 `/m/`),
which can carry another institution's name: 동대문구립도서관(YES24) `http://e-book.l4d.or.kr:8088/m/` showed
"경동대학교" (user's phone, 2026-10-05) while the desktop page said 동대문구립도서관.
in.tsv: id<TAB>name<TAB>url per line.
out.tsv: id, name, url, http code, final url, name found (yes/no), page title, bytes.
"""
import concurrent.futures
import re
import subprocess
import sys
import threading
import time
from collections import defaultdict
from urllib.parse import urlsplit

CURL = r'C:\Windows\System32\curl.exe'
USER_AGENT = 'chaekcheck/0.0.1'
MOBILE_USER_AGENT = ('Mozilla/5.0 (Linux; Android 14; SM-S918N) AppleWebKit/537.36 (KHTML, like Gecko) '
                     'Chrome/129.0 Mobile Safari/537.36')


def keys(name):
    """Parts of the name that should appear on the page: the name and its first word. Names carry no vendor label
    since cycle 09 (ADR-0071)."""
    base = name.strip()
    first = re.split(r'\s+', base)[0]
    short = re.sub(r'(전자도서관|전자책도서관|도서관|중앙도서관|학술정보원|학술정보관)$', '', first)
    return {k for k in (base, first, short) if len(k) >= 2}


def decode(raw):
    for enc in ('utf-8', 'cp949'):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode('utf-8', 'replace')


def fetch(url, agent=USER_AGENT):
    done = subprocess.run([CURL, '-s', '-L', '--max-redirs', '5', '-m', '15', '-A', agent,
                           '-w', '\n@@%{http_code} %{url_effective}', url], capture_output=True)
    text = decode(done.stdout)
    body, _, meta = text.rpartition('\n@@')
    code, _, final = meta.partition(' ')
    return code, final, body


def main(src, dst, mobile=False):
    agent = MOBILE_USER_AGENT if mobile else USER_AGENT
    sys.stdout.reconfigure(encoding='utf-8')
    rows = [line.rstrip('\n').split('\t') for line in open(src, encoding='utf-8') if line.strip()]
    by_host = defaultdict(list)
    for r in rows:
        by_host[urlsplit(r[2]).hostname].append(r)
    out = open(dst, 'w', encoding='utf-8')
    out.write('id\tname\turl\tcode\tfinal\tname_found\ttitle\tbytes\n')
    lock = threading.Lock()

    def run(host_rows):
        for lid, name, url in host_rows:
            try:
                code, final, body = fetch(url, agent)
            except Exception as ex:  # noqa: BLE001 - one site's failure must not stop the others
                code, final, body = 'ERR', str(ex), ''
            flat = re.sub(r'\s+', '', re.sub(r'&nbsp;', ' ', body))
            found = any(re.sub(r'\s+', '', k) in flat for k in keys(name))
            title = re.sub(r'\s+', ' ', ' '.join(re.findall(r'<title[^>]*>(.*?)</title>', body, re.S | re.I)))[:80]
            with lock:
                out.write(f'{lid}\t{name}\t{url}\t{code}\t{final}\t{"yes" if found else "no"}\t{title}\t{len(body)}\n')
                out.flush()
            time.sleep(1.1)

    with concurrent.futures.ThreadPoolExecutor(12) as pool:
        list(pool.map(run, by_host.values()))
    out.close()


if __name__ == '__main__':
    if len(sys.argv) not in (3, 4) or (len(sys.argv) == 4 and sys.argv[3] != '--mobile'):
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2], len(sys.argv) == 4)
