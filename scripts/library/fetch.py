"""Fetch one library page politely for join-condition research (user request 2026-10-05).

Usage: python fetch.py [--data-dir DIR] <url> [--links] [--full]
Moved from the data folder (library-join\fetch.py) into the dev repository in cycle 13 (spec R1-3). The host lock
files stay in the data folder's library-join\.hostlocks (--data-dir > CHAEKCHECK_DATA > chaekcheck-data next to
the repository), so this copy and the old one still keep the gap between them.
Prints the final URL, HTTP status and the page text (scripts/styles removed). --links also prints the page's links; --full prints the whole page text (default: first 20,000 characters).
Same rules as dev docs/library-maintenance.md 2장: our User-Agent only, certificates verified, and a per-host
lock file so two runs never hit the same host less than 1.1 s apart.
"""
import html
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urljoin, urlsplit, quote

sys.stdout.reconfigure(encoding="utf-8")
UA = "chaekcheck/0.0.1"
GAP = 1.1
if "--data-dir" in sys.argv:  # taken out before main() reads its own arguments
    _i = sys.argv.index("--data-dir")
    DATA = sys.argv[_i + 1]
    del sys.argv[_i:_i + 2]
else:
    DATA = os.environ.get("CHAEKCHECK_DATA") or os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "chaekcheck-data")
LOCKDIR = os.path.join(DATA, "library-join", ".hostlocks")


def wait_host(host):
    os.makedirs(LOCKDIR, exist_ok=True)
    path = os.path.join(LOCKDIR, re.sub(r"[^\w.-]", "_", host))
    while True:
        try:
            fd = os.open(path + ".lock", os.O_CREAT | os.O_EXCL)
            os.close(fd)
            break
        except FileExistsError:
            if time.time() - os.path.getmtime(path + ".lock") > 60:
                os.remove(path + ".lock")
            time.sleep(0.3)
    try:
        last = os.path.getmtime(path) if os.path.exists(path) else 0
        if time.time() - last < GAP:
            time.sleep(GAP - (time.time() - last))
    finally:
        return path


def curl(url):
    done = subprocess.run([r"C:\Windows\System32\curl.exe", "-s", "-L", "-m", "25", "--max-filesize", "3000000",
                           "-A", UA, "-w", "\n@@FINAL@@%{url_effective} %{http_code}", url], capture_output=True, timeout=40)
    raw = done.stdout
    i = raw.rfind(b"\n@@FINAL@@")
    if i < 0:
        raise OSError("curl exit %d" % done.returncode)
    final, code = raw[i + 10:].decode().rsplit(" ", 1)
    if not code.startswith("2"):
        raise OSError("http " + code)
    return raw[:i], final, int(code)


def main():
    url = quote(sys.argv[1].strip(), safe=":/?#[]@!$&'()*+,;=%~")
    host = urlsplit(url).netloc
    stamp = wait_host(host)
    try:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=25) as r:
                body, final, status = r.read(3_000_000), r.geturl(), r.status
        except urllib.error.URLError as e:
            if "SSL" not in str(e) and "handshake" not in str(e).lower():
                raise
            # some servers (e.g. *.jne.go.kr) fail Python's TLS handshake but work with Windows curl (Schannel);
            # certificates are still verified
            body, final, status = curl(url)
    except Exception as e:  # noqa: BLE001
        print("ERROR", type(e).__name__, str(e)[:200])
        return
    finally:
        open(stamp, "w").close()
        try:
            os.remove(stamp + ".lock")
        except OSError:
            pass
    head = body[:3000].decode("ascii", "ignore").lower()
    cs = "cp949" if re.search(r"charset=[\"']?(euc-kr|ks_c_5601|cp949)", head) else "utf-8"
    page = body.decode(cs, "replace")
    print("FINAL", final, status)
    text = re.sub(r"<(script|style)[^>]*>.*?</\1>", " ", page, flags=re.S | re.I)
    text = re.sub(r"<br\s*/?>|</(p|li|tr|div|h\d|dd|dt|td|th)>", "\n", text, flags=re.I)
    text = html.unescape(re.sub(r"<[^>]+>", " ", text)).replace("\xa0", " ")
    lines = [re.sub(r"\s+", " ", x).strip() for x in text.split("\n")]
    print("\n".join(x for x in lines if x)[:None if "--full" in sys.argv else 20000])
    if "--links" in sys.argv:
        print("\n--- LINKS ---")
        for href, label in re.findall(r"<a[^>]+href=[\"']([^\"'#][^\"']*)[\"'][^>]*>(.*?)</a>", page, re.S | re.I):
            label = re.sub(r"<[^>]+>|\s+", " ", html.unescape(label)).strip()
            if not href.lower().startswith(("javascript", "mailto")):
                print(label[:40], "|", urljoin(final, html.unescape(href)))


if __name__ == "__main__":
    main()
