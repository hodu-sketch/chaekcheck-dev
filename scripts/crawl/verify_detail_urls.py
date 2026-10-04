"""Open one detail_url per library x content_type and check the page names that book.

Usage: python verify_detail_urls.py [out.tsv] [PLATFORM,PLATFORM...]
One request at a time per host, at least 1 s apart. Do not run together with crawl_catalog.py.
"""
import csv
import html
import re
import sqlite3
import sys
import time
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from crawl_catalog import DB, SSL, USER_AGENT, now  # noqa: E402

_last = defaultdict(float)


def fetch(url, host):
    wait = _last[host] + 1.0 - time.monotonic()
    if wait > 0:
        time.sleep(wait)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=40, context=SSL) as r:
            return r.status, r.read()
    finally:
        _last[host] = time.monotonic()


def norm(t):
    return re.sub(r"[\s\W_]+", "", t or "")


def check(row):
    lib_id, name, platform, host, ctype, title, url = row
    try:
        status, body = fetch(url, host)
        texts = [body.decode(e, "replace") for e in ("utf-8", "euc-kr")]
        key = norm(title)[:10]
        found = any(key and key in norm(html.unescape(t)) for t in texts)
        verdict = "OK" if found else "TITLE_NOT_FOUND"
    except Exception as e:  # noqa: BLE001
        status, verdict = None, "ERROR " + type(e).__name__ + ": " + str(e)[:80]
    return dict(library_id=lib_id, library=name, platform=platform, content_type=ctype, title=title, url=url,
                http=status, verdict=verdict, checked_at=now())


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else str(Path(DB).parent / "logs" / "verify-detail-urls.tsv")
    platforms = sys.argv[2].split(",") if len(sys.argv) > 2 else None
    con = sqlite3.connect(DB, timeout=120)
    rows = con.execute("""
        SELECT h.library_id, l.name, l.platform_type, l.host, h.content_type, h.title, h.detail_url
        FROM holding h JOIN library l ON l.id = h.library_id
        WHERE h.rowid IN (SELECT min(rowid) FROM holding WHERE removed_at IS NULL
                          GROUP BY library_id, content_type)""").fetchall()
    if platforms:
        rows = [r for r in rows if r[2] in platforms]
    by_host = defaultdict(list)
    for r in rows:
        by_host[r[3]].append(r)
    results = []
    with ThreadPoolExecutor(max_workers=32) as ex:
        for res in ex.map(lambda rs: [check(r) for r in rs], by_host.values()):
            results.extend(res)
    with open(out, "w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(results[0].keys()), delimiter="\t")
        w.writeheader()
        w.writerows(sorted(results, key=lambda r: (r["verdict"], r["platform"], r["library_id"])))
    counts = defaultdict(int)
    for r in results:
        counts[(r["platform"], r["content_type"], r["verdict"].split(" ")[0])] += 1
    for k in sorted(counts):
        print(*k, counts[k], sep="\t")
    print("written", out)


if __name__ == "__main__":
    main()
