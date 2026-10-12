"""Collect each e-library site's own logo and map it to the library row (user request 2026-10-05).

Usage: python probe_logos.py [--data-dir DIR] [--only ID,ID] [--limit N]
Moved from the data folder into the dev repository in cycle 13 (spec R1-3). Output goes to the data folder
(--data-dir > CHAEKCHECK_DATA > chaekcheck-data next to the repository, same rule as scripts/crawl).
Writes logos/<library_id>.<ext> and appends logos/logos.tsv. A rerun skips libraries already in logos.tsv.
Politeness: one queue per IP (same as the crawler with queue-by-ip), one request at a time, GAP seconds apart.
Where the logo is (2026-10-05):
- KYOBO_SUBSCRIPTION_V1: {base}/main/headerLogo.json -> {base}/util/imageDisplay.ink?libraryCode=&target=toplogo&imagePath=
- KYOBO_V1: {base}/elibrary-front/main/headerLogo.json -> {base}/elibrary-front/util/imageDisplay.ink?...
- others: the header image on the first page (img whose src/alt/class or parent says logo, not in the footer)
"""
import csv
import hashlib
import json
import re
import sys
import threading
import time
import urllib.parse
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

# the collector is in scripts/crawl (cycle 13: moved from the data folder's scripts\)
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "crawl"))
import crawl_catalog as c  # noqa: E402
if "--data-dir" in sys.argv:  # taken out before main() reads its own arguments
    _i = sys.argv.index("--data-dir")
    c.set_data_dir(sys.argv[_i + 1])
    del sys.argv[_i:_i + 2]
from bs4 import BeautifulSoup  # noqa: E402

GAP = 2.0
OUT = c.ROOT / "logos"
TSV = OUT / "logos.tsv"
COLS = ["library_id", "name", "platform_type", "base_url", "logo_url", "file", "bytes", "sha256", "content_type",
        "note", "checked_at"]
EXT = {"image/png": "png", "image/jpeg": "jpg", "image/jpg": "jpg", "image/gif": "gif", "image/svg+xml": "svg",
       "image/webp": "webp", "image/x-icon": "ico", "image/vnd.microsoft.icon": "ico", "image/bmp": "bmp"}
_lock = threading.Lock()
_last = defaultdict(float)


def get(url, key, data=None, headers=None):
    with _lock:
        pass
    wait = _last[key] + GAP - time.monotonic()
    if wait > 0:
        time.sleep(wait)
    h = {"User-Agent": c.USER_AGENT}
    h.update(headers or {})
    req = urllib.request.Request(clean_url(url), data=data, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=40, context=c.SSL) as r:
            return r.read(), (r.headers.get("Content-Type") or "").split(";")[0].strip().lower(), r.geturl()
    finally:
        _last[key] = time.monotonic()


def clean_url(url):
    """Drop whitespace/control characters and percent-encode non-ASCII (Korean file names in logo paths)."""
    url = "".join(ch for ch in url.strip() if ord(ch) >= 32 and ord(ch) != 127)
    return urllib.parse.quote(url, safe=":/?#[]@!$&'()*+,;=%~")


def kyobo_logo(base, key, prefix):
    body, _, _ = get(f"{base}{prefix}/main/headerLogo.json", key, data=b"",
                     headers={"X-Requested-With": "XMLHttpRequest"})
    logo = (json.loads(body.decode("utf-8", "replace")) or {}).get("logo") or {}
    if not logo.get("upendLogo1Name"):
        return None, "no logo in headerLogo.json"
    q = urllib.parse.urlencode({"libraryCode": logo.get("lbryCode") or "", "target": "toplogo",
                                "imagePath": (logo.get("upendLogo1Path") or "") + logo["upendLogo1Name"]})
    return f"{base}{prefix}/util/imageDisplay.ink?{q}", ""


