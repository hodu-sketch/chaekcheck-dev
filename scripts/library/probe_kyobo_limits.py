"""Check Kyobo subscription/rental e-library sites for limit notices (popups and the first notice board).

Usage: python probe_kyobo_limits.py <out.tsv>            one-off survey (a rerun skips libraries already in out.tsv)
       python probe_kyobo_limits.py --log <log.tsv> <img_dir>
                                            daily: append only popups/notices not seen before (first_seen = today)
                                            and save new popup images to img_dir for a person to read later
Reads library rows of KYOBO_SUBSCRIPTION_V1 whose library_model is rental/mixed (service subscription libraries
and okbari-2026-10-03-rental). One request at a time, 3 s apart; a rerun skips libraries already in out.tsv.
Popup bodies are often images: only the title and registration time are read. (2026-10-04)
"""
import csv
import json
import re
import sqlite3
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

# crawl_catalog.py lives in scripts/crawl/ (ADR-0047 C)
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "crawl"))
import crawl_catalog as c  # noqa: E402

WORDS = re.compile(r"마감|소진|종료|중단|재개|오픈|한도|권수|대출\s*권|구독|대여|예산|초과|1일")
COLS = ["library_id", "name", "source", "kind", "posted", "title", "image", "checked_at"]


def post(base, path, data):
    req = urllib.request.Request(base + path, data=urllib.parse.urlencode(data).encode(), headers={
        "User-Agent": c.USER_AGENT, "X-Requested-With": "XMLHttpRequest",
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"})
    try:
        with urllib.request.urlopen(req, timeout=40, context=c.SSL) as r:
            return json.loads(r.read().decode("utf-8", "replace"))
    finally:
        time.sleep(3)


def libraries():
    con = sqlite3.connect(f"file:{c.DB}?mode=ro", uri=True)
    return con.execute("""SELECT id, name, base_url, coalesce(source, 'service') FROM library
        WHERE platform_type='KYOBO_SUBSCRIPTION_V1' AND (source='okbari-2026-10-03-rental'
        OR (id < 100000 AND coalesce(seed_holdings_count, 0) >= 50000)) ORDER BY id""").fetchall()


def fetch_rows(base):
    rows = []
    for p in post(base, "/popup/popupMain.json", {"mmbrLgnCode": "N"}).get("list", []):
        img = re.search(r'src="([^"]+)"', p.get("popupCntt") or "")
        rows.append(("popup", p.get("rgstDttm") or "", p.get("titeName") or "", img.group(1) if img else ""))
    first = post(base, "/main/firstBoardBlbrSrmb.json", {})
    srmb = str((first.get("board") or {}).get("blbrSrmb") or "1") if isinstance(first, dict) else "1"
    board = post(base, "/board/listBoard.json", {"blbrSrmb": srmb, "pageIndex": "1", "recordCount": "10",
                                                 "srchKey": "", "srchWord": ""})
    for b in board.get("list", []):
        title = next((str(v) for k, v in b.items() if re.search(r"tit", k, re.I) and v), "")
        if WORDS.search(title):
            posted = next((str(v) for k, v in b.items() if re.search(r"rgst|reg", k, re.I) and v), "")
            rows.append(("notice", posted, title, ""))
    return rows


LOG_COLS = ["first_seen", "library_id", "name", "source", "kind", "posted", "title", "image", "image_file"]


def main_log(log_path, img_dir):
    """Daily run: keep every popup/notice title once, with the day it was first seen. Images are saved, not read."""
    Path(img_dir).mkdir(parents=True, exist_ok=True)
    seen = set()
    if Path(log_path).exists():
        seen = {(r["library_id"], r["kind"], r["posted"], r["title"])
                for r in csv.DictReader(open(log_path, encoding="utf-8"), delimiter="	")}
    new_file = not Path(log_path).exists()
    added = errors = 0
    with open(log_path, "a", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=LOG_COLS, delimiter="	")
        if new_file:
            w.writeheader()
        for lid, name, base, src in libraries():
            try:
                rows = fetch_rows(base.rstrip("/"))
            except Exception as e:  # noqa: BLE001
                errors += 1
                c.log(f"[{lid}] {name}: ERROR {type(e).__name__}: {str(e)[:100]}")
                continue
            for kind, posted, title, image in rows:
                key = (str(lid), kind, posted, title)
                if key in seen:
                    continue
                seen.add(key)
                image_file = ""
                if image:
                    try:
                        req = urllib.request.Request(image, headers={"User-Agent": c.USER_AGENT})
                        with urllib.request.urlopen(req, timeout=40, context=c.SSL) as r:
                            data = r.read()
                        image_file = f"{lid}_{re.sub(r'[^A-Za-z0-9._-]', '_', image.rsplit('/', 1)[-1])}"
                        (Path(img_dir) / image_file).write_bytes(data)
                    except Exception as e:  # noqa: BLE001
                        image_file = f"(download failed: {type(e).__name__})"
                    time.sleep(3)
                w.writerow(dict(first_seen=c.now(), library_id=lid, name=name, source=src, kind=kind, posted=posted,
                                title=title, image=image, image_file=image_file))
                fh.flush()
                added += 1
                c.log(f"[{lid}] {name}: NEW {kind} {posted[:10]} {title[:60]}")
    c.log(f"daily limits check: {added} new rows, {errors} libraries failed")


def main():
    # cycle 13 (plan spec R1-6 [확정], 2026-10-12): no request goes to the KYOBO subscription servers
    # (*.dkyobobook.co.kr); they blocked our IP and rental/mixed sites are left out (ADR-0067). Every site this tool
    # checks is on those servers, so it stops here. Lifting it needs a new user decision.
    sys.exit("probe_kyobo_limits.py does not run since 2026-10-12: *.dkyobobook.co.kr is never requested (plan cycle 13 R1-6)")
    if sys.argv[1] == "--log":
        return main_log(sys.argv[2], sys.argv[3])
    out = sys.argv[1]
    done = set()
    if Path(out).exists():
        done = {r["library_id"] for r in csv.DictReader(open(out, encoding="utf-8"), delimiter="\t")}
    libs = libraries()
    new = not Path(out).exists()
    with open(out, "a", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=COLS, delimiter="\t")
        if new:
            w.writeheader()
        for lid, name, base, src in libs:
            if str(lid) in done:
                continue
            base = base.rstrip("/")
            rows = []
            try:
                for p in post(base, "/popup/popupMain.json", {"mmbrLgnCode": "N"}).get("list", []):
                    img = re.search(r'src="([^"]+)"', p.get("popupCntt") or "")
                    rows.append(("popup", p.get("rgstDttm") or "", p.get("titeName") or "", img.group(1) if img else ""))
                first = post(base, "/main/firstBoardBlbrSrmb.json", {})
                srmb = str((first.get("board") or {}).get("blbrSrmb") or "1") if isinstance(first, dict) else "1"
                board = post(base, "/board/listBoard.json", {"blbrSrmb": srmb, "pageIndex": "1", "recordCount": "10",
                                                             "srchKey": "", "srchWord": ""})
                for b in board.get("list", []):
                    title = next((str(v) for k, v in b.items() if re.search(r"tit", k, re.I) and v), "")
                    if WORDS.search(title):
                        posted = next((str(v) for k, v in b.items() if re.search(r"rgst|reg", k, re.I) and v), "")
                        rows.append(("notice", posted, title, ""))
            except Exception as e:  # noqa: BLE001
                rows.append(("error", "", (type(e).__name__ + ": " + str(e))[:150], ""))
            if not rows:
                rows.append(("none", "", "", ""))
            for kind, posted, title, image in rows:
                w.writerow(dict(library_id=lid, name=name, source=src, kind=kind, posted=posted, title=title,
                                image=image, checked_at=c.now()))
            fh.flush()
            c.log(f"[{lid}] {name}: {', '.join(r[2] for r in rows if r[2])[:80]}")


if __name__ == "__main__":
    main()
