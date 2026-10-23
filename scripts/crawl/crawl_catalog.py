"""Collect the e-book catalogue (what each e-library holds) into SQLite.

Usage:
  python crawl_catalog.py init                 # (re)load the library list from chaekcheck-dev seed CSVs (read-only)
  python crawl_catalog.py full [--workers N] [--only ID,ID] [--limit-pages N]
  python crawl_catalog.py status
  python crawl_catalog.py add-library --id N --name NAME --platform TYPE --base-url URL --source LABEL
  python crawl_catalog.py refill-pubdt --only ID   # read a library's list again in publish-date order (adds only)

Rules (docs/research/ebook-catalog/crawl-method.md in chaekcheck-plan):
- one request at a time per host, at least 1 s apart; identifying User-Agent
- facts only: no blurbs, no loan/hold counts; cover image URL only
- every row keeps first_seen_at / last_seen_at / removed_at; every run is recorded in crawl_run
- rows are marked removed only after a successful full run
- no request goes to the KYOBO subscription servers (*.dkyobobook.co.kr): they blocked our IP (cycle 13 spec R1-6)
- a library whose site blocks us (HTTP 403, or time-outs after every retry) is marked not crawled (crawl_skip, R1-7)
"""
import argparse
import csv
import html
import http.client
import os
import re
import sqlite3
import socket
import ssl
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

from bs4 import BeautifulSoup

# ---------------------------------------------------------------- 규칙 (rules)
# Values people decided (ADR-0117). Each has a row in docs/maintenance/rules-index.md: what it means, how to check
# it against the data, and what to do after changing it (a change takes effect from the next crawl).

# Library ids from the dev seed that init leaves out.
DISABLED_IDS = {59, 131}  # rules-index.md: DISABLED_IDS
# Rows per list page, by platform.  rules-index.md: PAGE_SIZE
PAGE_SIZE = {"KYOBO_V1": 500, "YES24_V1": 50, "YES24_V2": 10, "YES24_V3": 10, "KYOBO_SUBSCRIPTION_V1": 80,
             "ELIB_PORTAL_V1": 100, "GANGNAM_V1": 20, "BOOKCUBE_FX": 100, "ALADIN_V1": 100, "SEOUL_ELIB_V1": 100}
# rules-index.md: PAGE_SIZE_BY_HOST
PAGE_SIZE_BY_HOST = {  # hosts that cut or time out on the default page size (2026-09-30)
    "ebook.jbe.go.kr": 100, "ebook.chilgok.go.kr": 100, "lib.goe.go.kr": 50}
# Subscription sites whose catalogue is the ~200k shared KYOBO subscription pool (all on *.dkyobobook.co.kr):
# skipped unless --include-big-subscription (about 2,500 pages each on one shared server).
BIG_SUBSCRIPTION_MIN = 50000  # rules-index.md: BIG_SUBSCRIPTION_MIN
# Never requested (cycle 13 spec R1-6): the KYOBO subscription servers blocked our IP, and rental/mixed sites are
# left out of the service (ADR-0067), so there is nothing to fetch there. Checked again right before every request.
BLOCKED_HOST_SUFFIX = "dkyobobook.co.kr"  # rules-index.md: BLOCKED_HOST_SUFFIX
# Hosting platforms that serve many libraries from one server: one politeness queue each (host_key, --queue-by-ip).
# rules-index.md: SHARED_HOSTS
SHARED_HOSTS = ("dkyobobook.co.kr", "bookcube.biz", "dasangng.co.kr", "aladinebook.co.kr", "yes24library.com",
                "libworks.co.kr", "hongjicns.co.kr")
# Parallelism, chosen per run (--workers, --per-host, --gap). Defaults are the agreed politeness rule:
# one request at a time per server, at least 1 s apart (cycle 01 spec 5.6); different servers run in parallel.
MIN_GAP = 1.0          # seconds between requests to one host; rules-index.md: MIN_GAP
# Hosts asked for a longer gap than MIN_GAP: 서울도서관 1.1 s (library-maintenance 2절 and 4-14, 2026-10-20).
# rules-index.md: MIN_GAP_BY_HOST
MIN_GAP_BY_HOST = {"elib.seoul.go.kr": 1.1}
PER_HOST = 1           # requests in flight at once on one host; rules-index.md: PER_HOST
MATCH_TOLERANCE = 0.01  # distinct items vs site total; rules-index.md: MATCH_TOLERANCE
# Integrated portals (ELIB_PORTAL_V1) whose list rows show the publisher before the author in div.info
# (cycle 19 spec R3-1, ADR-0115); every other portal shows the author first. Seen on the sites 2026-10-16.
# rules-index.md: PUBLISHER_FIRST_PORTAL_HOSTS
PUBLISHER_FIRST_PORTAL_HOSTS = {"lib.goe.go.kr", "library.daegu.go.kr", "lib.jbe.go.kr"}

# ---------------------------------------------------------------- settings

# Moved into the dev repository as scripts/crawl/ (ADR-0047 B, 2026-10-05). The collected data stays outside git:
# ROOT is the data folder (ebook-catalog.sqlite, logs/, other-platforms/, secrets/), chosen by --data-dir or the
# CHAEKCHECK_DATA environment variable; by default the chaekcheck-data folder next to this repository.
REPO = Path(__file__).resolve().parents[2]
ROOT = Path(os.environ.get("CHAEKCHECK_DATA") or REPO.parent / "chaekcheck-data")
DB = ROOT / "ebook-catalog.sqlite"
DEV_SEED = REPO / "backend" / "src" / "main" / "resources" / "db" / "changelog" / "library"
SEED_CSVS = ["003-library-cycle02.csv", "004-library.csv", "005-library-yes24.csv", "006-library-moved.csv"]
# 002-library-seed.yaml is SQL, not CSV: the two pilot libraries.
PILOTS = [(1, "서울 강서구 전자도서관", "YES24_V2", "https://ebook.gangseo.seoul.kr", None),
          (2, "고흥군립도서관 전자도서관", "KYOBO_V1", "https://www.ghlib.go.kr:446", None)]
PLATFORMS = {"KYOBO_V1", "YES24_V1", "YES24_V2", "YES24_V3", "KYOBO_SUBSCRIPTION_V1", "ELIB_PORTAL_V1", "GANGNAM_V1",
             "BOOKCUBE_FX", "ALADIN_V1", "SEOUL_ELIB_V1"}
# library.vendor values (same CHECK as the data folder's library-naming/apply_rename.py, 2026-10-12)
VENDORS = ("KYOBO", "YES24", "BOOKCUBE", "ALADIN", "PORTAL", "IN_HOUSE")

# The KYOBO subscription sites serve a mobile page without the total unless the agent looks like a desktop browser.
# Identifies the project only; a contact address is added when the service has a public one.
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) chaekcheck-crawler/0.1 (catalog survey)"
TIMEOUT = 90
RETRIES = 3
RETRY_WAIT = 30

SCHEMA = """
CREATE TABLE IF NOT EXISTS library (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  platform_type TEXT NOT NULL,
  base_url TEXT NOT NULL,
  host TEXT NOT NULL,
  seed_holdings_count INTEGER,
  last_full_success_at TEXT,
  last_full_run_id INTEGER,
  source TEXT,                      -- dev-seed / okbari-2026-09-30 (third-party list, checked by probe)
  crawl_skip TEXT,                  -- reason this library is not crawled (NULL = crawl it)
  -- set only when the vendor differs from the platform_type mapping; read by the library-insert command
  vendor TEXT CHECK (vendor IS NULL OR vendor IN ('KYOBO','YES24','BOOKCUBE','ALADIN','PORTAL','IN_HOUSE'))
);
CREATE TABLE IF NOT EXISTS crawl_run (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  library_id INTEGER NOT NULL REFERENCES library(id),
  kind TEXT NOT NULL CHECK (kind IN ('FULL','NEW')),
  status TEXT NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','MISMATCH')),
  started_at TEXT NOT NULL,
  finished_at TEXT,
  page_size INTEGER NOT NULL,
  site_total INTEGER,
  last_page INTEGER NOT NULL DEFAULT 0,
  rows_seen INTEGER NOT NULL DEFAULT 0,
  distinct_items INTEGER,
  removed_marked INTEGER,
  error TEXT,
  user_agent TEXT NOT NULL,
  sections TEXT
);
CREATE TABLE IF NOT EXISTS holding (
  library_id INTEGER NOT NULL REFERENCES library(id),
  item_key TEXT NOT NULL,           -- KYOBO brcd / YES24 goods_id
  platform TEXT NOT NULL,           -- KYOBO / YES24
  title TEXT NOT NULL,
  author_raw TEXT,
  publisher TEXT,
  published_on TEXT,                -- YYYY-MM-DD when parseable
  published_raw TEXT,
  supplier TEXT,
  content_kind TEXT,                -- site label, e.g. 전자책 / 오디오북 (KYOBO only)
  category_code TEXT,               -- KYOBO ctgrId
  category_name TEXT,               -- YES24 [분야]
  cover_url TEXT,
  detail_path TEXT,
  intro_raw TEXT,                   -- book introduction as the LIST page carries it (2026-10-23; no detail page is read)
  first_seen_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL,
  removed_at TEXT,
  last_run_id INTEGER NOT NULL,
  branch_name TEXT,                 -- ELIB portal: 소속도서관
  section TEXT,                     -- list the row came from: MAIN, AUDIO (YES24), EBK/ADO (portal)
  kind_source TEXT,                 -- SITE_LABEL: row states its kind; SITE_SECTION: implied by its list
  PRIMARY KEY (library_id, item_key)
);
CREATE INDEX IF NOT EXISTS holding_title ON holding(title);

CREATE INDEX IF NOT EXISTS holding_run ON holding(library_id, last_run_id);
"""


def now():
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


# content_type / is_audiobook / kind_check are filled by triggers, so every writer (old or new code, SQL) agrees.
TYPE_CASE = """CASE
    WHEN {k} IN ('오디오북','오디오') OR {k} LIKE '%audio%' OR {k} LIKE '%/mp3' OR {k} LIKE '%/aud' THEN 'AUDIOBOOK'
    WHEN {k} = '전자책' OR {k} LIKE 'book/%' THEN 'EBOOK'
    WHEN {k} = '동영상북' THEN 'VIDEO_BOOK'
    WHEN {k} = '키즈북' THEN 'KIDS_BOOK'
    WHEN {k} = '학술논문' THEN 'PAPER'
    ELSE 'UNKNOWN' END"""
