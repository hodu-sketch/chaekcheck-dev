"""Logo from the library's main homepage when the e-library site has none of its own (user request 2026-10-05:
"공공도서관만이라도 먼저 받아봐"). Finds a link to the main homepage on the e-library page, then takes its header logo.

Usage: python probe_home_logos.py [--data-dir DIR] <ids comma separated> [--home ID=URL,...]
Moved from the data folder into the dev repository in cycle 13 (spec R1-3); data folder rule as probe_logos.py.
Writes logos/home/<library_id>.<ext> and logos/logos-home.tsv. Same politeness as probe_logos.py.
"""
import csv
import hashlib
import re
import sys
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import probe_logos as P  # noqa: E402  (first: it reads --data-dir and puts scripts/crawl on the path)
import crawl_catalog as c  # noqa: E402
from bs4 import BeautifulSoup  # noqa: E402

OUT = P.OUT / "home"
TSV = P.OUT / "logos-home.tsv"
COLS = ["library_id", "name", "elib_url", "home_url", "how", "logo_url", "file", "bytes", "sha256", "content_type",
        "note", "checked_at"]
HOME_TEXT = re.compile(r"홈페이지|도서관\s*홈|home|메인|family|패밀리|바로\s*가기", re.I)
ELIB_WORD = re.compile(r"elib|ebook|e-?book|dkyobobook|yes24library|bookcube|전자", re.I)


def elib_pages(lib):
    base = lib["base_url"].rstrip("/")
    p = lib["platform_type"]
    if p == "KYOBO_V1":
        return [base + "/elibrary-front/main.ink", base + "/elibrary-front/member/memberLogin.ink"]
    if p == "KYOBO_SUBSCRIPTION_V1":
        return [base + "/main.ink"]
    return [P.home_of(lib)]


def find_home(lib, key):
    elib_host = urllib.parse.urlsplit(lib["base_url"]).hostname or ""
    best = None
    for page in elib_pages(lib):
        try:
            body, _, final = P.get(page, key)
        except Exception:  # noqa: BLE001
            continue
        doc = BeautifulSoup(c.decode(body, "utf-8"), "lxml")
        for a in doc.select("a[href], option[value]"):
            href = a.get("href") or a.get("value") or ""
            if not href.startswith("http"):
                continue
            host = urllib.parse.urlsplit(href).hostname or ""
            if not host or host == elib_host or ELIB_WORD.search(host):
                continue
            if not re.search(r"\.(go|or|re|ac)\.kr$|lib", host):
                continue
            text = a.get_text(" ", strip=True) + " " + (a.get("title") or "")
            score = (2 if HOME_TEXT.search(text) else 0) + (1 if "lib" in host else 0)
            if best is None or score > best[0]:
                best = (score, urllib.parse.urlunsplit(urllib.parse.urlsplit(href)._replace(path="/", query="", fragment="")), text[:40])
        if best and best[0] >= 2:
            break
    return (best[1], "link: " + best[2]) if best else (None, "no homepage link")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    ids = [int(x) for x in sys.argv[1].split(",") if x]
    given = {}
    if "--home" in sys.argv:
        for kv in sys.argv[sys.argv.index("--home") + 1].split(","):
            k, v = kv.split("=", 1)
            given[int(k)] = v
    con = c.connect()
    con.row_factory = c.sqlite3.Row
    new = not TSV.exists()
    fh = open(TSV, "a", encoding="utf-8", newline="")
    w = csv.DictWriter(fh, fieldnames=COLS, delimiter="\t")
    if new:
        w.writeheader()
    for lid in ids:
        lib = dict(con.execute("SELECT * FROM library WHERE id=?", (lid,)).fetchone())
        key = c.queue_key(lib["base_url"])
        row = dict(library_id=lid, name=lib["name"], elib_url=lib["base_url"], home_url="", how="", logo_url="",
                   file="", bytes="", sha256="", content_type="", note="", checked_at=c.now())
        try:
            home, how = (given[lid], "given") if lid in given else find_home(lib, key)
            row.update(home_url=home or "", how=how)
            if home:
                hkey = urllib.parse.urlsplit(home).hostname
                for url, note in P.page_logo(home, hkey):
                    row["note"] = note
                    if not url:
                        break
                    try:
                        data, ctype, _ = P.get(url, hkey)
                    except Exception as e:  # noqa: BLE001
                        row["note"] = type(e).__name__ + ": " + str(e)[:80]
                        continue
                    ext = P.EXT.get(ctype) or (re.search(r"\.(png|jpe?g|gif|svg|webp)(?:$|\?)", url, re.I) or [None, None])[1]
                    if ext and len(data) >= 50:
                        name = f"{lid}.{ext.lower().replace('jpeg', 'jpg')}"
                        (OUT / name).write_bytes(data)
                        row.update(logo_url=url, file=name, bytes=len(data),
                                   sha256=hashlib.sha256(data).hexdigest(), content_type=ctype)
                        break
        except Exception as e:  # noqa: BLE001
            row["note"] = (type(e).__name__ + ": " + str(e))[:150]
        w.writerow(row)
        fh.flush()
        print(lid, lib["name"], "|", row["home_url"], "|", row["file"] or row["note"], flush=True)
    fh.close()


if __name__ == "__main__":
    main()
