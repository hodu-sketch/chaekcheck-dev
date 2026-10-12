"""Probe Bookcube / Aladin e-library sites once each: alive? how many titles?

Usage: python probe_other_platforms.py <in.tsv> <out.tsv>
in.tsv columns: platform, name, url. One request per site (two for Aladin: home + full list),
one request at a time per host, at least 1 s apart.
"""
import csv
import re
import sys
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from urllib.parse import urljoin, urlsplit

# crawl_catalog.py lives in scripts/crawl/ (ADR-0047 C)
from pathlib import Path  # noqa: E402
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "crawl"))
from crawl_catalog import SSL, USER_AGENT, host_key  # noqa: E402
import urllib.request  # noqa: E402

_last = defaultdict(float)


def get(url, host):
    wait = _last[host] + 1.0 - time.monotonic()
    if wait > 0:
        time.sleep(wait)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=30, context=SSL) as r:
            return r.geturl(), r.read()
    finally:
        _last[host] = time.monotonic()


def text(body):
    head = body[:3000].lower()
    for enc in (["euc-kr"] if b"euc-kr" in head and b"utf-8" not in head else []) + ["utf-8", "euc-kr"]:
        try:
            return body.decode(enc)
        except UnicodeDecodeError:
            continue
    return body.decode("utf-8", "replace")


def bookcube_base(url):
    m = re.match(r"(https?://[^/]+/(?:FxLibrary\w*|BookLive))", url.rstrip("/") + "/")
    return m.group(1) if m else url.rstrip("/")


def probe_bookcube(url, host):
    base = bookcube_base(url)
    final, body = get(base + "/xml/productList/?itemCount=1", host)
    s = text(body)
    total = re.search(r"<totalItem>(\d+)</totalItem>", s)
    volume = re.search(r"<totalVolume>(\d+)</totalVolume>", s)
    if not total:
        return "NO_XML", final, None, None, ""
    return "OK", base, int(total.group(1)), int(volume.group(1)) if volume else None, "xml/productList"


def probe_aladin(url, host):
    final, body = get(url, host)
    s = text(body)
    m = re.search(r"URL=([^'\"> ]+)", s, re.I)
    if m and len(s) < 2000:
        final, body = get(urljoin(final, m.group(1)), host)
        s = text(body)
    links = re.findall(r"""href=["']([^"']*sub\.htm\?nav_code=(\w+)&(?:amp;)?view_level=1&(?:amp;)?scroll=0)["']""", s)
    if not links:
        return "NO_LIST_LINK", final, None, None, ""
    list_url = urljoin(final, links[0][0].replace("&amp;", "&"))
    final2, body2 = get(list_url, host)
    s2 = re.sub(r"<[^>]+>", " ", text(body2))
    total = re.search(r"총\s*([\d,]+)\s*건의\s*도서", s2)
    if not total:
        return "NO_TOTAL", list_url, None, None, ""
    return "OK", list_url, int(total.group(1).replace(",", "")), None, "sub.htm full list"


def probe(row):
    platform, name, url = row["platform"], row["name"], row["url"]
    host = host_key(url)  # shared hosting (bookcube.biz, dasangng.co.kr ...) is one queue
    try:
        status, final, total, volume, how = (probe_bookcube if platform == "BOOKCUBE" else probe_aladin)(url, host)
    except Exception as e:  # noqa: BLE001
        status, final, total, volume, how = "ERROR", url, None, None, type(e).__name__ + ": " + str(e)[:120]
    return dict(platform=platform, name=name, url=url, status=status, list_url=final, titles=total,
                volumes=volume, note=how, checked_at=datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"))


def main():
    rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8"), delimiter="\t"))
    by_host = defaultdict(list)
    for r in rows:
        by_host[host_key(r["url"])].append(r)
    out = []

    def run(rs):
        return [probe(r) for r in rs]

    with ThreadPoolExecutor(max_workers=16) as ex:
        for res in ex.map(run, by_host.values()):
            out.extend(res)
    cols = ["platform", "name", "url", "status", "list_url", "titles", "volumes", "note", "checked_at"]
    with open(sys.argv[2], "w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=cols, delimiter="\t")
        w.writeheader()
        w.writerows(sorted(out, key=lambda r: (r["platform"], r["status"], r["name"])))
    counts = defaultdict(int)
    for r in out:
        counts[(r["platform"], r["status"])] += 1
    print(dict(counts))


if __name__ == "__main__":
    main()