def page_logo(home, key):
    for _ in range(3):  # Aladin roots answer with a meta refresh to main/main.htm
        body, _, final = get(home, key)
        html = c.decode(body, "utf-8")
        if "�" in html[:5000]:
            html = c.decode(body, "euc-kr")
        doc = BeautifulSoup(html, "lxml")
        meta = doc.find("meta", attrs={"http-equiv": re.compile("refresh", re.I)})
        m = re.search(r"url\s*=\s*['\"]?([^'\";]+)", (meta.get("content") or "") if meta else "", re.I)
        if not m or doc.select("img"):
            break
        home = urllib.parse.urljoin(final, m.group(1).strip())
    best, score, foot = None, 0, None
    for img in doc.select("img"):
        src = img.get("src") or ""
        if not src or src.startswith("data:"):
            continue
        ctx = " ".join([src, img.get("alt") or "", " ".join(img.get("class") or []), img.get("id") or ""])
        parents = list(img.parents)[:4]
        pctx = " ".join(p.name + " " + " ".join(p.get("class") or []) + " " + (p.get("id") or "") for p in parents)
        if re.search(r"foot|bottom|btm|copyright", src + " " + pctx, re.I):
            if foot is None and re.search(r"logo", ctx + " " + pctx, re.I):
                foot = urllib.parse.urljoin(final, src)
            continue
        s = 0
        if re.search(r"logo", ctx, re.I):
            s += 3
        if re.search(r"logo", pctx, re.I):
            s += 2
        if re.search(r"\bh1\b|header|top", pctx + " " + src, re.I):
            s += 1
        if s > score:
            best, score = urllib.parse.urljoin(final, src), s
    # some portals draw the header logo with CSS, and some header images are broken (404); the footer logo is the same mark
    cands = ([(best, "")] if best and score >= 2 else []) + ([(foot, "footer logo (no header img)")] if foot else [])
    return cands or [(None, "no header logo found")]


def home_of(lib):
    p, base = lib["platform_type"], lib["base_url"].rstrip("/")
    if p == "ALADIN_V1":
        root, _ = c.aladin_parts(base)
        return root + "/"
    if p == "ELIB_PORTAL_V1":
        return c.portal_root(base) + "/index.do"
    if p == "GANGNAM_V1":
        return base + "/"
    return base + "/"


def probe(lib, key):
    row = dict(library_id=lib["id"], name=lib["name"], platform_type=lib["platform_type"], base_url=lib["base_url"],
               logo_url="", file="", bytes="", sha256="", content_type="", note="", checked_at=c.now())
    base = lib["base_url"].rstrip("/")
    try:
        if lib["platform_type"] == "KYOBO_SUBSCRIPTION_V1":
            cands = [kyobo_logo(base, key, "")]
        elif lib["platform_type"] == "KYOBO_V1":
            cands = [kyobo_logo(base, key, "/elibrary-front")]
        else:
            cands = page_logo(home_of(lib), key)
        data, ctype = b"", ""
        for i, (url, note) in enumerate(cands):
            row["note"] = note
            try:
                data, ctype, _ = get(url, key) if url else (b"", "", "")
                break
            except urllib.error.HTTPError:
                if i == len(cands) - 1:
                    raise
        if url:
            ext = EXT.get(ctype) or (re.search(r"\.(png|jpe?g|gif|svg|webp|ico|bmp)(?:$|\?)", url, re.I) or [None, None])[1]
            if not ext or not data or len(data) < 50:
                row.update(logo_url=url, note=f"not an image ({ctype}, {len(data)} bytes)")
            else:
                ext = ext.lower().replace("jpeg", "jpg")
                name = f"{lib['id']}.{ext}"
                (OUT / name).write_bytes(data)
                row.update(logo_url=url, file=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
                           content_type=ctype)
    except Exception as e:  # noqa: BLE001
        row["note"] = (type(e).__name__ + ": " + str(e))[:150]
    return row


def main():
    OUT.mkdir(exist_ok=True)
    args = sys.argv[1:]
    only = set()
    limit = None
    if "--only" in args:
        only = {int(x) for x in args[args.index("--only") + 1].split(",") if x}
    if "--limit" in args:
        limit = int(args[args.index("--limit") + 1])
    done = set()
    if TSV.exists():
        # a rerun retries libraries whose logo was not saved
        done = {int(r["library_id"]) for r in csv.DictReader(open(TSV, encoding="utf-8"), delimiter="\t") if r["file"]}
    con = c.connect()
    con.row_factory = c.sqlite3.Row
    libs = [dict(r) for r in con.execute("SELECT * FROM library WHERE coalesce(crawl_skip,'')='' ORDER BY id")]
    libs = [lb for lb in libs if lb["id"] not in done and (not only or lb["id"] in only)]
    if limit:
        libs = libs[:limit]
    c.QUEUE_BY_IP = True
    queues = defaultdict(list)
    for lb in libs:
        queues[c.queue_key(lb["base_url"])].append(lb)
    c.log(f"logos: {len(libs)} libraries on {len(queues)} queues")
    new = not TSV.exists()
    fh = open(TSV, "a", encoding="utf-8", newline="")
    w = csv.DictWriter(fh, fieldnames=COLS, delimiter="\t")
    if new:
        w.writeheader()

    def run(item):
        key, ls = item
        for lb in ls:
            row = probe(lb, key)
            with _lock:
                w.writerow(row)
                fh.flush()
            c.log(f"[{lb['id']}] {lb['name']}: {row['file'] or row['note']}")

    with ThreadPoolExecutor(max_workers=40) as ex:
        list(ex.map(run, queues.items()))
    fh.close()


if __name__ == "__main__":
    main()