CHECK_CASE = """CASE WHEN ({t} LIKE '%오디오북%' OR lower({t}) LIKE '%audiobook%')
    AND NOT ({k} IN ('오디오북','오디오') OR {k} LIKE '%audio%' OR {k} LIKE '%/mp3' OR {k} LIKE '%/aud')
    THEN 'TITLE_SAYS_AUDIO' END"""
DETAIL_URL = """(SELECT CASE WHEN instr(l.base_url, '?') > 0 THEN substr(l.base_url, 1, instr(l.base_url, '?') - 1)
    ELSE rtrim(l.base_url, '/') END FROM library l WHERE l.id = {lib}) || {path}"""
TYPED_SET = (f"detail_url = {DETAIL_URL.format(lib='NEW.library_id', path='NEW.detail_path')}, "
             f"content_type = {TYPE_CASE.format(k='NEW.content_kind')}, "
             f"is_audiobook = CASE {TYPE_CASE.format(k='NEW.content_kind')} WHEN 'AUDIOBOOK' THEN 1 "
             f"WHEN 'UNKNOWN' THEN NULL ELSE 0 END, "
             f"kind_check = {CHECK_CASE.format(t='NEW.title', k='NEW.content_kind')}")


def migrate(con):
    cols = {r[1] for r in con.execute("PRAGMA table_info(holding)")}
    for c, t in (("branch_name", "TEXT"), ("section", "TEXT"), ("kind_source", "TEXT"), ("content_type", "TEXT"),
                 ("is_audiobook", "INTEGER"), ("kind_check", "TEXT"), ("alt_code", "TEXT"),
                 ("detail_url", "TEXT"), ("intro_raw", "TEXT")):  # intro_raw: plan request 2026-10-23
        if c not in cols:
            con.execute(f"ALTER TABLE holding ADD COLUMN {c} {t}")
    run_cols = {r[1] for r in con.execute("PRAGMA table_info(crawl_run)")}
    if "sections" not in run_cols:
        con.execute("ALTER TABLE crawl_run ADD COLUMN sections TEXT")
    # resume point of a site with several lists (YES24 MAIN + AUDIO): the list being read, totals of finished lists
    if "cur_section" not in run_cols:
        con.execute("ALTER TABLE crawl_run ADD COLUMN cur_section TEXT")
    if "done_total" not in run_cols:
        con.execute("ALTER TABLE crawl_run ADD COLUMN done_total INTEGER")
    lib_cols = {r[1] for r in con.execute("PRAGMA table_info(library)")}
    if "source" not in lib_cols:
        con.execute("ALTER TABLE library ADD COLUMN source TEXT")
    if "crawl_skip" not in lib_cols:  # reason this library is not crawled (NULL = crawl it)
        con.execute("ALTER TABLE library ADD COLUMN crawl_skip TEXT")
    if "vendor" not in lib_cols:  # added in the data folder on 2026-10-12 (apply_rename.py); read by library-insert
        con.execute("ALTER TABLE library ADD COLUMN vendor TEXT CHECK (vendor IS NULL OR vendor IN %s)" % (VENDORS,))
    con.execute("CREATE INDEX IF NOT EXISTS holding_type ON holding(content_type)")
    # triggers are dropped and recreated so a changed definition always takes effect
    con.execute("DROP TRIGGER IF EXISTS holding_typed_ins")
    con.execute("DROP TRIGGER IF EXISTS holding_typed_upd")
    con.executescript(f"""
CREATE TRIGGER IF NOT EXISTS holding_typed_ins AFTER INSERT ON holding BEGIN
  UPDATE holding SET {TYPED_SET} WHERE rowid = NEW.rowid;
END;
CREATE TRIGGER IF NOT EXISTS holding_typed_upd AFTER UPDATE OF content_kind, title, detail_path ON holding BEGIN
  UPDATE holding SET {TYPED_SET} WHERE rowid = NEW.rowid;
END;""")
    con.commit()


def backfill_types(con):
    k, t = "content_kind", "title"
    n = con.execute(f"""UPDATE holding SET
        detail_url = {DETAIL_URL.format(lib='holding.library_id', path='holding.detail_path')},
        content_type = {TYPE_CASE.format(k=k)},
        is_audiobook = CASE {TYPE_CASE.format(k=k)} WHEN 'AUDIOBOOK' THEN 1 WHEN 'UNKNOWN' THEN NULL ELSE 0 END,
        kind_check = {CHECK_CASE.format(t=t, k=k)}""").rowcount
    con.commit()
    return n


def connect():
    con = sqlite3.connect(DB, timeout=120, check_same_thread=False)
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("PRAGMA busy_timeout=120000")
    con.executescript(SCHEMA)
    migrate(con)
    return con


# ---------------------------------------------------------------- libraries

def host_key(url):
    """Politeness key: every *.dkyobobook.co.kr site is one KYOBO hosting server."""
    host = urlsplit(url).hostname
    # hosting platforms that serve many libraries from one server: one queue each
    for shared in SHARED_HOSTS:
        if host.endswith("." + shared):
            return shared
    return host


def init(con):
    rows = [(i, n, p, u, h) for i, n, p, u, h in PILOTS]
    for f in SEED_CSVS:
        with open(DEV_SEED / f, encoding="utf-8") as fh:
            for r in csv.DictReader(fh):
                h = (r.get("holdings_count") or "").strip()
                rows.append((int(r["id"]), r["name"], r["platform_type"], r["base_url"],
                             int(h) if h and h != "NULL" else None))
    n = 0
    for i, name, platform, url, holdings in rows:
        if platform not in PLATFORMS or i in DISABLED_IDS or not url or url == "NULL":
            continue
        url = url.rstrip("/")
        con.execute("""INSERT INTO library (id, name, platform_type, base_url, host, seed_holdings_count, source)
                       VALUES (?,?,?,?,?,?,'dev-seed')
                       ON CONFLICT(id) DO UPDATE SET name=excluded.name, platform_type=excluded.platform_type,
                         base_url=excluded.base_url, host=excluded.host,
                         seed_holdings_count=coalesce(excluded.seed_holdings_count, library.seed_holdings_count)""",
                    (i, name, platform, url, host_key(url), holdings))
        n += 1
    con.commit()
    print(f"libraries: {n}")


OKBARI_ID_BASE = 100000  # ids above this are libraries from the third-party list, not in the dev seed


def import_probe(con, probe_tsv, source):
    """Add sites from probe_yes24.py / probe_kyobo_institutions.py output (status OK) as libraries."""
    def norm(u):
        return re.sub(r"^https?://", "", (u or "").lower()).rstrip("/")
    known = {norm(r[0]) for r in con.execute("SELECT base_url FROM library")}
    next_id = max(OKBARI_ID_BASE, con.execute("SELECT coalesce(max(id), 0) FROM library").fetchone()[0]) + 1
    n = skipped = 0
    with open(probe_tsv, encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter="	"):
            platform = r.get("platform_type") or r.get("platform")
            if r.get("status") != "OK" or platform not in PLATFORMS:
                continue
            base = r["base_url"].rstrip("/")
            if norm(base) in known:
                skipped += 1
                continue
            titles = int(r["titles"]) if (r.get("titles") or "").isdigit() else None
            con.execute("INSERT INTO library (id, name, platform_type, base_url, host, seed_holdings_count, source) "
                        "VALUES (?,?,?,?,?,?,?)", (next_id, r["name"], platform, base, host_key(base), titles, source))
            known.add(norm(base))
            next_id += 1
            n += 1
    con.commit()
    print(f"libraries added: {n}, already known: {skipped}")


