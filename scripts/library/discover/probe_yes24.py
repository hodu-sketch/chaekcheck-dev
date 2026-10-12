"""Probe YES24 e-library sites once: alive? which screen (YES24_V1/V2/V3)? how many titles?

Usage: python probe_yes24.py <in.tsv> <out.tsv>
Input columns: platform, name, url. Two requests per site (home, blank search), one at a time per host, 1 s apart.
Output columns match what `crawl_catalog.py import-probe` reads: platform_type, name, url, base_url, status, titles.
"""
import csv
import sys
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urlsplit

# crawl_catalog.py lives in scripts/crawl/ (ADR-0047 C)
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "crawl"))
import crawl_catalog as c  # noqa: E402


def get(url, host):
    req = c.urllib.request.Request(url, headers={"User-Agent": c.USER_AGENT})
    with c._host_locks[host]:
        wait = c._host_last[host] + c.MIN_GAP - c.time.monotonic()
        if wait > 0:
            c.time.sleep(wait)
        try:
            with c.urllib.request.urlopen(req, timeout=40, context=c.SSL) as r:
                return r.geturl(), r.read()
        finally:
            c._host_last[host] = c.time.monotonic()


def base_of(original, final):
    """Site root the search path hangs off: final scheme/host + the path prefix of the listed address (/YES24, /B2B_x)."""
    f = urlsplit(final)
    prefix = urlsplit(original).path.rstrip("/")
    for tail in ("/main", "/index", "/ebook", "/search"):
        if tail in prefix.lower():
            prefix = prefix[:prefix.lower().index(tail)]
    return f"{f.scheme}://{f.netloc}{prefix}"


def screen(body):
    head = body[:3000].lower()
    euc = b"euc-kr" in head and b"utf-8" not in head
    for name, parser in (("YES24_V1", lambda b: c.parse_yes24_v1(b, "")),
                         ("YES24_V3" if euc else "YES24_V2",
                          lambda b: c.parse_yes24_v2(b, "", "euc-kr" if euc else "utf-8"))):
        try:
            total, items = parser(body)
            if total is not None and (items or total == 0):
                return name, total
        except Exception:  # noqa: BLE001
            continue
    return None, None


def probe(row):
    url = row["url"].strip()
    out = dict(platform_type="", name=row["name"], url=url, base_url="", status="", titles="", note="",
               checked_at=c.now())
    host = c.host_key(url)
    try:
        final, _ = get(url, host)
        base = base_of(url, final)
        host = c.host_key(base)
        _, body = get(f"{base}/search/?src_key=&srch_order=title&sort=title&view=50&page_num=1", host)
        kind, total = screen(body)
        root = f"{urlsplit(base).scheme}://{urlsplit(base).netloc}"
        if kind is None and base != root:  # the listed /YES24-style prefix may be gone: try the site root
            _, body = get(f"{root}/search/?src_key=&srch_order=title&sort=title&view=50&page_num=1", host)
            kind, total = screen(body)
            if kind is not None:
                base = root
        if kind is None:
            out.update(base_url=base, status="NOT_YES24_SCREEN")
        else:
            out.update(platform_type=kind, base_url=base, status="OK", titles=total)
    except Exception as e:  # noqa: BLE001
        out.update(status="ERROR", note=(type(e).__name__ + ": " + str(e))[:150])
    return out


COLS = ["platform_type", "name", "url", "base_url", "status", "titles", "note", "checked_at"]
_out_lock = c.threading.Lock()


def append(path, row):
    """Write each result at once, so a stopped run keeps what it did."""
    with _out_lock:
        new = not Path(path).exists()
        with open(path, "a", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=COLS, delimiter="	")
            if new:
                w.writeheader()
            w.writerow(row)


def main():
    out_path = sys.argv[2]
    done_urls = set()
    if Path(out_path).exists():  # resume
        done_urls = {r["url"].strip() for r in csv.DictReader(open(out_path, encoding="utf-8"), delimiter="	")}
    rows = [r for r in csv.DictReader(open(sys.argv[1], encoding="utf-8"), delimiter="	")
            if r["url"].strip() not in done_urls]
    by_host = defaultdict(list)
    for r in rows:
        by_host[c.host_key(r["url"])].append(r)
    c.log(f"{len(rows)} sites to probe on {len(by_host)} hosts ({len(done_urls)} done before)")
    with ThreadPoolExecutor(max_workers=24) as ex:
        list(ex.map(lambda rs: [append(out_path, probe(r)) for r in rs], by_host.values()))
    counts = defaultdict(int)
    for r in csv.DictReader(open(out_path, encoding="utf-8"), delimiter="	"):
        counts[(r["status"], r["platform_type"])] += 1
    for k in sorted(counts):
        print(*k, counts[k], sep="	")


if __name__ == "__main__":
    main()
