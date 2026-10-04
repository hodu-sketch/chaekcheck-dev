"""Probe Aladin e-library candidates once: alive? standard screen (sub/sub.htm)? e-book menu code? how many titles?

Usage: python probe_aladin.py <in.tsv> <out.tsv>
Input columns: platform, name, url. Each result is written at once; a rerun skips sites already in out.tsv.
Output matches `crawl_catalog.py import-probe`: base_url = '{root}?nav_code={code}', platform_type ALADIN_V1.
"""
import csv
import re
import sys
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urljoin, urlsplit

# crawl_catalog.py lives in scripts/crawl/ (ADR-0047 C)
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "crawl"))
import crawl_catalog as c  # noqa: E402

COLS = ["platform_type", "name", "url", "base_url", "status", "titles", "note", "checked_at"]
NAV = re.compile(r"""sub\.htm\?nav_code=(\w+)&(?:amp;)?view_level=1&(?:amp;)?scroll=0""")
REFRESH = re.compile(r"""URL=([^'"> ]+)""", re.I)
_out_lock = c.threading.Lock()


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


def root_of(final):
    u = urlsplit(final)
    path = u.path
    for cut in ("/main/", "/sub/", "/index"):
        if cut in path:
            path = path[:path.index(cut)]
            break
    else:
        path = path.rsplit("/", 1)[0] if "." in path.rsplit("/", 1)[-1] else path
    return f"{u.scheme}://{u.netloc}{path.rstrip('/')}"


def probe(row):
    url = row["url"].strip()
    out = dict(platform_type="", name=row["name"], url=url, base_url="", status="", titles="", note="",
               checked_at=c.now())
    host = c.host_key(url)
    try:
        final, body = get(url, host)
        page = c.decode(body, "utf-8")
        m = REFRESH.search(page)
        if m and len(page) < 3000:  # index page that forwards to main/main.htm
            final, body = get(urljoin(final, m.group(1)), host)
            page = c.decode(body, "utf-8")
        nav = NAV.search(page)
        if not nav:
            out.update(status="NO_LIST_LINK", base_url=final)
            return out
        root = root_of(final)
        base = f"{root}?nav_code={nav.group(1)}"
        lib = dict(name=row["name"], platform_type="ALADIN_V1", base_url=base, host=c.host_key(base))
        total, _ = c.parse(lib, get(c.page_url(lib, 1, ("MAIN", None)), host)[1], ("MAIN", None))
        out.update(platform_type="ALADIN_V1", base_url=base, status="OK", titles=total)
    except Exception as e:  # noqa: BLE001
        out.update(status="ERROR", note=(type(e).__name__ + ": " + str(e))[:150])
    return out


def append(path, row):
    with _out_lock:
        new = not Path(path).exists()
        with open(path, "a", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=COLS, delimiter="\t")
            if new:
                w.writeheader()
            w.writerow(row)


def main():
    out_path = sys.argv[2]
    done = set()
    if Path(out_path).exists():
        done = {r["url"].strip() for r in csv.DictReader(open(out_path, encoding="utf-8"), delimiter="\t")}
    rows = [r for r in csv.DictReader(open(sys.argv[1], encoding="utf-8"), delimiter="\t")
            if r["url"].strip() not in done]
    by_host = defaultdict(list)
    for r in rows:
        by_host[c.host_key(r["url"])].append(r)
    c.log(f"{len(rows)} candidates to probe on {len(by_host)} hosts ({len(done)} done before)")
    with ThreadPoolExecutor(max_workers=24) as ex:
        list(ex.map(lambda rs: [append(out_path, probe(r)) for r in rs], by_host.values()))
    counts = defaultdict(int)
    for r in csv.DictReader(open(out_path, encoding="utf-8"), delimiter="\t"):
        counts[(r["status"], r["platform_type"])] += 1
    for k in sorted(counts):
        print(*k, counts[k], sep="\t")


if __name__ == "__main__":
    main()