def import_bookcube(con, probe_tsv, source="okbari-2026-09-30"):
    """Add Bookcube sites that answered the probe (status OK) as BOOKCUBE_FX libraries."""
    known = {r[0].rstrip("/") for r in con.execute("SELECT base_url FROM library")}
    next_id = max(OKBARI_ID_BASE, con.execute("SELECT coalesce(max(id), 0) FROM library").fetchone()[0]) + 1
    n = 0
    with open(probe_tsv, encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            if r["platform"] != "BOOKCUBE" or r["status"] != "OK":
                continue
            base = r["list_url"].rstrip("/")
            if base in known:
                continue
            con.execute("INSERT INTO library (id, name, platform_type, base_url, host, seed_holdings_count, source) "
                        "VALUES (?,?,?,?,?,?,?)", (next_id, r["name"], "BOOKCUBE_FX", base, host_key(base),
                                                   int(r["titles"]), source))
            known.add(base)
            next_id += 1
            n += 1
    con.commit()
    print(f"bookcube libraries added: {n}")


def add_library(con, lib_id, name, platform, base_url, source, vendor=None):
    """Add one site found by hand (e.g. 서울도서관, library-maintenance 4-14) under a chosen collection id, so the
    rules files (scripts/library/rules/) can name that id before the crawl. Stops if the id or address is taken."""
    if platform not in PLATFORMS:
        raise SystemExit(f"unknown platform_type {platform}")
    if vendor and vendor not in VENDORS:
        raise SystemExit(f"unknown vendor {vendor}")
    if lib_id <= OKBARI_ID_BASE:
        raise SystemExit(f"collection ids of libraries found later start at {OKBARI_ID_BASE + 1}")
    base = base_url.rstrip("/")
    taken = con.execute("SELECT id, name, base_url FROM library WHERE id=? OR rtrim(base_url, '/')=?",
                        (lib_id, base)).fetchone()
    if taken:
        raise SystemExit(f"already there: {taken}")
    con.execute("INSERT INTO library (id, name, platform_type, base_url, host, source, vendor) VALUES (?,?,?,?,?,?,?)",
                (lib_id, name, platform, base, host_key(base), source, vendor))
    con.commit()
    print(f"library added: {lib_id} {name} {platform} {base}")


# ---------------------------------------------------------------- HTTP

def ssl_context():
    # Several library sites have incomplete chains or old RSA key exchange (dev ADR-0022). Public read-only pages.
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    ctx.set_ciphers("DEFAULT:@SECLEVEL=0")
    ctx.options |= getattr(ssl, "OP_LEGACY_SERVER_CONNECT", 0x4)
    return ctx


SSL = ssl_context()
_host_locks = defaultdict(threading.Lock)
_host_last = defaultdict(float)


_host_slots = defaultdict(lambda: threading.BoundedSemaphore(PER_HOST))

# Queues by IP (user decision 2026-10-04, --queue-by-ip): every IP gets one queue (one request at a time, MIN_GAP).
# Shared hosting servers (YES24, Bookcube, Aladin ...) are split into one queue per IP, and different domains on
# the same IP share one queue. If a split IP of a shared server gets slow or keeps failing, every IP of that
# shared server falls back to one queue (they may share one machine or database behind the IPs).
QUEUE_BY_IP = False  # shared servers: SHARED_HOSTS in the rules block
SPLIT_SLOW = 12.0         # seconds: average response time of an IP queue of a shared server that triggers the fallback
SPLIT_FAILS = 5           # consecutive failed requests on such a queue that trigger the fallback
_ip_of = {}               # hostname -> IP
_group_of_ip = {}         # IP -> shared server it belongs to
_group_off = set()        # shared servers that fell back to one queue
# Requests at once for chosen queues (user decision 2026-10-04: Kyobo rental/mixed server 103.182.126.192 = 4).
# Starts stay MIN_GAP apart. A queue that gets slow (HOST_SLOW) or keeps failing goes back to one at a time.
HOST_SLOTS = {}           # queue key (IP or host) -> requests at once (--host-slots)
HOST_SLOW = 14.0          # seconds: about twice the 7 s answer measured on 2026-10-04
_slots_off = set()
_slot_sems = {}
_slot_lock = threading.Lock()
_timing = defaultdict(lambda: [0.0, 0, 0])   # key -> [moving average seconds, requests, consecutive failures]
_timing_lock = threading.Lock()


def resolve(hostname):
    if hostname not in _ip_of:
        try:
            _ip_of[hostname] = socket.gethostbyname(hostname)
        except OSError:
            _ip_of[hostname] = None
    return _ip_of[hostname]


def queue_key(url):
    """Politeness queue for a request: its IP, or the shared server name when that server fell back."""
    group = host_key(url)
    if not QUEUE_BY_IP:
        return group
    ip = resolve(urlsplit(url).hostname)
    if not ip:
        return group
    if group in SHARED_HOSTS:
        _group_of_ip.setdefault(ip, group)
    shared = _group_of_ip.get(ip)
    return shared if shared in _group_off else ip


def note_timing(key, seconds, ok):
    with _timing_lock:
        t = _timing[key]
        t[1] += 1
        t[0] = seconds if t[1] == 1 else t[0] * 0.9 + seconds * 0.1
        t[2] = 0 if ok else t[2] + 1
        shared = _group_of_ip.get(key)
        label = f"{key} ({shared})" if shared else key
        if t[1] % 200 == 0:
            log(f"timing {label}: avg {t[0]:.1f}s over last requests, {t[1]} requests")
        if key in HOST_SLOTS:
            if key not in _slots_off and ((t[1] >= 30 and t[0] > HOST_SLOW) or t[2] >= SPLIT_FAILS):
                _slots_off.add(key)
                log(f"SLOTS OFF: {label} avg {t[0]:.1f}s, {t[2]} failures in a row -> one request at a time again")
        elif shared and shared not in _group_off and ((t[1] >= 30 and t[0] > SPLIT_SLOW) or t[2] >= SPLIT_FAILS):
            _group_off.add(shared)
            log(f"SPLIT OFF: {label} avg {t[0]:.1f}s, {t[2]} failures in a row -> every IP of {shared} shares one queue again")


def fetch(url, host):
    key = queue_key(url) if QUEUE_BY_IP else host
    start = time.monotonic()
    try:
        body = _fetch(url, key)
    except Exception:
        note_timing(key, time.monotonic() - start, False)
        raise
    note_timing(key, time.monotonic() - start, True)
    return body


def gap_for(host):
    """MIN_GAP, or a host's own longer gap (MIN_GAP_BY_HOST)."""
    return max(MIN_GAP, MIN_GAP_BY_HOST.get(host, 0.0))


def slots_for(key):
    return 1 if key in _slots_off else HOST_SLOTS.get(key, PER_HOST)


def _fetch(url, host):
    n = slots_for(host)
    if n > 1:
        # several requests may be in flight on this host; their starts stay MIN_GAP apart
        with _slot_lock:
            sem = _slot_sems.setdefault(host, threading.BoundedSemaphore(n))
        with sem:
            with _host_locks[host]:
                wait = _host_last[host] + gap_for(host) - time.monotonic()
                if wait > 0:
                    time.sleep(wait)
                _host_last[host] = time.monotonic()
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=TIMEOUT, context=SSL) as resp:
                return resp.read()
    # default: one at a time; the gap counts from the end of the previous request
    with _host_locks[host]:
        wait = _host_last[host] + gap_for(host) - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=TIMEOUT, context=SSL) as resp:
                return resp.read()
        finally:
            _host_last[host] = time.monotonic()


class BlockedHost(RuntimeError):
    """A request to a server we never call (BLOCKED_HOST_SUFFIX)."""


def is_blocked_host(url):
    host = (urlsplit(url).hostname or "").lower()
    return host == BLOCKED_HOST_SUFFIX or host.endswith("." + BLOCKED_HOST_SUFFIX)


def fetch_retry(url, host):
    if is_blocked_host(url):
        raise BlockedHost(f"not requested (KYOBO subscription server, spec R1-6): {url}")
    last = None
    for attempt in range(RETRIES):
        try:
            return fetch(url, host)
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError, http.client.HTTPException) as e:
            last = e
            if isinstance(e, urllib.error.HTTPError) and e.code < 500 and e.code != 429:
                break
            time.sleep(RETRY_WAIT)
    raise RuntimeError(f"fetch failed: {url}: {last!r}") from last


def blocked_reason(e):
    """'HTTP 403' or 'time-out' when the site turned us away after every retry (spec R1-7), else None."""
    while e is not None:
        if isinstance(e, urllib.error.HTTPError) and e.code == 403:
            return "HTTP 403"
        if isinstance(e, (TimeoutError, socket.timeout)) or (
                isinstance(e, urllib.error.URLError) and isinstance(e.reason, (TimeoutError, socket.timeout))):
            return "time-out"
        e = e.__cause__
    return None


def mark_blocked(con, lib, e):
    """Marks only this library not crawled when its site blocks us (spec R1-7); other libraries go on."""
    reason = blocked_reason(e)
    if not reason:
        return
    with db_lock:
        con.execute("UPDATE library SET crawl_skip=? WHERE id=?",
                    (f"blocked {now()[:10]}: {reason} (clear: --skip {lib['id']} '')", lib["id"]))
        con.commit()
    log(f"[{lib['id']}] {lib['name']}: site blocks us ({reason}); marked not crawled")


# ---------------------------------------------------------------- parsing

NUM = re.compile(r"([\d,]+)")
DATE = re.compile(r"(\d{4})[-.](\d{1,2})[-.](\d{1,2})")
CONTENT_CLICK = re.compile(r"fnContentClick\(\s*this\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'")
GOODS = re.compile(r"goods_id=([0-9A-Za-z]+)")  # KYOBO-supplied items look like 480D231030630
YES24_LINK = re.compile(r"^/?([a-z]+)/detail/?\?goods_id=([0-9A-Za-z]+)")
YES24_KIND = {"ebook": "전자책", "audiobook": "오디오북", "booklearning": "동영상북"}


def yes24_detail(href):
    """(/{menu}/detail/?goods_id=N, kind) from the list link; the menu decides the kind (checked 2026-09-30)."""
    m = YES24_LINK.search(href or "")
    if not m:
        g = GOODS.search(href or "")
        return (f"/ebook/detail/?goods_id={g.group(1)}", None) if g else (None, None)
    return f"/{m.group(1)}/detail/?goods_id={m.group(2)}", YES24_KIND.get(m.group(1), m.group(1))


SKIPPED = threading.local()


def skip(reason):
    SKIPPED.rows = getattr(SKIPPED, "rows", 0) + 1
    SKIPPED.reason = reason


def txt(el):
    return re.sub(r"\s+", " ", el.get_text(" ", strip=True)).strip() if el else None


# an HTML tag (a name that starts with a letter): "<br />" written as text in a YES24 list, "<p>" in Bookcube CDATA;
# "<이갑수>" around a name is not one
HTML_TAG = re.compile(r"</?[A-Za-z][A-Za-z0-9]*(?:\s[^<>]*)?/?>")
LINE_BREAK_TAG = re.compile(r"<(?:br|/p|/div|/li)\b[^<>]*>", re.I)


def clean_intro(text):
    """A book introduction's text as the list carries it: HTML tags stripped (br and block ends become line breaks),
    entities decoded, line breaks kept, blank lines squeezed; None when nothing is left."""
    if text is None:
        return None
    # entities first: a YES24 V1 list writes "&lt;br /&gt;" as text, meant as a line break
    text = HTML_TAG.sub("", LINE_BREAK_TAG.sub("\n", html.unescape(text)))
    lines = [re.sub(r"[ \t\xa0]+", " ", line).strip() for line in text.split("\n")]
    return "\n".join(line for line in lines if line) or None


def intro(el):
    """A book introduction as the list page carries it (user decision 2026-10-23,
    easy/2026-10-23-book-intro-decisions.html #1: the raw text, only on this PC): see clean_intro."""
    return clean_intro(el.decode_contents()) if el is not None else None


def date_of(raw):
    m = DATE.search(raw or "")
    if not m:
        return None
    y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
    if not (1000 <= y <= 2100 and 1 <= mo <= 12 and 1 <= d <= 31):
        return None
    return f"{y:04d}-{mo:02d}-{d:02d}"


def abs_url(base, src):
    if not src:
        return None
    if src.startswith("//"):
        return "https:" + src
    if src.startswith("/"):
        return base + src
    return src


def total_of(text, pattern):
    m = re.search(pattern, text)
    return int(m.group(1).replace(",", "")) if m else None


