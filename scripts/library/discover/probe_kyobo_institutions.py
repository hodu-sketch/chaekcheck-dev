"""Probe KYOBO institution e-libraries once: alive? regular (/elibrary-front) or subscription? how many titles?

Usage: python probe_kyobo_institutions.py <kyobo-institutions.tsv> <out.tsv>
Input columns: category, name, url. Sites already in library (same origin) are marked KNOWN without a request.
Requests follow crawl_catalog's rules: one at a time per host (all *.dkyobobook.co.kr = one host), 1 s apart.
"""
import csv
import re
import sys
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urlsplit

# crawl_catalog.py lives in scripts/crawl/ (ADR-0047 C)
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "crawl"))
import crawl_catalog as c  # noqa: E402

TOTAL = re.compile(r"총\s*([\d,]+)\s*개")


def origin(url):
    u = urlsplit(url.strip())
    return f"{u.scheme}://{u.netloc}".lower()


def total_of(body):
    text = c.html.unescape(re.sub(r"<[^>]+>", " ", c.decode(body, "utf-8"))).replace("\xa0", " ")
    m = TOTAL.search(text)
    return int(m.group(1).replace(",", "")) if m else None


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


def probe(row):
    url = row["url"].strip()
    host = c.host_key(url)
    out = dict(category=row["category"], name=row["name"], url=url, status="", platform="", base_url="",
               titles="", model="", note="", checked_at=c.now())
    try:
        final, body = get(url, host)
        page = c.decode(body, "utf-8")
        o = origin(final)
        m = re.search(r"library_model:\s*'([^']*)'", page)  # 대여 = subscription, 소장 = owned, 혼합 = both
        out["model"] = m.group(1) if m else ""
        host = c.host_key(final)
        if "/elibrary-front" in final or "/elibrary-front/" in page:
            out.update(platform="KYOBO_V1", base_url=o)
            _, b = get(f"{o}/elibrary-front/search/searchList.ink?schClst=all&schDvsn=000&schTxt=%20"
                       f"&recordCount=1&pageIndex=1", host)
        else:
            out.update(platform="KYOBO_SUBSCRIPTION_V1", base_url=o)
            _, b = get(f"{o}/content/contentList.ink?cttsDvsnCode=001&contentAll=Y&ctgrId=&orderByKey=cttsHnglName"
                       f"&pageIndex=1&recordCount=20", host)  # this list accepts 20/40/60/80 only
        t = total_of(b)
        out.update(status="OK" if t is not None else "NO_TOTAL", titles=t if t is not None else "")
    except Exception as e:  # noqa: BLE001
        out.update(status="ERROR", note=(type(e).__name__ + ": " + str(e))[:150])
    return out


COLS = ["category", "name", "url", "status", "platform", "base_url", "titles", "model", "note", "checked_at"]
_out_lock = c.threading.Lock()


def append(path, row):
    """Write each result as soon as it is known, so a stopped run keeps what it did."""
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
    if Path(out_path).exists():  # resume: skip sites an earlier run already wrote
        done_urls = {r["url"].strip() for r in csv.DictReader(open(out_path, encoding="utf-8"), delimiter="	")}
    con = c.connect()
    known = {origin(r[0]) for r in con.execute("SELECT base_url FROM library")}
    rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8"), delimiter="	"))
    todo, seen, n_known = [], set(), 0
    for r in rows:
        o = origin(r["url"])
        if o in seen or r["url"].strip() in done_urls:
            continue
        seen.add(o)
        if o in known:
            append(out_path, dict(category=r["category"], name=r["name"], url=r["url"], status="KNOWN", platform="",
                                  base_url=o, titles="", model="", note="already in library", checked_at=c.now()))
            n_known += 1
        else:
            todo.append(r)
    by_host = defaultdict(list)
    for r in todo:
        by_host[c.host_key(r["url"])].append(r)
    c.log(f"{len(rows)} rows, {len(done_urls)} done before, {n_known} known, {len(todo)} to probe on {len(by_host)} hosts")
    done = [0]

    def run(rs):
        for r in rs:
            append(out_path, probe(r))
            done[0] += 1
            if done[0] % 100 == 0:
                c.log(f"probed {done[0]}/{len(todo)}")

    with ThreadPoolExecutor(max_workers=24) as ex:
        list(ex.map(run, by_host.values()))
    counts = defaultdict(int)
    for r in csv.DictReader(open(out_path, encoding="utf-8"), delimiter="	"):
        counts[(r["status"], r["platform"])] += 1
    for k in sorted(counts):
        print(*k, counts[k], sep="	")


if __name__ == "__main__":
    main()
