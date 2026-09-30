"""Collects the "로그인 상세 이용안내" panel (div.toogle_con) and footer of every Kyobo site in the seed CSVs.

Usage (from the repository root): python scripts/library/kyobo_login_notice.py <out.tsv>
One request per site, 1.1 s apart, our User-Agent only (docs/library-maintenance.md 2장, 4-10절).
The pilot library (고흥, 002 seed YAML) is not in the CSVs and is added by hand.
"""
import csv
import glob
import html
import re
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8")
SEED_DIR = "backend/src/main/resources/db/changelog/library/"
PILOTS = [("2", "고흥군립도서관 전자도서관", "https://www.ghlib.go.kr:446")]


def kyobo_sites():
    sites = list(PILOTS)
    for path in sorted(glob.glob(SEED_DIR + "*-library*.csv")):
        with open(path, encoding="utf-8") as f:
            sites += [(r["id"], r["name"], r["base_url"]) for r in csv.DictReader(f)
                      if r["platform_type"].startswith("KYOBO")]
    return sites


def text_of(fragment):
    fragment = re.sub(r"<br\s*/?>", "\n", fragment)
    text = html.unescape(re.sub(r"<[^>]+>", "", fragment)).replace("\xa0", " ")
    return "\n".join(line.strip() for line in text.splitlines() if line.strip())


def main(out_path):
    sites = kyobo_sites()
    with open(out_path, "w", encoding="utf-8") as out:
        out.write("id\tname\tstatus\tfooter\tnotice\n")
        for i, (library_id, name, base) in enumerate(sites):
            url = base.rstrip("/") + "/elibrary-front/member/memberLogin.ink"
            try:
                body = subprocess.run(["curl", "-s", "-L", "-m", "15", "-A", "chaekcheck/0.0.1", url],
                                      capture_output=True, timeout=20).stdout.decode("utf-8", errors="replace")
                panel = re.search(r'class="toogle_con[^"]*"[^>]*>(.*?)</div>', body, re.S)
                notice = text_of(panel.group(1)) if panel else ""
                footer = re.search(r"도서관명\s*:\s*(.*?)\s*\|.*?대표전화\s*:\s*([0-9-]+)", text_of(body), re.S)
                footer_text = f"{footer.group(1)} / {footer.group(2)}" if footer else ""
                status = "OK" if panel else ("NO_PANEL" if body else "NO_RESPONSE")
            except subprocess.TimeoutExpired:
                status, notice, footer_text = "TIMEOUT", "", ""
            out.write("\t".join([library_id, name, status, footer_text, notice.replace("\n", " ⏎ ")]) + "\n")
            out.flush()
            print(i + 1, len(sites), library_id, name, status, flush=True)
            time.sleep(1.1)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "kyobo-login-notice.tsv")