def parse_kyobo(body, base, prefix="/elibrary-front"):
    doc = BeautifulSoup(body.decode("utf-8", "replace"), "lxml")
    summary = txt(doc.select_one("div.book_resultTxt"))
    if summary is None:
        raise ValueError("KYOBO: div.book_resultTxt missing")
    total = total_of(summary, r"총\s*([\d,]+)\s*개")
    if total is None:
        if "검색 결과가 없습니다" in summary:
            return 0, []
        raise ValueError("KYOBO: total missing")
    items = []
    for li in doc.select("ul.book_resultList > li"):
        a = li.select_one("li.tit a")
        m = CONTENT_CLICK.search(a.get("onclick", "")) if a else None
        if not m:
            skip("KYOBO: row without fnContentClick")
            continue
        writer = li.select_one("li.writer")
        author, publisher, pub_raw = None, None, None
        if writer:
            span = writer.find("span")
            before, after = [], []
            seen_span = False
            for node in writer.children:
                if node is span:
                    seen_span = True
                    continue
                s = node.get_text(" ", strip=True) if hasattr(node, "get_text") else str(node).strip()
                (after if seen_span else before).append(s)
            author = " ".join(x for x in before if x).strip() or None
            publisher = txt(span) or None
            pub_raw = " ".join(x for x in after if x).strip() or None
        img = li.select_one("div.img img")
        kind = li.select_one("span.small")
        items.append(dict(
            item_key=m.group(2), platform="KYOBO", title=txt(a), author_raw=author, publisher=publisher,
            intro_raw=intro(li.select_one("li.txt")),
            published_on=date_of(pub_raw), published_raw=pub_raw, supplier=txt(li.select_one("span.store")),
            content_kind=txt(kind) or ('전자책' if m.group(1) == '001' else None), category_code=m.group(3),
            category_name=None,
            cover_url=abs_url(base, img.get("src") if img else None),
            detail_path=f"{prefix}/content/contentView.ink?cttsDvsnCode={m.group(1)}&brcd={m.group(2)}"
                        f"&ctgrId={m.group(3)}&sntnAuthCode={m.group(4)}"))
    return total, items


def decode(body, fallback):
    head = body[:2048].lower()
    if b"euc-kr" in head or b"ks_c_5601" in head:
        return body.decode("euc-kr", "replace")
    try:
        return body.decode("utf-8")
    except UnicodeDecodeError:
        return body.decode(fallback, "replace")


def split_category(title):
    m = re.match(r"^\s*\[([^\]]+)\]\s*(.*)$", title or "")
    return (m.group(1), m.group(2)) if m else (None, title)


def parse_yes24_v2(body, base, charset="utf-8"):
    doc = BeautifulSoup(decode(body, charset), "lxml")
    total_el = doc.select_one("div.total em")
    if total_el is None:
        raise ValueError("YES24: div.total em missing")
    total = int(NUM.search(total_el.get_text()).group(1).replace(",", ""))
    items = []
    for bx in doc.select("div.ebook-list div.bx"):
        a = bx.select_one("p.tit a")
        g = GOODS.search(a.get("href", "")) if a else None
        if not g:
            skip("YES24: row without goods_id")
            continue
        strong = bx.select_one("p.tit strong")
        category = txt(strong).strip("[]") if strong else None
        spans = bx.select("p.detail span")
        pub_raw = txt(spans[1]) if len(spans) > 1 else None
        img = bx.select_one("a.thumb img")
        items.append(dict(
            item_key=g.group(1), platform="YES24", title=txt(a), author_raw=txt(bx.select_one("p.writer")),
            publisher=txt(spans[0]) if spans else None, published_on=date_of(pub_raw), published_raw=pub_raw,
            supplier=txt(spans[2]) if len(spans) > 2 else None, category_code=None,
            category_name=category, cover_url=abs_url(base, img.get("src") if img else None),
            intro_raw=intro(bx.select_one("p.txt")),
            detail_path=yes24_detail(a.get("href"))[0], content_kind=yes24_detail(a.get("href"))[1]))
    return total, items


def parse_yes24_v1(body, base):
    doc = BeautifulSoup(decode(body, "euc-kr"), "lxml")
    content = doc.select_one("#content")
    if content is None:
        raise ValueError("YES24_V1: #content missing")
    total = total_of(content.get_text(" "), r"전체\s*([\d,]+)\s*건")
    if total is None:
        raise ValueError("YES24_V1: total missing")
    items = []
    for li in content.select("div#booklist ul.list > li.line"):
        a = li.select_one("span.book_tit > a")
        g = GOODS.search(a.get("href", "")) if a else None
        if not g:
            skip("YES24_V1: row without goods_id")
            continue

        def field(name):
            return txt(li.select_one(f'ul.book_txt li[title="{name}"]'))
        author = field("저자")
        pub_raw = field("출간일")
        kind = li.select_one("span.book_kind")
        img = li.select_one("div.cover img")
        items.append(dict(
            item_key=g.group(1), platform="YES24", title=txt(a),
            author_raw=re.sub(r"[<>]", "", author).strip() if author else None, publisher=field("출판사"),
            published_on=date_of(pub_raw), published_raw=pub_raw, supplier=field("공급사"),
            category_code=None, category_name=txt(kind).strip("[]") if kind else None,
            cover_url=abs_url(base, img.get("src") if img else None), intro_raw=intro(li.select_one("p.book_note")),
            detail_path=yes24_detail(a.get("href"))[0], content_kind=yes24_detail(a.get("href"))[1]))
    return total, items


def parse_portal_search(body, base, menus):
    """table.do search rows: div.row > a.name.goDetail[data-book_idx][data-type], then p: author, "publisher, date",
    branch. menus = "ebook menu,audio menu" for detail pages (read from the page script)."""
    doc = BeautifulSoup(body.decode("utf-8", "replace"), "lxml")
    total = re.search(r"총\s*<b>([\d,]+)</b>\s*건", body.decode("utf-8", "replace"))
    ebk_menu, ado_menu = (menus.split(",") + ["", ""])[:2] if menus else ("", "")
    items, seen = [], set()
    for row in doc.select("div.row"):
        a = row.select_one("a.name.goDetail[data-book_idx]")
        if a is None:
            continue
        idx, kind = a["data-book_idx"], a.get("data-type") or "EBK"
        if (kind, idx) in seen:
            continue
        seen.add((kind, idx))
        ps = [txt(x) for x in a.find_parent("div").find_all("p", recursive=False)]
        pub, pub_raw = None, None
        if len(ps) > 1 and ps[1]:
            parts = ps[1].rsplit(",", 1)
            pub = parts[0].strip() or None
            pub_raw = parts[1].strip() if len(parts) > 1 else None
        menu = ebk_menu if kind == "EBK" else ado_menu
        img = row.select_one("div.thumb img")
        items.append(dict(
            item_key=f"{kind}:{idx}", platform="ELIB_PORTAL", title=txt(a), author_raw=ps[0] if ps else None,
            publisher=pub, published_on=date_of(pub_raw), published_raw=pub_raw, supplier=None,
            content_kind={"EBK": "전자책", "ADO": "오디오북"}.get(kind, kind), category_code=None, category_name=None,
            cover_url=abs_url(base, img.get("src") if img else None), section=kind,
            detail_path=(f"/module/elib/book/view.do?menu_idx={menu}&book_idx={idx}&type={kind}" if menu
                         else f"/module/elib/book/view.do?book_idx={idx}&type={kind}"),
            branch_name=ps[2] if len(ps) > 2 else None))
    return (int(total.group(1).replace(",", "")) if total else None), items


def parse_portal(body, base, kind, menu=None):
    doc = BeautifulSoup(body.decode("utf-8", "replace"), "lxml")
    if doc.select_one("ul.bbs_webzine") is None and "찾으시는 자료가 없습니다" not in doc.get_text():
        raise ValueError("ELIB_PORTAL: ul.bbs_webzine missing")
    # div.info is "author | publisher | date" on most portals, "publisher | author | date" on these (R3-1)
    publisher_first = urlsplit(base).hostname in PUBLISHER_FIRST_PORTAL_HOSTS
    items, seen = [], set()
    for li in doc.select("ul.bbs_webzine > li"):
        a = li.select_one("div.list-body a.book_link[data-book_idx]")
        if a is None:
            continue
        idx = a["data-book_idx"]
        if idx in seen:
            continue
        seen.add(idx)
        info = [txt(sp) for sp in li.select("div.info > span") if "txt-bar" not in (sp.get("class") or [])]
        meta = {}
        for label in li.select("div.meta label"):
            value = label.find_next_sibling("span")
            meta[txt(label).rstrip(":").strip()] = txt(value)
        first = info[0] if info else None
        second = info[1] if len(info) > 1 else None
        author, publisher = (second, first) if publisher_first else (first, second)
        pub_raw = info[2] if len(info) > 2 else None
        img = li.select_one("div.thumb img")
        items.append(dict(
            item_key=f"{kind}:{idx}", platform="ELIB_PORTAL", title=txt(a), author_raw=author,
            publisher=publisher, published_on=date_of(pub_raw), published_raw=pub_raw,
            supplier=meta.get("공급사"), content_kind={"EBK": "전자책", "ADO": "오디오북"}.get(kind, kind),
            category_code=None, category_name=None, cover_url=abs_url(base, img.get("src") if img else None),
            # menu_idx is required on some portals (Gangwon answers an empty page without it, 2026-09-30)
            detail_path=f"/module/elib/book/view.do?menu_idx={menu}&book_idx={idx}&type={kind}" if menu
            else f"/module/elib/book/view.do?book_idx={idx}&type={kind}", branch_name=meta.get("소속도서관")))
    return None, items


def gangnam_kind(label):
    head = (label or "").split("·")[0].strip()
    return {"eBook": "전자책"}.get(head, head or None)


def parse_gangnam(body, base):
    doc = BeautifulSoup(body.decode("euc-kr", "replace"), "lxml")
    header = doc.select_one("div.list_header")
    total = total_of(txt(header) or "", r"([\d,]+)\s*건")
    if total is None:
        raise ValueError("GANGNAM: total missing")
    items = []
    for info in doc.select("div.book_info:has(> div.book_title)"):
        a = info.select_one("div.book_title a")
        m = re.search(r"book_num=(\w+)", a.get("href", "")) if a else None
        if not m:
            skip("GANGNAM: row without book_num")
            continue
        publish = txt(info.select_one("div.publish_date")) or ""
        parts = [x.strip() for x in publish.split("·")]
        pub_raw = parts[1] if len(parts) > 1 else None
        img = info.find_previous("img")
        items.append(dict(
            item_key=m.group(1), platform="GANGNAM", title=txt(a), author_raw=txt(info.select_one("div.writer")),
            publisher=parts[0] or None, published_on=date_of(pub_raw), published_raw=pub_raw,
            supplier=txt(info.select_one("div.current strong")),
            content_kind=gangnam_kind(txt(info.select_one("div.publish_ebook"))), kind_source="SITE_LABEL",
            category_code=None,
            category_name=None, cover_url=None, intro_raw=intro(info.select_one("div.book_intro")),
            detail_path=f"/elibbook/book_detail.asp?book_num={m.group(1)}"))
    return total, items


PRODUCT = re.compile(r"<Product>(.*?)</Product>", re.S)


