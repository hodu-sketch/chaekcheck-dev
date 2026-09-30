"""Collects join-eligibility sentences from each library's homepage (docs/library-maintenance.md 4-8, 4-10).

Usage (from the repository root):
  python scripts/library/collect_join_evidence.py <targets.tsv> <out.jsonl>
    targets.tsv  columns: id, name, start_urls (space separated: the known source page, the homepage, ...)

For each library it fetches the start pages, follows same-site links whose text is about membership, usage or
e-books (at most MAX_PAGES pages), and keeps only the sentences that talk about who may join. A person reads the
output and decides; this script decides nothing.
Politeness (2장): our User-Agent only, one request per host at a time, HOST_INTERVAL seconds apart. Libraries are
grouped by host and the groups run on a few threads, so no host ever gets two requests at once.
"""
import csv
import html
import json
import re
import subprocess
import sys
import threading
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urljoin, urlsplit

sys.stdout.reconfigure(encoding="utf-8")
USER_AGENT = "chaekcheck/0.0.1"
HOST_INTERVAL = 1.1
MAX_PAGES = 9
WORKERS = 6
LINK_TEXT = re.compile(r"회원\s*가입|가입\s*안내|회원\s*안내|회원증|대출\s*회원|정회원|이용\s*자격|이용\s*안내|"
                       r"도서관\s*이용|대출\s*(및|/|·)?\s*반납|대출\s*안내|전자\s*책|전자\s*도서관|e-?book", re.I)
LINK_PRIORITY = re.compile(r"회원|가입|자격|회원증|정회원")
EVIDENCE = re.compile(r"가입\s*대상|발급\s*대상|이용\s*대상|회원\s*자격|가입\s*자격|이용\s*자격|자격|주민등록|거주|"
                      r"누구나|전\s*국민|타\s*지역|재직|재학|정회원|준회원|웹\s*회원|대출\s*회원|회원증")


def fetch(url):
    """(final url, text) or (None, error). Certificates are always verified."""
    try:
        done = subprocess.run(["curl", "-s", "-L", "-m", "15", "--max-filesize", "3000000", "-A", USER_AGENT,
                               "-w", "\n@@FINAL@@%{url_effective} %{http_code}", url],
                              capture_output=True, timeout=25)
    except subprocess.TimeoutExpired:
        return None, "timeout"
    raw = done.stdout
    marker = raw.rfind(b"\n@@FINAL@@")
    if marker < 0:
        return None, "curl exit %d" % done.returncode
    final, code = raw[marker + 10:].decode().rsplit(" ", 1)
    body = raw[:marker]
    if not code.startswith("2"):
        return None, "http " + code
    head = body[:3000].decode("ascii", errors="ignore").lower()
    charset = "cp949" if re.search(r"charset=[\"']?(euc-kr|ks_c_5601|cp949)", head) else "utf-8"
    return final, body.decode(charset, errors="replace")


def sentences(page_html):
    text = re.sub(r"<(script|style)[^>]*>.*?</\1>", " ", page_html, flags=re.S | re.I)
    text = re.sub(r"<br\s*/?>|</(p|li|tr|div|h\d|dd|dt|td|th)>", "\n", text, flags=re.I)
    text = html.unescape(re.sub(r"<[^>]+>", " ", text)).replace("\xa0", " ")
    lines = [re.sub(r"\s+", " ", line).strip() for line in text.split("\n")]
    return [line[:300] for line in lines if 4 <= len(line) and EVIDENCE.search(line)]


def links(base, page_html):
    found = []
    for href, label in re.findall(r"<a[^>]+href=[\"']([^\"'#][^\"']*)[\"'][^>]*>(.*?)</a>", page_html, re.S | re.I):
        label = re.sub(r"<[^>]+>|\s+", " ", html.unescape(label)).strip()
        if label and LINK_TEXT.search(label) and not href.lower().startswith(("javascript", "mailto")):
            found.append((label, urljoin(base, html.unescape(href))))
    return found


def site(url):
    host = urlsplit(url).hostname or ""
    parts = host.split(".")
    return ".".join(parts[-3:]) if len(parts) > 2 else host


class Hosts:
    """One request per host at a time, HOST_INTERVAL apart."""

    def __init__(self):
        self.locks = defaultdict(threading.Lock)
        self.last = defaultdict(float)
        self.guard = threading.Lock()

    def get(self, url):
        host = urlsplit(url).netloc
        with self.guard:
            lock = self.locks[host]
        with lock:
            wait = self.last[host] + HOST_INTERVAL - time.monotonic()
            if wait > 0:
                time.sleep(wait)
            try:
                return fetch(url)
            finally:
                self.last[host] = time.monotonic()


def collect(hosts, library):
    lid, name, start_urls = library
    queue = [("start", u) for u in start_urls]
    seen, pages, errors = set(), [], []
    home_sites = {site(u) for u in start_urls}
    while queue and len(pages) + len(errors) < MAX_PAGES:
        label, url = queue.pop(0)
        if url in seen:
            continue
        seen.add(url)
        final, body = hosts.get(url)
        if final is None:
            errors.append({"url": url, "error": body})
            continue
        pages.append({"url": final, "via": label, "evidence": sentences(body)})
        found = [(l, u) for l, u in links(final, body) if site(u) in home_sites and u not in seen]
        found.sort(key=lambda lu: 0 if LINK_PRIORITY.search(lu[0]) else 1)
        queue += found
        queue.sort(key=lambda lu: 0 if lu[0] == "start" else (1 if LINK_PRIORITY.search(lu[0]) else 2))
    return {"id": lid, "name": name, "pages": pages, "errors": errors}


def main(targets_path, out_path):
    libraries = []
    for row in csv.DictReader(open(targets_path, encoding="utf-8"), delimiter="\t"):
        urls = [u for u in row["start_urls"].split() if u]
        if urls:
            libraries.append((row["id"], row["name"], urls))
    hosts, out_lock = Hosts(), threading.Lock()
    with open(out_path, "w", encoding="utf-8") as out:
        def run(library):
            result = collect(hosts, library)
            with out_lock:
                out.write(json.dumps(result, ensure_ascii=False) + "\n")
                out.flush()
                print(result["id"], result["name"], len(result["pages"]), "pages", len(result["errors"]), "errors",
                      flush=True)
        with ThreadPoolExecutor(WORKERS) as pool:
            list(pool.map(run, libraries))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
