"""Finds the front page of each e-library row in the seed CSVs and checks that it opens (library.homepage_url).

Usage (from the repository root):
  python scripts/library/find_front_pages.py <out.tsv> [--write]

For an EBOOK row the front page is the e-library's own main page (docs/library-maintenance.md 1-1, user decision
2026-09-29: "도서관이 지금은 전자도서관이니까 전자도서관 홈페이지"). The possible front-page URLs of a platform are tried in
order; the first one that answers 2xx after redirects wins. The URL written is the one tried, not the redirect target.
--write also puts the found URLs into the homepage_url column of every library/*-library*.csv (only rows whose value
is NULL). The pilot rows in 002-library-seed.yaml are printed for a hand edit.
Politeness (2장): our User-Agent only, certificates always verified, one request at a time, 1.1 s between requests
to the same host.
"""
import csv
import glob
import io
import subprocess
import sys
import time
from urllib.parse import urlsplit

sys.stdout.reconfigure(encoding="utf-8")
LIBRARY_DIR = "backend/src/main/resources/db/changelog/library/"
PILOTS = [("1", "서울 강서구 전자도서관", "YES24_V2", "https://ebook.gangseo.seoul.kr"),
          ("2", "고흥군립도서관 전자도서관", "KYOBO_V1", "https://www.ghlib.go.kr:446")]
PATHS = {
    "KYOBO_V1": ["/elibrary-front/main.ink", "/"],
    "KYOBO_SUBSCRIPTION_V1": ["/main.ink", "/"],
    "YES24_V1": ["/"],
    "YES24_V2": ["/"],
    "YES24_V3": ["/"],
    "GANGNAM_V1": ["/"],
    "ELIB_PORTAL_V1": ["/", "/index.do"],
}
last_request = {}


def status(url):
    host = urlsplit(url).netloc
    wait = last_request.get(host, 0) + 1.1 - time.monotonic()
    if wait > 0:
        time.sleep(wait)
    try:
        done = subprocess.run(["curl", "-s", "-o", "/dev/null", "-L", "-m", "15", "-A", "chaekcheck/0.0.1",
                               "-w", "%{http_code}", url], capture_output=True, timeout=25)
        code = done.stdout.decode().strip() or "000"
    except subprocess.TimeoutExpired:
        code = "timeout"
    last_request[host] = time.monotonic()
    return code


def rows():
    found = list(PILOTS)
    for path in sorted(glob.glob(LIBRARY_DIR + "*-library*.csv")):
        found += [(r["id"], r["name"], r["platform_type"], r["base_url"])
                  for r in csv.DictReader(open(path, encoding="utf-8"))]
    return found


def main(out_path, write):
    result = {}
    with open(out_path, "w", encoding="utf-8") as out:
        out.write("id\tname\tfront_page\tchecked\n")
        for library_id, name, platform, base in rows():
            tried = []
            front = "NULL"
            for path in PATHS[platform]:
                url = base.rstrip("/") + path
                code = status(url)
                tried.append(f"{path}={code}")
                if code.startswith("2"):
                    front = url
                    break
            result[library_id] = front
            out.write("\t".join([library_id, name, front, " ".join(tried)]) + "\n")
            out.flush()
            print(library_id, name, front, " ".join(tried), flush=True)
    if write:
        for path in sorted(glob.glob(LIBRARY_DIR + "*-library*.csv")):
            table = list(csv.reader(open(path, encoding="utf-8", newline="")))
            column = table[0].index("homepage_url")
            for row in table[1:]:
                if row[column] == "NULL" and result.get(row[0], "NULL") != "NULL":
                    row[column] = result[row[0]]
            text = io.StringIO()
            csv.writer(text, lineterminator="\n").writerows(table)
            open(path, "w", encoding="utf-8", newline="").write(text.getvalue())
        for library_id, name, _, _ in PILOTS:
            print("pilot, edit 002-library-seed.yaml by hand:", library_id, name, result[library_id])


if __name__ == "__main__":
    main(sys.argv[1], "--write" in sys.argv[2:])