def xml_field(block, name):
    m = re.search(rf"<{name}>(?:<!\[CDATA\[(.*?)\]\]>|([^<]*))</{name}>", block, re.S)
    if not m:
        return None
    v = (m.group(1) if m.group(1) is not None else m.group(2)).strip()
    return html.unescape(v) or None


def parse_bookcube(body, base):
    s = body.decode("utf-8", "replace")
    total = re.search(r"<totalItem>(\d+)</totalItem>", s)
    if not total:
        raise ValueError("BOOKCUBE: totalItem missing")
    items = []
    for m in PRODUCT.finditer(s):
        block = m.group(1)
        num = xml_field(block, "num")
        if not num:
            skip("BOOKCUBE: product without num")
            continue
        if not xml_field(block, "name"):  # the site has no title for it, list or detail (ebook.ansan.ac.kr F0002074)
            skip("BOOKCUBE: product without name")
            continue
        pub_raw = xml_field(block, "publisherDate")
        ctype, fmt = xml_field(block, "category_type"), xml_field(block, "format")
        items.append(dict(
            item_key=num, platform="BOOKCUBE", title=xml_field(block, "name"), author_raw=xml_field(block, "author"),
            publisher=xml_field(block, "publisher"), published_on=date_of(pub_raw), published_raw=pub_raw,
            supplier=xml_field(block, "supplier"), content_kind=f"{ctype or '?'}/{fmt or '?'}",
            kind_source="SITE_LABEL", category_code=None, alt_code=xml_field(block, "legacyCode"),
            category_name=xml_field(block, "category"), cover_url=xml_field(block, "listImage"),
            intro_raw=clean_intro(xml_field(block, "longExplain") or xml_field(block, "shortExplain")),
            detail_path=f"/product/view/?num={num}"))
    return int(total.group(1)), items


ALADIN_GOODS = re.compile(r"goods_id=([A-Za-z0-9_-]+)")  # numeric, or e.g. BOOKRAILN0809003 (kmou, 2026-10-04)
ALADIN_BG = re.compile(r"url\(([^)]+)\)")


def aladin_parts(base):
    """base_url keeps the e-book menu code: '{root}?nav_code={code}' (root may end in /aladin or /HOME)."""
    root, _, query = base.partition("?")
    m = re.search(r"nav_code=(\w+)", query)
    return root.rstrip("/"), (m.group(1) if m else "")


def parse_aladin(body, root, nav, ebook_kind="전자책"):
    """Standard Aladin e-library list (sub/sub.htm): "총 N 건의 도서", rows li.line (2026-10-03, uc.aladinebook.co.kr)."""
    doc = BeautifulSoup(body.decode("utf-8", "replace"), "lxml")
    text = html.unescape(doc.get_text(" ")).replace("\xa0", " ")
    total = re.search(r"총\s*([\d,]+)\s*건의\s*도서", text)
    if not total:
        raise ValueError("ALADIN: total missing")
    items = []
    for li in doc.select("ul.list > li.line"):
        a = li.select_one("h2.book_tit a, .book_tit a")
        g = ALADIN_GOODS.search(a.get("href", "")) if a else None
        if not g:
            skip("ALADIN: row without goods_id")
            continue
        pub = txt(li.select_one("span.pub"))
        bg = li.select_one("div.book-img1")
        cover = ALADIN_BG.search(bg.get("style", "")) if bg else None
        company = li.select_one("div.com div")
        items.append(dict(
            item_key=g.group(1), platform="ALADIN", title=txt(a), author_raw=txt(li.select_one("span.writer")),
            publisher=pub.lstrip("|").strip() if pub else None, published_on=None, published_raw=None,
            supplier=(company.get("class") or [""])[0].replace("company", "") if company else None,
            content_kind=ebook_kind, category_code=None, category_name=None,
            cover_url=abs_url(root, cover.group(1).strip("'\"")) if cover else None,
            detail_path=f"/sub/sub.htm?mode=view&nav_code={nav}&goods_id={g.group(1)}"))
    return int(total.group(1).replace(",", "")), items


# 서울도서관 elib.seoul.go.kr (library-maintenance 4-14, maintenance/platforms.md 11): the site's own JSON.
# The catalogue is the category lists, e-books (contentType EB) and audiobooks (TY03); category numbers differ.
SEOUL_CONTENT_TYPES = (("EB", "전자책"), ("TY03", "오디오북"))
# The overseas category lists only OverDrive titles (ownerCode OV), lent on OverDrive's own site: not read
# (2026-10-20: 3,900 rows, all OV).
SEOUL_SKIPPED_CATEGORIES = {"국외전자책"}
SEOUL_SUPPLIERS = {"OD": "ECO", "BQ": "북큐브", "BX": "OPMS", "YE": "예스24", "YB": "Y2BOOKS", "CP": "컨텐츠포탈",
                   "AL": "알라딘", "AD": "오디언", "OV": "오버드라이브"}  # ownerCode as r_detail.js names them


def seoul_json(body):
    import json
    try:
        return json.loads(body.decode("utf-8"))
    except ValueError:
        raise ValueError("SEOUL_ELIB: answer is not JSON (an error page?)") from None


def seoul_segments(lib):
    """[(section, major category)] for every category with books, e-books then audiobooks: 'EB-001', 'TY03-139'."""
    segs = []
    for content_type, _ in SEOUL_CONTENT_TYPES:
        data = seoul_json(fetch_retry(f"{lib['base_url']}/api/category/main?contentType={content_type}", lib["host"]))
        for c in data.get("ContentDataList") or []:
            if int(c.get("contentCount") or 0) > 0 and c.get("categoryName") not in SEOUL_SKIPPED_CATEGORIES:
                segs.append((f"{content_type}-{c['categoryNo']}", c["categoryNo"]))
    if not segs:
        raise ValueError("SEOUL_ELIB: no category with books")
    return segs


def parse_seoul(body, seg):
    """One category page: {totalCount, ContentDataList}. OverDrive rows (ownerCode OV) are lent on OverDrive's own site,
    not here: skipped, so they are not counted against the total either."""
    data = seoul_json(body)
    total = str(data.get("totalCount") or "")
    if not total.isdigit():
        raise ValueError("SEOUL_ELIB: totalCount missing")
    kind = dict(SEOUL_CONTENT_TYPES)[seg[0].split("-")[0]]
    items = []
    for r in data.get("ContentDataList") or []:
        key, title = (r.get("contentsKey") or "").strip(), (r.get("title") or "").strip()
        if not key or not title:
            skip("SEOUL_ELIB: row without contentsKey or title")
            continue
        if r.get("ownerCode") == "OV":
            skip("SEOUL_ELIB: OverDrive (overseas) row")
            continue
        pub_raw = r.get("publishDate")
        items.append(dict(
            item_key=key, platform="SEOUL_ELIB", title=title, author_raw=(r.get("author") or "").strip() or None,
            publisher=(r.get("publisher") or "").strip() or None, published_on=date_of(pub_raw), published_raw=pub_raw,
            supplier=SEOUL_SUPPLIERS.get(r.get("ownerCode"), r.get("ownerCode")),
            content_kind="오디오북" if r.get("contentsType") == "TY03" else kind, kind_source="SITE_LABEL",
            category_code=seg[1], category_name=r.get("contentsTypeDesc"), cover_url=None,
            detail_path=f"/contents/detail.do?no={key}"))
    return int(total), items


def portal_root(base):
    return base.split("?")[0].rstrip("/")


def portal_segments(lib):
    """[(kind, menu_idx)] for the portal's 'new books' lists (e-books, audiobooks), read from its home page."""
    root = portal_root(lib["base_url"])
    home = decode(fetch_retry(f"{root}/index.do", lib["host"]), "utf-8")
    segs = []
    for kind in ("EBK", "ADO"):
        m = re.search(r"book/index\.do\?menu_idx=(\d+)&(?:amp;)?menu=NEW&(?:amp;)?type=" + kind, home)
        if m:
            segs.append((kind, m.group(1)))
    if segs:
        kind, menu = segs[0]
        probe = decode(fetch_retry(f"{root}/module/elib/book/index.do?menu_idx={menu}&menu=NEW&type={kind}"
                                   f"&rowCount=10&viewPage=1", lib["host"]), "utf-8")
        if "bbs_webzine" in probe:
            return segs
    # list pages need a login (Incheon, 2026-09-30): the blank search lists every book without one
    probe = decode(fetch_retry(f"{root}/module/elib/search/table.do?menu_idx=10&type=&search_type=&author_name="
                               f"&book_pubname=&book_year=&device=&search_text=%20&rowCount=10&viewPage=1",
                               lib["host"]), "utf-8")
    m = re.search(r"==\s*'EBK'\)\s*\{\s*\$\('#detail_menu_idx'\)\.val\('(\d+)'\);\s*\}\s*else\s*\{\s*"
                  r"\$\('#detail_menu_idx'\)\.val\('(\d+)'\)", probe)
    if "goDetail" not in probe:
        raise ValueError("ELIB_PORTAL: neither the NEW list nor the blank search works")
    return [("SEARCH", f"{m.group(1)},{m.group(2)}" if m else "")]


def yes24_segments(lib):
    home = decode(fetch_retry(lib["base_url"] + "/", lib["host"]), "utf-8")
    segs = [("MAIN", None)]
    if 'href="/audiobook/' in home:
        segs.append(("AUDIO", None))
    return segs


def segments(lib):
    """[(section, menu)]: the lists that together make the site's catalogue."""
    p = lib["platform_type"]
    if p == "ELIB_PORTAL_V1":
        return portal_segments(lib)
    if p.startswith("YES24"):
        return yes24_segments(lib)
    if p == "SEOUL_ELIB_V1":
        return seoul_segments(lib)
    return [("MAIN", None)]


def page_url(lib, page, seg=("MAIN", None)):
    base, p = lib["base_url"], lib["platform_type"]
    if p == "KYOBO_V1":
        return (f"{base}/elibrary-front/search/searchList.ink?schClst=all&schDvsn=000&schTxt=%20"
                f"&recordCount={seg_page_size(lib, seg)}&pageIndex={page}")
    if p == "KYOBO_SUBSCRIPTION_V1":
        return (f"{base}/content/contentList.ink?cttsDvsnCode=001&contentAll=Y&ctgrId=&orderByKey=cttsHnglName"
                f"&pageIndex={page}&recordCount={PAGE_SIZE[p]}")
    if p == "ELIB_PORTAL_V1":
        kind, menu = seg
        if kind == "SEARCH":  # blank search (one space) when the list pages need a login (Incheon)
            return (f"{portal_root(base)}/module/elib/search/table.do?menu_idx=10&type=&search_type=&author_name="
                    f"&book_pubname=&book_year=&device=&search_text=%20&rowCount={seg_page_size(lib, seg)}"
                    f"&viewPage={page}")
        return (f"{portal_root(base)}/module/elib/book/index.do?menu_idx={menu}&menu=NEW&type={kind}"
                f"&rowCount={seg_page_size(lib, seg)}&viewPage={page}")
    if p == "GANGNAM_V1":
        return f"{base}/elibbook/book_category.asp?page_num={page}"
    if p == "BOOKCUBE_FX":
        sort = 2 if seg[1] == "pubdt" else 1  # 1 title, 2 publish date
        return f"{base}/xml/productList/?itemCount={PAGE_SIZE[p]}&page={page}&sort={sort}"
    if p == "ALADIN_V1":  # order_list=2 = title order; startPage is a 0-based row offset, not a page number
        root, nav = aladin_parts(base)
        order = 1 if seg[1] == "pubdt" else 2  # 2 title, 1 publish date
        return (f"{root}/sub/sub.htm?nav_code={nav}&code=&order_list={order}&view_cate=&list_scale={PAGE_SIZE[p]}"
                f"&search_item=&search_order=&view_level=1&startPage={(page - 1) * PAGE_SIZE[p]}")
    if p == "SEOUL_ELIB_V1":
        return (f"{base}/api/contents/catesearch?libCode=&majorCategory={seg[1]}&subCategory=&innerKeyword="
                f"&orderOption=1&loanable=&currentCount={page}&pageCount={PAGE_SIZE[p]}")
    if seg[0] == "AUDIO":  # 10 rows (the newer layout on every YES24 site seen so far)
        return f"{base}/audiobook/?mode=total&sort=title&cate_id=&page_num={page}"
    view = f"&view={PAGE_SIZE[p]}" if p == "YES24_V1" else ""
    sort = seg[1] if seg[1] in ("pubdt",) else "title"
    return f"{base}/search/?src_key=&srch_order=title&sort={sort}{view}&page_num={page}"


def seg_page_size(lib, seg):
    if lib["platform_type"].startswith("YES24") and seg[0] == "AUDIO":
        return 10
    if lib["host"] in PAGE_SIZE_BY_HOST and lib["platform_type"] in ("KYOBO_V1", "ELIB_PORTAL_V1"):
        return PAGE_SIZE_BY_HOST[lib["host"]]
    return PAGE_SIZE[lib["platform_type"]]


def parse(lib, body, seg=("MAIN", None)):
    p = lib["platform_type"]
    if p == "KYOBO_V1":
        total, items = parse_kyobo(body, lib["base_url"])
        source = "SITE_LABEL"
    elif p == "KYOBO_SUBSCRIPTION_V1":
        total, items = parse_kyobo(body, lib["base_url"], prefix="")
        source = "SITE_SECTION"  # cttsDvsnCode=001 = e-book list
    elif p == "ELIB_PORTAL_V1":
        if seg[0] == "SEARCH":
            total, items = parse_portal_search(body, portal_root(lib["base_url"]), seg[1])
        else:
            total, items = parse_portal(body, portal_root(lib["base_url"]), seg[0], seg[1])
        source = "SITE_SECTION"  # type=EBK / ADO list
    elif p == "GANGNAM_V1":
        total, items = parse_gangnam(body, lib["base_url"])
        source = "SITE_LABEL"
    elif p == "ALADIN_V1":
        root, nav = aladin_parts(lib["base_url"])
        # a site whose name says it is an audiobook library lists audiobooks in the same screen
        kind = "오디오북" if "오디오북" in (lib.get("name") or "") else "전자책"
        total, items = parse_aladin(body, root, nav, kind)
        source = "SITE_SECTION"
    elif p == "BOOKCUBE_FX":
        total, items = parse_bookcube(body, lib["base_url"])
        source = "SITE_LABEL"  # category_type/format of each product
    elif p == "SEOUL_ELIB_V1":
        total, items = parse_seoul(body, seg)
        source = "SITE_LABEL"  # contentsType of each row
    else:
        if p == "YES24_V1" and seg[0] == "MAIN":
            total, items = parse_yes24_v1(body, lib["base_url"])
        else:
            total, items = parse_yes24_v2(body, lib["base_url"], "utf-8" if p == "YES24_V2" else "euc-kr")
        source = "SITE_SECTION"  # /search/ = e-books, /audiobook/ = audiobooks
        for it in items:
            if seg[0] == "AUDIO":
                it["item_key"] = "AUDIO:" + it["item_key"]
                if not it.get("content_kind") or it["content_kind"] == "전자책":
                    it["content_kind"] = "오디오북"
                    it["detail_path"] = (it["detail_path"] or "").replace("/ebook/detail/", "/audiobook/detail/")
            elif not it.get("content_kind"):
                it["content_kind"] = "전자책"
    for it in items:
        it.setdefault("section", seg[0])
        if it["section"] is None:
            it["section"] = seg[0]
        if not it.get("kind_source"):
            it["kind_source"] = source
    return total, items


# ---------------------------------------------------------------- run

db_lock = threading.Lock()
COLS = ["item_key", "platform", "title", "author_raw", "publisher", "published_on", "published_raw", "supplier",
        "content_kind", "category_code", "category_name", "cover_url", "detail_path", "branch_name", "section", "kind_source", "alt_code",
        "intro_raw"]  # intro_raw: the list page's book introduction (2026-10-23); None where the list has none


def save(con, lib_id, run_id, items, at):
    sql = f"""INSERT INTO holding (library_id, {", ".join(COLS)}, first_seen_at, last_seen_at, removed_at, last_run_id)
              VALUES (?, {", ".join("?" * len(COLS))}, ?, ?, NULL, ?)
              ON CONFLICT(library_id, item_key) DO UPDATE SET
                {", ".join(f"{c}=excluded.{c}" for c in COLS[1:])},
                last_seen_at=excluded.last_seen_at, removed_at=NULL, last_run_id=excluded.last_run_id"""
    with db_lock:
        con.executemany(sql, [(lib_id, *[it.get(c) for c in COLS], at, at, run_id) for it in items])


def log(msg):
    print(f"{datetime.now():%H:%M:%S} {msg}", flush=True)


class BrokenPage(Exception):
    """A page the site keeps failing on (HTTP 5xx or an error page) after retries; other pages are fine."""


def fetch_parse(lib, page, seg):
    last = None
    for attempt in range(RETRIES):
        try:
            return parse(lib, fetch_retry(page_url(lib, page, seg), lib["host"]), seg)
        except ValueError as e:  # an error page instead of the list (e.g. YES24 without div.total em)
            last = e
            time.sleep(RETRY_WAIT)
        except RuntimeError as e:
            # after fetch_retry's own retries: a later page that keeps failing (5xx, timeout, cut off) is skipped
            # and read again at the end; only a failing first page fails the library
            if page > 1:
                raise BrokenPage(str(e)[:200]) from e
            raise
    if page > 1:
        raise BrokenPage(repr(last)[:200])
    raise last


def fill_other_order(con, lib, run_id, seg):
    """Read a list again in publish-date order; books hidden behind a broken page appear on other pages
    (YES24 sort=pubdt, Bookcube sort=2, Aladin order_list=1)."""
    alt = (seg[0], "pubdt")
    page, pages = 1, None
    while pages is None or page <= pages:
        try:
            total, items = fetch_parse(lib, page, alt)
        except BrokenPage:
            page += 1
            continue
        pages = pages or max(1, -(-total // seg_page_size(lib, seg)))
        save(con, lib["id"], run_id, items, now())
        with db_lock:
            con.commit()
        page += 1
    log(f"[{lib['id']}] {lib['name']}: refilled {pages} pages in pubdt order")


def crawl_library(con, lib, limit_pages=None, only_sections=None):
    p = lib["platform_type"]
    try:
        segs = segments(lib)
    except Exception as e:  # noqa: BLE001
        log(f"[{lib['id']}] {lib['name']}: FAILED reading the home page {e!r}")
        mark_blocked(con, lib, e)
        segs = None
    if segs is None:
        with db_lock:
            con.execute("INSERT INTO crawl_run (library_id, kind, status, started_at, finished_at, page_size, "
                        "user_agent, error) VALUES (?, 'FULL', 'FAILED', ?, ?, ?, ?, 'home page')",
                        (lib["id"], now(), now(), PAGE_SIZE[p], USER_AGENT))
            con.commit()
        return
    all_sections = [sg[0] for sg in segs]
    if only_sections:
        segs = [sg for sg in segs if sg[0] in only_sections]
        if not segs:
            return
        with db_lock:
            want = ",".join(sg[0] for sg in segs)
            done = con.execute("SELECT 1 FROM crawl_run WHERE library_id=? AND status='SUCCEEDED' "
                               "AND (sections=? OR ','||sections||',' LIKE '%,'||?||',%')",
                               (lib["id"], want, want)).fetchone()
        if done:
            return
    # YES24 with an audiobook list resumes inside the list it was reading (2026-10-04: big sites lost hours on restart)
    resumable = len(segs) > 1 and p.startswith("YES24") and not only_sections
    multi = (len(segs) > 1 or p == "ELIB_PORTAL_V1" or bool(only_sections)) and not resumable  # start over
    with db_lock:
        # resume only the library's latest run; an older RUNNING run was superseded (e.g. by `invalidate`)
        con.execute("UPDATE crawl_run SET status='FAILED', finished_at=?, error=coalesce(error, 'superseded') "
                    "WHERE library_id=? AND status='RUNNING' AND id < (SELECT max(id) FROM crawl_run WHERE library_id=?)",
                    (now(), lib["id"], lib["id"]))
        row = con.execute("SELECT id, last_page, site_total, cur_section, done_total FROM crawl_run "
                          "WHERE library_id=? AND kind='FULL' AND status='RUNNING' ORDER BY id DESC LIMIT 1",
                          (lib["id"],)).fetchone()
        if row and multi:
            con.execute("UPDATE crawl_run SET status='FAILED', finished_at=?, error='abandoned; restarted' WHERE id=?",
                        (now(), row[0]))
            row = None
        start, done_total = 0, 0
        if row and resumable:
            names = [sg[0] for sg in segs]
            if row[3] in names and row[1]:
                start, done_total = names.index(row[3]), row[4] or 0
            else:  # no resume point saved for this list: read it from the start, keep the run
                row = (row[0], 0, None)
        if row:
            run_id, last_page, resume_total = row[0], row[1], row[2]
        else:
            cur = con.execute("INSERT INTO crawl_run (library_id, kind, status, started_at, page_size, user_agent, "
                              "sections) VALUES (?, 'FULL', 'RUNNING', ?, ?, ?, ?)",
                              (lib["id"], now(), seg_page_size(lib, segs[0]), USER_AGENT,
                               ",".join(sg[0] for sg in segs)))
            run_id, last_page, resume_total = cur.lastrowid, 0, None
        con.commit()
    if last_page:
        log(f"[{lib['id']}] {lib['name']}: resume run {run_id} after page {last_page}"
            + (f" of {segs[start][0]}" if resumable else ""))
    try:
        complete, site_total, no_total, skipped, broken_pages = True, done_total, False, 0, []
        for seg in segs[start:]:
            size = seg_page_size(lib, seg)
            seg_total = resume_total
            page = last_page + 1
            pages = None if seg_total is None else max(1, -(-seg_total // size))
            while pages is None or page <= pages:
                if limit_pages and page > limit_pages:
                    complete = False
                    break
                SKIPPED.rows = 0
                try:
                    total, items = fetch_parse(lib, page, seg)
                except (RuntimeError, ValueError) as e:
                    if seg[0] != "AUDIO" or page != 1 or isinstance(e, BrokenPage):
                        raise
                    log(f"[{lib['id']}] AUDIO list unreadable, skipped: {e}")
                    broken_pages.append("AUDIO-list-unreadable")
                    break
                except BrokenPage as e:
                    broken_pages.append(f"{seg[0]}:{page}")
                    log(f"[{lib['id']}] {seg[0]} page {page} skipped: {e}")
                    page += 1
                    continue
                skipped += SKIPPED.rows
                if total is not None and seg_total is None:
                    seg_total = total
                    pages = max(1, -(-total // size))
                    log(f"[{lib['id']}] {lib['name']} ({p} {seg[0]}): total {total}, {pages} pages")
                elif page == 1 and total is None:
                    no_total = True
                    log(f"[{lib['id']}] {lib['name']} ({p} {seg[0]}): no total, paging until empty")
                save(con, lib["id"], run_id, items, now())
                with db_lock:
                    con.execute("UPDATE crawl_run SET last_page=?, rows_seen=rows_seen+?, site_total=?, "
                                "cur_section=?, done_total=? WHERE id=?",
                                (0 if multi else page, len(items),
                                 site_total + (seg_total or 0) if multi else seg_total,
                                 seg[0] if resumable else None, site_total if resumable else None, run_id))
                    con.commit()
                if not items and not SKIPPED.rows:  # a page whose rows were all skipped is not empty
                    if pages is not None and page < pages:
                        raise ValueError(f"empty page {page} of {pages} ({seg[0]})")
                    break
                if page % 50 == 0:
                    log(f"[{lib['id']}] {seg[0]} page {page}/{pages or '?'}")
                page += 1
            site_total += seg_total or 0
            last_page, resume_total = 0, None
            seg_broken = [int(x.split(":")[1]) for x in broken_pages if x.startswith(seg[0] + ":")]
            if seg_broken and complete:
                # most broken pages are passing error screens: read just those pages again first
                still = []
                for bp in seg_broken:
                    try:
                        _, items = fetch_parse(lib, bp, seg)
                        save(con, lib["id"], run_id, items, now())
                        broken_pages.remove(f"{seg[0]}:{bp}")
                    except BrokenPage:
                        still.append(bp)
                with db_lock:
                    con.commit()
                log(f"[{lib['id']}] {seg[0]}: re-read {len(seg_broken)} skipped pages, {len(still)} still broken")
                # pages the site always breaks: YES24 shows those books on other pages in publish-date order
                if still and seg[0] == "MAIN" and (p.startswith("YES24") or p in ("BOOKCUBE_FX", "ALADIN_V1")):
                    fill_other_order(con, lib, run_id, seg)
        if broken_pages:
            with db_lock:
                con.execute("UPDATE crawl_run SET error=coalesce(error || '; ', '') || ? WHERE id=?",
                            ("site error on pages " + ",".join(broken_pages) + " (skipped; YES24 refilled by pubdt order)",
                             run_id))
                con.commit()
        if skipped:
            with db_lock:
                con.execute("UPDATE crawl_run SET error=? WHERE id=?",
                            (f"skipped {skipped} rows: {getattr(SKIPPED, 'reason', '')}", run_id))
                con.commit()
        finish(con, lib, run_id, None if no_total else site_total - skipped, partial=not complete,
               no_total=no_total,
               sections=[sg[0] for sg in segs], whole=[sg[0] for sg in segs] == all_sections)
    except Exception as e:  # noqa: BLE001 - record any failure on the run
        with db_lock:
            con.execute("UPDATE crawl_run SET status='FAILED', finished_at=?, error=? WHERE id=?",
                        (now(), repr(e)[:1000], run_id))
            con.commit()
        log(f"[{lib['id']}] {lib['name']}: FAILED {e!r}")
        mark_blocked(con, lib, e)


def finish(con, lib, run_id, site_total, partial, no_total=False, sections=("MAIN",), whole=True):
    with db_lock:
        distinct = con.execute("SELECT count(*) FROM holding WHERE library_id=? AND last_run_id=?",
                               (lib["id"], run_id)).fetchone()[0]
        if no_total:
            ok = not partial and distinct > 0  # every list was read until an empty page
        else:
            rows_seen = con.execute("SELECT rows_seen FROM crawl_run WHERE id=?", (run_id,)).fetchone()[0]
            ok = not partial and site_total is not None and (
                abs(distinct - site_total) <= max(1, site_total * MATCH_TOLERANCE)
                # every listed row was read; fewer distinct books means the site's list repeats some
                or (len(sections) == 1 and abs(rows_seen - site_total) <= max(1, site_total * MATCH_TOLERANCE)))
            if ok and distinct < site_total * (1 - MATCH_TOLERANCE):
                con.execute("UPDATE crawl_run SET error=coalesce(error || '; ', '') || ? WHERE id=?",
                            (f"read {rows_seen} rows, {site_total - distinct} fewer distinct books than the site total",
                             run_id))
        removed = 0
        at = now()
        if ok:
            marks = ",".join("?" * len(sections))
            removed = con.execute(f"UPDATE holding SET removed_at=? WHERE library_id=? AND last_run_id<>? "
                                  f"AND removed_at IS NULL AND coalesce(section, 'MAIN') IN ({marks})",
                                  (at, lib["id"], run_id, *sections)).rowcount
            if whole:
                con.execute("UPDATE library SET last_full_success_at=?, last_full_run_id=? WHERE id=?",
                            (at, run_id, lib["id"]))
        con.execute("UPDATE crawl_run SET status=?, finished_at=?, distinct_items=?, removed_marked=? WHERE id=?",
                    ("SUCCEEDED" if ok else "MISMATCH", at, distinct, removed, run_id))
        con.commit()
    log(f"[{lib['id']}] {lib['name']}: {'OK' if ok else 'MISMATCH'} {distinct}/{site_total}, removed {removed}")


def full(con, workers, only, limit_pages, platforms=None, retry_failed=False, big_subscription=False,
         sections=None, shard=None, audio_pass=False):
    con.row_factory = sqlite3.Row
    libs = [dict(r) for r in con.execute("SELECT * FROM library ORDER BY coalesce(seed_holdings_count,0) DESC")]
    latest = {r[0]: r[1] for r in con.execute(
        "SELECT library_id, status FROM crawl_run r WHERE id=(SELECT max(id) FROM crawl_run WHERE library_id=r.library_id)")}
    con.row_factory = None
    if only:
        libs = [lb for lb in libs if lb["id"] in only]
    skipped_libs = [lb for lb in libs if lb.get("crawl_skip")]
    libs = [lb for lb in libs if not lb.get("crawl_skip")]
    for lb in skipped_libs:
        log(f"[{lb['id']}] {lb['name']}: not crawled ({lb['crawl_skip']})")
    blocked = [lb for lb in libs if is_blocked_host(lb["base_url"])]
    libs = [lb for lb in libs if not is_blocked_host(lb["base_url"])]
    if blocked:
        log(f"not crawled: {len(blocked)} libraries on *.{BLOCKED_HOST_SUFFIX} (KYOBO subscription server, R1-6)")
    if platforms:
        libs = [lb for lb in libs if lb["platform_type"] in platforms]
    if retry_failed:
        # not finished = latest run did not succeed, or no full (all-lists) run has ever succeeded
        keep = [lb for lb in libs if latest.get(lb["id"]) in ("FAILED", "MISMATCH", "RUNNING", None)
                or not lb.get("last_full_success_at")]
        if audio_pass:  # succeeded YES24 sites still need their audiobook pass
            keep += [lb for lb in libs if lb not in keep and lb["platform_type"].startswith("YES24")]
        libs = keep
    if not big_subscription and not only:
        libs = [lb for lb in libs if not (lb["platform_type"] == "KYOBO_SUBSCRIPTION_V1"
                                          and (lb["seed_holdings_count"] or 0) >= BIG_SUBSCRIPTION_MIN)]
    def priority(lb):
        """1 = dev seed (278), 2 = big KYOBO subscription, 3 = third-party list (Bookcube ...)."""
        if lb.get("source") not in (None, "dev-seed"):
            return 3
        if lb["platform_type"] == "KYOBO_SUBSCRIPTION_V1" and (lb["seed_holdings_count"] or 0) >= BIG_SUBSCRIPTION_MIN:
            return 2
        return 1

    by_host = defaultdict(list)
    for lb in sorted(libs, key=lambda lb: (priority(lb), -(lb["seed_holdings_count"] or 0))):
        by_host[queue_key(lb["base_url"]) if QUEUE_BY_IP else lb["host"]].append(lb)
    # hosts with priority-1 work first, then the biggest, so the long ones start early
    hosts = sorted(by_host.values(), key=lambda ls: (min(priority(lb) for lb in ls),
                                                     -sum(lb["seed_holdings_count"] or 0 for lb in ls)))
    if shard:
        i, n = shard
        hosts = hosts[i::n]
    log(f"full run: {len(libs)} libraries on {len(hosts)} hosts, {workers} workers")

    def run_host(ls):
        for lb in ls:
            if not (retry_failed and latest.get(lb["id"]) == "SUCCEEDED" and lb.get("last_full_success_at")):
                crawl_library(con, lb, limit_pages, sections or None)
            # YES24 sites finished before the audiobook list existed get that list once (skipped when done)
            if audio_pass and not sections and lb["platform_type"].startswith("YES24"):
                crawl_library(con, lb, limit_pages, {"AUDIO"})

    def split(ls):  # a queue with n requests at once is read as n lists side by side
        n = slots_for(queue_key(ls[0]["base_url"]) if QUEUE_BY_IP else ls[0]["host"])
        return [ls[i::n] for i in range(n) if ls[i::n]]
    hosts = [part for ls in hosts for part in split(ls)]
    if HOST_SLOTS:
        log(f"requests at once per queue: {HOST_SLOTS} (back to 1 if avg > {HOST_SLOW}s or {SPLIT_FAILS} failures)")
    log(f"parallelism: {workers} workers, {PER_HOST} request(s) at a time per host, {MIN_GAP}s gap")
    with ThreadPoolExecutor(max_workers=workers) as ex:
        list(ex.map(run_host, hosts))
    status(con)


REFILL_PLATFORMS = ("YES24_V1", "YES24_V2", "YES24_V3", "BOOKCUBE_FX", "ALADIN_V1")  # lists with a pubdt order


def refill_pubdt(con, ids):
    """Reads each library's main list again in publish-date order and adds the books missing from it (replaces the
    data folder's refill_pubdt.py). Rows go to the library's latest run; nothing is removed. Each library runs on its
    own thread; the politeness rule is the crawler's (one request at a time per host, MIN_GAP apart)."""
    con.row_factory = sqlite3.Row
    libs = {r["id"]: dict(r) for r in con.execute(
        f"SELECT * FROM library WHERE id IN ({','.join('?' * len(ids))})", sorted(ids))}
    con.row_factory = None

    def run(lid):
        lib = libs.get(lid)
        if not lib:
            log(f"[{lid}] no such library in the collected DB")
            return
        if lib.get("crawl_skip") or is_blocked_host(lib["base_url"]) or lib["platform_type"] not in REFILL_PLATFORMS:
            log(f"[{lid}] {lib['name']}: not refilled ({lib.get('crawl_skip') or lib['platform_type']})")
            return
        run_id = con.execute("SELECT max(id) FROM crawl_run WHERE library_id=?", (lid,)).fetchone()[0]
        if run_id is None:
            log(f"[{lid}] {lib['name']}: not refilled (never crawled; run full first)")
            return
        before = con.execute("SELECT count(*) FROM holding WHERE library_id=?", (lid,)).fetchone()[0]
        log(f"[{lid}] {lib['name']}: refill in pubdt order (run {run_id}), {before} rows before")
        try:
            seg = next(sg for sg in segments(lib) if sg[0] == "MAIN")
            fill_other_order(con, lib, run_id, seg)
        except Exception as e:  # noqa: BLE001 - one library's failure does not stop the others
            log(f"[{lid}] {lib['name']}: refill FAILED {e!r}")
            mark_blocked(con, lib, e)
            return
        after = con.execute("SELECT count(*) FROM holding WHERE library_id=?", (lid,)).fetchone()[0]
        log(f"[{lid}] {lib['name']}: refill done, {after} rows after (+{after - before})")

    threads = [threading.Thread(target=run, args=(i,)) for i in sorted(ids)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()


def status(con):
    print("runs by status (latest per library):")
    for s, n in con.execute("""SELECT status, count(*) FROM crawl_run r WHERE id = (SELECT max(id) FROM crawl_run
                               WHERE library_id=r.library_id) GROUP BY status"""):
        print(f"  {s}: {n}")
    print("holdings rows:", con.execute("SELECT count(*) FROM holding WHERE removed_at IS NULL").fetchone()[0])
    print("libraries never succeeded:",
          con.execute("SELECT count(*) FROM library WHERE last_full_success_at IS NULL").fetchone()[0])
    if "crawl_skip" in {r[1] for r in con.execute("PRAGMA table_info(library)")}:  # read-only status: maybe old DB
        print("libraries not crawled (crawl_skip):",
              con.execute("SELECT count(*) FROM library WHERE crawl_skip IS NOT NULL").fetchone()[0])


def set_data_dir(path):
    """Points ROOT and DB at another data folder (--data-dir)."""
    global ROOT, DB
    ROOT = Path(path)
    DB = ROOT / "ebook-catalog.sqlite"


def connect_read_only():
    """For "status": reads the collected DB without schema or migration writes, safe while a crawl is running."""
    con = sqlite3.connect(f"file:{DB.as_posix()}?mode=ro", uri=True, timeout=120, check_same_thread=False)
    con.execute("PRAGMA busy_timeout=120000")
    return con


def main():
    if "fetch-covers" in sys.argv[1:]:
        # book covers after the list crawl (plan request 2026-10-23): its own options, in fetch_covers.py
        import fetch_covers
        sys.exit(fetch_covers.main([x for x in sys.argv[1:] if x != "fetch-covers"]))
    pre = argparse.ArgumentParser(add_help=False)
    pre.add_argument("--data-dir", default="")
    known, _ = pre.parse_known_args()
    if known.data_dir:
        set_data_dir(known.data_dir)
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="", help="data folder (default: $CHAEKCHECK_DATA or ../chaekcheck-data)")
    ap.add_argument("cmd", choices=["init", "full", "status", "import-bookcube", "import-probe", "backfill-types",
                                    "invalidate", "refill-pubdt", "add-library", "fetch-covers"])
    ap.add_argument("--source", default="okbari-2026-10-03", help="library.source label for import-probe")
    ap.add_argument("--reason", default="")
    ap.add_argument("--skip", nargs=2, metavar=("ID", "REASON"), help="do not crawl this library; REASON '' clears it")
    ap.add_argument("--probe", default=str(ROOT / "other-platforms" / "probe-2026-09-30.tsv"))
    ap.add_argument("--workers", type=int, default=16, help="servers crawled at the same time")
    ap.add_argument("--per-host", type=int, default=1,
                    help="requests at once on one server (default 1 = agreed politeness rule; raise only by decision)")
    ap.add_argument("--gap", type=float, default=1.0, help="seconds between requests to one server (default 1.0)")
    ap.add_argument("--queue-by-ip", action="store_true", help="one queue per IP instead of per host name")
    ap.add_argument("--host-slots", default="", help="KEY=N,...: requests at once for these queues (IP or host)")
    ap.add_argument("--only", default="")
    ap.add_argument("--limit-pages", type=int, default=None)
    ap.add_argument("--platforms", default="", help="comma-separated platform_type values")
    ap.add_argument("--retry-failed", action="store_true", help="only libraries whose latest run did not succeed")
    ap.add_argument("--include-big-subscription", action="store_true",
                    help="no effect since cycle 13: those sites are all on *.dkyobobook.co.kr, which is never requested")
    ap.add_argument("--sections", default="", help="only these lists, e.g. AUDIO (no removal outside them)")
    ap.add_argument("--shard", default="", help="i/n: this process takes every n-th host starting at i")
    ap.add_argument("--audio-pass", action="store_true", help="also fetch YES24 /audiobook/ lists not yet fetched")
    ap.add_argument("--set-count", nargs=2, metavar=("ID", "COUNT"), help="store a catalogue size seen on the site")
    ap.add_argument("--id", type=int, help="add-library: the collection id (above 100000), as in the rules files")
    ap.add_argument("--name", help="add-library: the library's name")
    ap.add_argument("--platform", help="add-library: platform_type")
    ap.add_argument("--base-url", help="add-library: the site root the crawler appends its paths to")
    ap.add_argument("--vendor", default=None, help="add-library: vendor when it differs from the platform_type mapping")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    global PER_HOST, MIN_GAP
    PER_HOST, MIN_GAP = max(1, a.per_host), max(0.0, a.gap)
    global QUEUE_BY_IP
    QUEUE_BY_IP = a.queue_by_ip
    for part in filter(None, a.host_slots.split(",")):
        k, _, n = part.partition("=")
        HOST_SLOTS[k.strip()] = max(1, int(n))
    if QUEUE_BY_IP:
        log("queues by IP: one queue per IP; shared servers fall back to one queue if an IP gets slow or fails")
    if a.cmd == "status" and not (a.skip or a.set_count):
        status(connect_read_only())
        return
    con = connect()
    if a.skip:
        con.execute("UPDATE library SET crawl_skip=? WHERE id=?", (a.skip[1] or None, int(a.skip[0])))
        con.commit()
        print("crawl_skip set", a.skip)
    if a.set_count:
        con.execute("UPDATE library SET seed_holdings_count=? WHERE id=?", (int(a.set_count[1]), int(a.set_count[0])))
        con.commit()
    if a.cmd == "init":
        init(con)
    elif a.cmd == "import-bookcube":
        import_bookcube(con, a.probe, a.source)
    elif a.cmd == "import-probe":
        import_probe(con, a.probe, a.source)
    elif a.cmd == "add-library":
        if not (a.id and a.name and a.platform and a.base_url):
            ap.error("add-library needs --id, --name, --platform and --base-url")
        add_library(con, a.id, a.name, a.platform, a.base_url, a.source, a.vendor)
    elif a.cmd == "invalidate":
        plats = [x for x in a.platforms.split(",") if x]
        ids = [r[0] for r in con.execute(
            f"SELECT id FROM library WHERE platform_type IN ({','.join('?' * len(plats))})", plats)]
        for i in ids:
            con.execute("INSERT INTO crawl_run (library_id, kind, status, started_at, finished_at, page_size, user_agent, "
                        "error) VALUES (?, 'FULL', 'FAILED', ?, ?, 0, ?, ?)",
                        (i, now(), now(), USER_AGENT, "invalidated: " + a.reason))
        con.commit()
        print(f"invalidated {len(ids)} libraries")
    elif a.cmd == "refill-pubdt":
        ids = {int(x) for x in a.only.split(",") if x}
        if not ids:
            ap.error("refill-pubdt needs --only ID[,ID...]")
        refill_pubdt(con, ids)
    elif a.cmd == "backfill-types":
        print("rows typed:", backfill_types(con))
    elif a.cmd == "full":
        full(con, a.workers, {int(x) for x in a.only.split(",") if x}, a.limit_pages,
             {x for x in a.platforms.split(",") if x}, a.retry_failed, a.include_big_subscription,
             {x for x in a.sections.split(",") if x},
             tuple(int(x) for x in a.shard.split("/")) if a.shard else None, a.audio_pass)
    else:
        status(con)


if __name__ == "__main__":
    main()
