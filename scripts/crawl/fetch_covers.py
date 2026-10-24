"""Book covers: one 200px JPEG per service book, collected after the catalogue crawl (plan request
to-dev-fetch-covers-2026-10-23.md; crawl-method.md 1절 "표지"; decisions easy/2026-10-23-cover-collection-decisions.html,
easy/2026-10-23-cover-source-decisions.html).

Runs apart from the list crawl (`full`) and reads the collected DB once that crawl is done:

  python -X utf8 scripts/crawl/crawl_catalog.py fetch-covers [--workers 32] [--limit N] [--max-books N]
  python -X utf8 scripts/crawl/crawl_catalog.py fetch-covers --status
  python -X utf8 scripts/crawl/crawl_catalog.py fetch-covers --export

One run does two steps (each can be asked alone with --plan-only / --fetch-only):

1. Plan: the service books of the PC's PostgreSQL (catalog.book, catalog.holding, collection.collection_link; SELECT
   only) are read once; every book gets the cover addresses of its holdings from the collected SQLite (holding.cover_url),
   in the order of fetch_cover_hosts.tsv (YES24, Bookcube, then the other servers), never a *.dkyobobook.co.kr address
   (cycle 13 R1-6), at most MAX_COLLECTED of them, and last the National Library of Korea lookups ('nlk': the ISBN
   bibliography's TITLE_URL when the book has an ISBN, then the holdings search's imageUrl on cover.nl.go.kr). The plan
   is the table book_cover of <data>/covers/covers.sqlite. A book is known by a stable key (its ISBN, else the
   collection library id and item key of its first holding), so a catalogue re-import, which renumbers books, does not
   make the covers be taken again: the plan only refreshes the book ids.
2. Fetch: every server (IP) gets one request at a time with a 1 second gap, different servers at the same time (the
   crawl's queue-by-ip rule); the National Library of Korea's API and cover server are one queue. The first address that
   gives a picture is made 200px wide, JPEG quality 0.8 (ADR-0091, recommended_lists.py COVER_WIDTH); an original over
   5MB is not read; a "no image" picture (cover_placeholders.tsv hashes) is skipped for the next address. Files go to
   <data>/covers/<first two hex of sha1(url)>/<sha1(url)>.jpg (the same address gives one file, however many books share
   it); the row of book_cover says which file a book has. Resumable: a book with a cover (DONE) or without any
   (NONE, every address tried) is not asked again; a stopped run leaves PENDING rows at their next address.

The service tables, the import and the screens are not touched (decision: showing covers is a later cycle).
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import crawl_catalog as cc  # noqa: E402  the crawl's politeness pieces: resolve, is_blocked_host, SSL, USER_AGENT
import recommended_lists as rl  # noqa: E402  cycle 21 cover code: shrink, image_hash, placeholders, Seoji, NlkHoldings

HERE = Path(__file__).resolve().parent
LISTS_DIR = HERE / 'lists'
NLK_KEY = 'nl.go.kr'          # one queue for the National Library of Korea: its API (www) and its cover server (cover)
NLK = 'nlk'                   # the pseudo address at the end of a book's candidates: the National Library lookups
MAX_COLLECTED = 6             # rules-index.md: 표지 후보 수 — collected addresses tried per book, at most
GAP = 1.0                     # seconds between requests to one server (the crawl's MIN_GAP)
TIMEOUT = 30
NLK_HOLDINGS_LIMIT = 3        # rules-index.md: 표지 서버 끄기 — cover.nl.go.kr addresses tried per book, at most
OFF_AFTER = 50                # rules-index.md: 표지 서버 끄기 — failures in a row that stop asking a server this run
SLOW = 12.0                   # rules-index.md: 표지 서버 끄기 — seconds (moving average) that stop asking a slow server
SLOW_AFTER = 30               # requests on a server before SLOW is judged (the crawl's SPLIT_SLOW rule)
BUSY_RECHECK = 600            # seconds between checks of the servers a running list crawl still uses
SOURCES = ('collected_preferred', 'collected_other', 'nlk_isbn', 'nlk_holdings')

SCHEMA = """
CREATE TABLE IF NOT EXISTS book_cover (
  book_key TEXT PRIMARY KEY,         -- isbn:<isbn13>, else item:<collection library id>:<item key> of the first holding
  book_id INTEGER NOT NULL,          -- catalog.book.id when planned (renumbered by every import; see meta)
  isbn13 TEXT,
  title TEXT,
  author TEXT,
  candidates TEXT NOT NULL,          -- JSON list of addresses in order; 'nlk' at the end = National Library lookups
  next_index INTEGER NOT NULL DEFAULT 0,
  next_host TEXT,                    -- host of candidates[next_index] ('nl.go.kr' for nlk); NULL when finished
  status TEXT NOT NULL CHECK (status IN ('PENDING', 'DONE', 'NONE')),
  source TEXT,                       -- collected_preferred / collected_other / nlk_isbn / nlk_holdings
  cover_url TEXT,
  file TEXT,                         -- covers/<xx>/<sha1>.jpg, relative to the data folder
  bytes INTEGER,
  tried INTEGER NOT NULL DEFAULT 0,  -- requests made for this book
  last_error TEXT,
  planned_at TEXT NOT NULL,
  fetched_at TEXT
);
CREATE INDEX IF NOT EXISTS book_cover_next ON book_cover(status, next_host, book_id);
CREATE INDEX IF NOT EXISTS book_cover_book ON book_cover(book_id);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE IF NOT EXISTS run (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  started_at TEXT NOT NULL,
  finished_at TEXT,
  requests INTEGER NOT NULL DEFAULT 0,
  done INTEGER NOT NULL DEFAULT 0,
  none INTEGER NOT NULL DEFAULT 0,
  bytes INTEGER NOT NULL DEFAULT 0,
  note TEXT
);
"""


def now() -> str:
    return dt.datetime.now().astimezone().isoformat(timespec='seconds')


class Log:
    def __init__(self, path: Path | None):
        self.path = path
        self.lock = threading.Lock()
        if path:
            path.parent.mkdir(parents=True, exist_ok=True)

    def line(self, text: str) -> None:
        stamp = dt.datetime.now().strftime('%H:%M:%S')
        with self.lock:
            try:
                print(f'{stamp} {text}', flush=True)
            except UnicodeEncodeError:
                print(f'{stamp} {text}'.encode('ascii', 'replace').decode('ascii'), flush=True)
            if self.path:
                with self.path.open('a', encoding='utf-8') as f:
                    f.write(f'{stamp} {text}\n')


# ---------------------------------------------------------------- rules files

_order: tuple[tuple[str, ...], tuple[str, ...]] | None = None


def load_order(lists_dir: Path = LISTS_DIR) -> tuple[tuple[str, ...], tuple[str, ...]]:
    """(preferred hosts in order, sources in order) from fetch_cover_hosts.tsv and fetch_cover_sources.tsv."""
    header, hosts = rl.read_tsv(lists_dir / 'fetch_cover_hosts.tsv')
    if header[:2] != ['order', 'host']:
        raise rl.CollectorFailure(f'{lists_dir / "fetch_cover_hosts.tsv"}: missing or without the columns order, host')
    hosts.sort(key=lambda h: int(h['order']))
    header, sources = rl.read_tsv(lists_dir / 'fetch_cover_sources.tsv')
    if header[:2] != ['order', 'source']:
        raise rl.CollectorFailure(f'{lists_dir / "fetch_cover_sources.tsv"}: missing or without the columns order, source')
    unknown = [s['source'] for s in sources if s['source'] not in SOURCES]
    if unknown:
        raise rl.CollectorFailure(f'fetch_cover_sources.tsv: unknown source {unknown[0]} (known: {", ".join(SOURCES)})')
    sources.sort(key=lambda s: int(s['order']))
    return tuple(h['host'] for h in hosts), tuple(s['source'] for s in sources)


def order() -> tuple[tuple[str, ...], tuple[str, ...]]:
    global _order
    if _order is None:
        _order = load_order()
    return _order


# ---------------------------------------------------------------- the plan

def host_of(url: str) -> str | None:
    if url == NLK:
        return NLK_KEY
    return (urllib.parse.urlsplit(url).hostname or '').lower() or None


def candidates_of(urls: list[str], preferred: tuple[str, ...], sources: tuple[str, ...], has_isbn: bool,
                  nlk: bool) -> list[str]:
    """
    A book's addresses in the order they are tried: the distinct collected addresses, never a *.dkyobobook.co.kr one,
    preferred hosts first (in their order), then the others as collected, at most MAX_COLLECTED; then 'nlk' when the
    National Library of Korea is asked (a key is there and a source names it). The sources order decides which blocks
    come and in what order (collected_preferred, collected_other, nlk_*); 'nlk' is always last (request order ① → ②).
    """
    seen: set[str] = set()
    collected = []
    for url in urls:
        if not url or not url.startswith(('http://', 'https://')) or url in seen or cc.is_blocked_host(url):
            continue
        seen.add(url)
        collected.append(url)
    rank = {host: i for i, host in enumerate(preferred)}
    blocks: dict[str, list[str]] = {
        'collected_preferred': sorted((u for u in collected if host_of(u) in rank), key=lambda u: rank[host_of(u)]),
        'collected_other': [u for u in collected if host_of(u) not in rank],
        'nlk_isbn': [NLK] if nlk and has_isbn else [],
        'nlk_holdings': [NLK] if nlk else [],
    }
    out: list[str] = []
    ask_nlk = False
    for source in sources:
        for url in blocks[source]:
            if url == NLK:
                ask_nlk = True
            elif len(out) < MAX_COLLECTED:
                out.append(url)
    return out + ([NLK] if ask_nlk else [])


def book_key(isbn13: str | None, first_holding: tuple[int, str] | None) -> str:
    if isbn13:
        return f'isbn:{isbn13}'
    if first_holding:
        return f'item:{first_holding[0]}:{first_holding[1]}'
    return 'none'


def open_covers_db(path: Path) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(path, timeout=120, check_same_thread=False)
    con.execute('PRAGMA journal_mode=WAL')
    con.execute('PRAGMA busy_timeout=120000')
    con.executescript(SCHEMA)
    return con


class BookSource:
    """The service books with their holdings' collection keys, streamed in book id order (SELECT only)."""

    def __init__(self, connection):
        self.db = connection

    @staticmethod
    def connect(env: dict[str, str], port: str | None):
        import psycopg
        return BookSource(psycopg.connect(host='localhost', port=port or env.get('DB_PORT', '5432'),
                                          dbname=env['DB_NAME'], user=env['DB_USERNAME'],
                                          password=env.get('DB_PASSWORD', '')))  # a transaction: the server-side cursor needs one

    def latest_import(self) -> str:
        with self.db.cursor() as cur:
            cur.execute("SELECT id, imported_at, book_count FROM collection.catalog_import WHERE status = 'SUCCEEDED' "
                        "ORDER BY id DESC LIMIT 1")
            row = cur.fetchone()
        return f'catalog_import {row[0]} at {row[1].isoformat()} ({row[2]} books)' if row else 'no catalog_import row'

    def books(self, limit: int | None = None):
        """Yields (book_id, isbn13, title, author, [(collection_library_id, item_key), ...] in holding id order)."""
        sql = """
            SELECT b.id, b.isbn13, b.title, b.author_display, l.collection_library_id, h.item_key
            FROM catalog.book b
            JOIN catalog.holding h ON h.book_id = b.id
            JOIN collection.collection_link l ON l.library_id = h.library_id
            """ + ('WHERE b.id <= (SELECT min(id) FROM catalog.book) + %s - 1 ' if limit else '') + \
              'ORDER BY b.id, h.id'
        try:
            with self.db.cursor(name='fetch_covers_books') as cur:
                cur.itersize = 20000
                cur.execute(sql, (limit,) if limit else None)
                current = None
                keys: list[tuple[int, str]] = []
                for book_id, isbn13, title, author, lib, item in cur:
                    if current is not None and book_id != current[0]:
                        yield current[0], current[1], current[2], current[3], keys
                        keys = []
                    current = (book_id, isbn13, title, author)
                    keys.append((int(lib), item))
                if current is not None:
                    yield current[0], current[1], current[2], current[3], keys
        finally:
            self.db.rollback()  # SELECT only: the read transaction ends here


def plan(covers: sqlite3.Connection, collected: sqlite3.Connection, source: BookSource, log: Log, nlk: bool,
         limit: int | None = None, lists_dir: Path = LISTS_DIR) -> dict[str, int]:
    """
    Writes or refreshes book_cover: a new book key gets its candidates and PENDING; a known key keeps its row (its cover
    or its place in the queue) and only gets the new book id, title, author and ISBN.
    """
    preferred, sources = load_order(lists_dir)
    log.line(f'plan: hosts first {list(preferred)}, sources {list(sources)}, '
             f'National Library {"asked" if nlk else "not asked (no key)"}')
    stamp = now()
    counts = {'books': 0, 'new': 0, 'known': 0, 'no_candidates': 0}
    new_rows: list[tuple] = []
    known_rows: list[tuple] = []
    known_keys = {row[0] for row in covers.execute('SELECT book_key FROM book_cover')}
    lookup = collected.execute

    def flush():
        if new_rows:
            covers.executemany("""
                INSERT INTO book_cover (book_key, book_id, isbn13, title, author, candidates, next_index, next_host,
                  status, planned_at) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)""", new_rows)
            new_rows.clear()
        if known_rows:
            covers.executemany('UPDATE book_cover SET book_id = ?, title = ?, author = ?, isbn13 = ? WHERE book_key = ?',
                               known_rows)
            known_rows.clear()
        covers.commit()

    started = time.monotonic()
    for book_id, isbn13, title, author, keys in source.books(limit):
        counts['books'] += 1
        key = book_key(isbn13, keys[0] if keys else None)
        if key in known_keys:
            counts['known'] += 1
            known_rows.append((book_id, title, author, isbn13, key))
        else:
            known_keys.add(key)
            urls = []
            for lib, item in keys:
                row = lookup('SELECT cover_url FROM holding WHERE library_id = ? AND item_key = ?', (lib, item)).fetchone()
                if row and row[0]:
                    urls.append(row[0].strip())
            cands = candidates_of(urls, preferred, sources, bool(isbn13), nlk)
            counts['new'] += 1
            if cands:
                new_rows.append((key, book_id, isbn13, title, author, json.dumps(cands), host_of(cands[0]), 'PENDING',
                                 stamp))
            else:
                counts['no_candidates'] += 1
                new_rows.append((key, book_id, isbn13, title, author, '[]', None, 'NONE', stamp))
        if len(new_rows) + len(known_rows) >= 5000:
            flush()
        if counts['books'] % 100000 == 0:
            log.line(f"plan: {counts['books']} books read, {counts['new']} new, {time.monotonic() - started:.0f}s")
    flush()
    covers.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('planned_from', ?)", (source.latest_import(),))
    covers.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('planned_at', ?)", (stamp,))
    covers.commit()
    log.line(f"plan: {counts['books']} books, {counts['new']} new ({counts['no_candidates']} without any address: NONE), "
             f"{counts['known']} already planned, {time.monotonic() - started:.0f}s")
    return counts


# ---------------------------------------------------------------- politeness: one request at a time per server

def queue_key_of(host: str) -> str:
    """The queue a host's requests wait in: the National Library as one, else the host's IP (the crawl's queue-by-ip)."""
    if host == NLK_KEY or host.endswith('.' + NLK_KEY):
        return NLK_KEY
    return cc.resolve(host) or host


class Dispatcher:
    """
    Hands hosts with pending books to the workers so that one server (queue key) has one request in flight and the
    next starts GAP after the previous one ended; hosts on different servers go at the same time.
    """

    def __init__(self, pending: dict[str, int], key_of=queue_key_of, gap: float = GAP, clock=time.monotonic):
        self.pending = {h: n for h, n in pending.items() if n > 0}
        self.key_of = key_of
        self.gap = gap
        self.clock = clock
        self.busy: set[str] = set()
        self.next_ok: dict[str, float] = {}
        self.off: set[str] = set()
        self.paused: set[str] = set()  # servers a running list crawl still uses (checked again, may be freed)
        self.cond = threading.Condition()
        self.stopped = False

    def take(self) -> str | None:
        """A host to take one pending book from, or None when nothing is left (or the run was stopped)."""
        with self.cond:
            while True:
                if self.stopped:
                    return None
                clock = self.clock()
                soonest = None
                left = False
                for host, n in self.pending.items():
                    if n <= 0:
                        continue
                    key = self.key_of(host)
                    if key in self.off or key in self.paused:
                        continue
                    left = True
                    if key in self.busy:
                        continue
                    ready_at = self.next_ok.get(key, 0.0)
                    if ready_at <= clock:
                        self.busy.add(key)
                        return host
                    soonest = ready_at if soonest is None else min(soonest, ready_at)
                if not left and not self.busy:
                    return None
                self.cond.wait(timeout=None if soonest is None else max(0.0, soonest - clock))

    def done(self, host: str, moved_to: str | None, took_one: bool = True) -> None:
        with self.cond:
            if took_one:
                self.pending[host] = self.pending.get(host, 0) - 1
            if moved_to:
                self.pending[moved_to] = self.pending.get(moved_to, 0) + 1
            key = self.key_of(host)
            self.busy.discard(key)
            self.next_ok[key] = self.clock() + self.gap
            self.cond.notify_all()

    def vanished(self, host: str) -> None:
        """The counts said a host had pending books but the table has none: the count is dropped."""
        with self.cond:
            self.pending[host] = 0
            self.busy.discard(self.key_of(host))
            self.cond.notify_all()

    def turn_off(self, host: str) -> None:
        with self.cond:
            self.off.add(self.key_of(host))
            self.cond.notify_all()

    def pause(self, keys: set[str]) -> int:
        """The servers not asked now (a running crawl uses them); returns the pending books waiting on them."""
        with self.cond:
            self.paused = set(keys)
            self.cond.notify_all()
            return sum(n for h, n in self.pending.items() if n > 0 and self.key_of(h) in self.paused)

    def stop(self) -> None:
        with self.cond:
            self.stopped = True
            self.cond.notify_all()

    def pending_total(self) -> int:
        with self.cond:
            return sum(n for n in self.pending.values() if n > 0)


class TooLarge(Exception):
    pass


class Http:
    """GET with the crawl's User-Agent; the caller holds the server's turn. The key never reaches a log line."""

    def __init__(self, log: Log, secrets: list[str], opener=None):
        self.log = log
        self.secrets = [s for s in secrets if s]
        self.opener = opener or urllib.request.urlopen
        self.requests = 0
        self.lock = threading.Lock()

    def hide(self, text: str) -> str:
        for secret in self.secrets:
            text = text.replace(secret, '***')
        return text

    def get(self, url: str, max_bytes: int | None = None) -> tuple[int, str, bytes]:
        """(status, content type, body). Raises TooLarge over max_bytes (from Content-Length or while reading)."""
        if cc.is_blocked_host(url):
            raise cc.BlockedHost(f'not requested (KYOBO subscription server, spec R1-6): {host_of(url)}')
        with self.lock:
            self.requests += 1
        host = host_of(url) or ''
        verified = host == NLK_KEY or host.endswith('.' + NLK_KEY)
        request = urllib.request.Request(url, headers={'User-Agent': cc.USER_AGENT})
        try:
            with self.opener(request, timeout=TIMEOUT, context=None if verified else cc.SSL) as response:
                length = response.headers.get('Content-Length')
                if max_bytes and length and length.isdigit() and int(length) > max_bytes:
                    raise TooLarge(f'{host}: Content-Length {length}')
                body = response.read(max_bytes + 1) if max_bytes else response.read()
                if max_bytes and len(body) > max_bytes:
                    raise TooLarge(f'{host}: larger than {max_bytes} bytes')
                return response.status, response.headers.get('Content-Type', ''), body
        except urllib.error.HTTPError as ex:
            return ex.code, '', b''

    def json(self, url: str, params: dict) -> dict:
        """What rl.Seoji and rl.NlkHoldings call; a non-JSON or non-200 answer is a CollectorFailure."""
        try:
            status, _, body = self.get(url + '?' + urllib.parse.urlencode(params))
        except (urllib.error.URLError, TimeoutError, OSError, TooLarge) as ex:
            raise rl.CollectorFailure(self.hide(f'{host_of(url)}: no answer ({ex.__class__.__name__})')) from None
        try:
            data = json.loads(body.decode('utf-8'))
        except ValueError:
            raise rl.CollectorFailure(f'{host_of(url)}: HTTP {status}, not JSON') from None
        if status != 200:
            raise rl.CollectorFailure(f'{host_of(url)}: HTTP {status}')
        return data


def cover_path(data_dir: Path, url: str) -> tuple[str, Path]:
    """(relative name kept in the table, absolute path): covers/<xx>/<sha1(url)>.jpg."""
    digest = hashlib.sha1(url.encode('utf-8')).hexdigest()
    rel = f'covers/{digest[:2]}/{digest}.jpg'
    return rel, data_dir / 'covers' / digest[:2] / f'{digest}.jpg'


class Fetcher:
    """The fetch step: workers take hosts from the Dispatcher and try one book's next address each."""

    def __init__(self, data_dir: Path, covers_path: Path, http: Http, log: Log, nlk_key: str, rules: rl.Rules,
                 gap: float = GAP, sleep=time.sleep, max_books: int | None = None, retry_none: bool = False,
                 busy_source=None):
        self.data_dir = data_dir
        self.covers_path = covers_path
        self.http = http
        self.log = log
        self.nlk_key = nlk_key
        self.rules = rules
        self.gap = gap
        self.sleep = sleep
        self.max_books = max_books
        self.stats = {'done': 0, 'none': 0, 'bytes': 0, 'moved': 0}
        self.by_source: dict[str, int] = {}
        self.failures: dict[str, int] = {}  # host -> consecutive failures
        self.timing: dict[str, tuple[float, int]] = {}  # queue key -> (moving average seconds, requests)
        self.busy_source = busy_source  # () -> IPs a running list crawl still uses; None = no crawl
        self.lock = threading.Lock()
        self.dispatcher: Dispatcher | None = None
        if retry_none:
            con = open_covers_db(covers_path)
            rows = con.execute("SELECT book_key, candidates FROM book_cover WHERE status = 'NONE' "
                               "AND json_array_length(candidates) > 0").fetchall()
            con.executemany("UPDATE book_cover SET status = 'PENDING', next_index = 0, next_host = ? WHERE book_key = ?",
                            [(host_of(json.loads(c)[0]), k) for k, c in rows])
            con.commit()
            con.close()
            log.line(f'retry: {len(rows)} books without a cover are asked again from their first address')

    def pending_by_host(self) -> dict[str, int]:
        con = open_covers_db(self.covers_path)
        try:
            return {h: n for h, n in con.execute(
                "SELECT next_host, count(*) FROM book_cover WHERE status = 'PENDING' AND next_host IS NOT NULL "
                "GROUP BY next_host")}
        finally:
            con.close()

    def run(self, workers: int) -> dict:
        pending = self.pending_by_host()
        self.log.line(f'fetch: {sum(pending.values())} books pending on {len(pending)} hosts; resolving hosts')
        keys = {queue_key_of(h) for h in pending}  # DNS once per host, before the threads start
        self.log.line(f'fetch: {len(keys)} servers (queues), {workers} workers')
        self.dispatcher = Dispatcher(pending, gap=self.gap)
        self.refresh_busy()
        threads = [threading.Thread(target=self.worker, name=f'cover-{i}', daemon=True) for i in range(workers)]
        for t in threads:
            t.start()
        started = time.monotonic()
        last_report = last_busy = started
        try:
            while any(t.is_alive() for t in threads):
                for t in threads:
                    t.join(timeout=1.0)
                if time.monotonic() - last_report >= 60:
                    last_report = time.monotonic()
                    self.report(started)
                if self.busy_source and time.monotonic() - last_busy >= BUSY_RECHECK:
                    last_busy = time.monotonic()
                    self.refresh_busy()
        except KeyboardInterrupt:
            self.log.line('fetch: stopping (Ctrl+C); the request in flight finishes, the rest stays PENDING')
            self.dispatcher.stop()
            for t in threads:
                t.join(timeout=TIMEOUT + 5)
        self.report(started)
        return dict(self.stats, elapsed=time.monotonic() - started, requests=self.http.requests,
                    by_source=dict(self.by_source), pending=self.dispatcher.pending_total())

    def refresh_busy(self) -> None:
        """Pauses the servers (IPs) a running list crawl still uses, so no server gets two requests at once (user
        2026-10-24: "조회 아이피 시간간격 1초 지키고 여러개 한다고 어기지 마")."""
        if not self.busy_source:
            return
        try:
            ips = self.busy_source()
        except Exception as ex:  # noqa: BLE001 - without the list, keep the servers paused as they were
            self.log.line(f'busy servers: check failed ({ex.__class__.__name__}); the paused ones stay paused')
            return
        if not ips and self.dispatcher.paused:
            self.log.line('busy servers: the list crawl has finished; every server is asked')
        waiting = self.dispatcher.pause(ips)
        if ips:
            self.log.line(f'busy servers: {len(ips)} servers the list crawl still uses are skipped '
                          f'({waiting} books wait on them); checked again in {BUSY_RECHECK // 60} min')
        else:
            self.busy_source = None

    def report(self, started: float) -> None:
        elapsed = max(1.0, time.monotonic() - started)
        with self.lock:
            s = dict(self.stats)
            by = ', '.join(f'{k} {v}' for k, v in sorted(self.by_source.items()))
        self.log.line(f"progress: {s['done']} covers ({by}), {s['none']} without, {self.http.requests} requests "
                      f"({self.http.requests / elapsed:.1f}/s), {s['bytes'] / 1048576:.1f} MB, "
                      f"{self.dispatcher.pending_total() if self.dispatcher else 0} pending, {elapsed / 60:.0f} min")

    def worker(self) -> None:
        con = open_covers_db(self.covers_path)
        try:
            while True:
                host = self.dispatcher.take()
                if host is None:
                    return
                row = con.execute("""SELECT book_key, book_id, isbn13, title, author, candidates, next_index, tried
                                     FROM book_cover WHERE status = 'PENDING' AND next_host = ?
                                     ORDER BY book_id LIMIT 1""", (host,)).fetchone()
                if row is None:
                    self.dispatcher.vanished(host)
                    continue
                try:
                    moved_to = self.process(con, host, row)
                except Exception as ex:  # noqa: BLE001 - one odd book must not stop the run
                    self.log.line(self.http.hide(f'book {row[1]}: unexpected {ex.__class__.__name__}: {ex}'))
                    moved_to = self.advance(con, row, f'unexpected {ex.__class__.__name__}')
                self.dispatcher.done(host, moved_to)
                if self.max_books is not None:
                    with self.lock:
                        finished = self.stats['done'] + self.stats['none']
                    if finished >= self.max_books:
                        self.dispatcher.stop()
        finally:
            con.close()

    def process(self, con: sqlite3.Connection, host: str, row) -> str | None:
        """Tries the book's next address; returns the host its next pending address is on (None when finished)."""
        book_key_, book_id, isbn13, title, author, candidates_json, index, tried = row
        candidates = json.loads(candidates_json)
        if index >= len(candidates):
            self.finish_none(con, book_key_, 'no address left')
            return None
        url = candidates[index]
        if url == NLK:
            return self.process_nlk(con, row)
        asked = time.monotonic()
        try:
            outcome, picture = self.take_picture(url)
        except cc.BlockedHost as ex:
            outcome, picture = str(ex), None
        self.note_timing(host, time.monotonic() - asked)
        if picture:
            source = 'collected_preferred' if host_of(url) in order()[0] else 'collected_other'
            self.finish_done(con, book_key_, url, source, picture, tried + 1)
            self.note_failure(host, False)
            return None
        self.note_failure(host, outcome != 'HTTP 404' and not outcome.startswith('no image'))
        return self.advance(con, row, outcome)

    def take_picture(self, url: str) -> tuple[str, bytes | None]:
        """('ok', the 200px JPEG) or (why not, None); one request."""
        try:
            status, _, body = self.http.get(url, rl.COVER_MAX_BYTES)
        except TooLarge:
            return 'over 5MB', None
        except (urllib.error.URLError, TimeoutError, OSError) as ex:
            return f'no answer ({ex.__class__.__name__})', None
        if status != 200:
            return f'HTTP {status}', None
        try:
            small = rl.shrink(body)
        except Exception as ex:  # noqa: BLE001 - Pillow raises its own kinds for a broken file
            return f'not an image ({ex.__class__.__name__})', None
        if self.rules.is_placeholder(small):
            return 'no image picture', None
        return 'ok', small

    def process_nlk(self, con: sqlite3.Connection, row) -> str | None:
        """The National Library of Korea lookups, all in one turn of its queue, GAP between the requests."""
        book_key_, book_id, isbn13, title, author, _, index, tried = row
        seoji = rl.Seoji(self.http, self.nlk_key)
        attempts = 0
        errors = []

        def picture_of(url: str, source: str) -> bool:
            nonlocal attempts
            self.sleep(self.gap)
            attempts += 1
            outcome, picture = self.take_picture(url)
            if picture:
                self.finish_done(con, book_key_, url, source, picture, tried + attempts)
                return True
            errors.append(f'{source}: {outcome}')
            return False

        sources = order()[1]
        if isbn13 and 'nlk_isbn' in sources:
            attempts += 1
            try:
                url = seoji.cover_url(isbn13)
            except rl.CollectorFailure as ex:
                url = None
                errors.append(f'nlk_isbn lookup: {ex}')
            if url and picture_of(url, 'nlk_isbn'):
                self.note_failure(NLK_KEY, False)
                return None
            if not url and not errors:
                errors.append('nlk_isbn: no TITLE_URL')
        if 'nlk_holdings' in sources:
            if attempts:
                self.sleep(self.gap)
            attempts += 1
            try:
                urls = rl.NlkHoldings(self.http, self.nlk_key).covers(isbn13, title, author, limit=NLK_HOLDINGS_LIMIT)
            except rl.CollectorFailure as ex:
                urls = []
                errors.append(f'nlk_holdings lookup: {ex}')
            for url in urls:
                if picture_of(url, 'nlk_holdings'):
                    self.note_failure(NLK_KEY, False)
                    return None
            if not urls and not any(e.startswith('nlk_holdings') for e in errors):
                errors.append('nlk_holdings: no imageUrl')
        self.note_failure(NLK_KEY, any('lookup' in e and 'no answer' in e for e in errors))
        return self.advance(con, row, '; '.join(errors) or 'National Library has none', count=attempts)

    def advance(self, con: sqlite3.Connection, row, error: str, count: int = 1) -> str | None:
        """The next address, or NONE when there is none; returns the host of the next address."""
        book_key_, book_id, isbn13, title, author, candidates_json, index, tried = row
        candidates = json.loads(candidates_json)
        error = self.http.hide(error)[:300]
        if index + 1 < len(candidates):
            next_host = host_of(candidates[index + 1])
            con.execute("""UPDATE book_cover SET next_index = ?, next_host = ?, tried = tried + ?, last_error = ?
                           WHERE book_key = ?""", (index + 1, next_host, count, error, book_key_))
            con.commit()
            with self.lock:
                self.stats['moved'] += 1
            return next_host
        con.execute("""UPDATE book_cover SET status = 'NONE', next_host = NULL, next_index = ?, tried = tried + ?,
                       last_error = ?, fetched_at = ? WHERE book_key = ?""",
                    (index + 1, count, error, now(), book_key_))
        con.commit()
        with self.lock:
            self.stats['none'] += 1
        return None

    def finish_none(self, con: sqlite3.Connection, book_key_: str, error: str) -> None:
        con.execute("UPDATE book_cover SET status = 'NONE', next_host = NULL, last_error = ?, fetched_at = ? "
                    "WHERE book_key = ?", (error, now(), book_key_))
        con.commit()
        with self.lock:
            self.stats['none'] += 1

    def finish_done(self, con: sqlite3.Connection, book_key_: str, url: str, source: str, picture: bytes,
                    tried: int) -> None:
        rel, path = cover_path(self.data_dir, url)
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            tmp = path.with_suffix('.part')
            tmp.write_bytes(picture)
            os.replace(tmp, path)
        con.execute("""UPDATE book_cover SET status = 'DONE', next_host = NULL, source = ?, cover_url = ?, file = ?,
                       bytes = ?, tried = ?, last_error = NULL, fetched_at = ? WHERE book_key = ?""",
                    (source, url, rel, len(picture), tried, now(), book_key_))
        con.commit()
        with self.lock:
            self.stats['done'] += 1
            self.stats['bytes'] += len(picture)
            self.by_source[source] = self.by_source.get(source, 0) + 1

    def note_timing(self, host: str, seconds: float) -> None:
        """A server answering slower than SLOW on average (after SLOW_AFTER requests) is not asked again this run."""
        key = queue_key_of(host)
        with self.lock:
            avg, n = self.timing.get(key, (0.0, 0))
            n += 1
            avg = seconds if n == 1 else avg * 0.9 + seconds * 0.1
            self.timing[key] = (avg, n)
        if n >= SLOW_AFTER and avg > SLOW and key not in self.dispatcher.off:
            self.log.line(f'host {host} ({key}): {avg:.1f}s on average; not asked again in this run (its books stay '
                          f'PENDING)')
            self.dispatcher.turn_off(host)

    def note_failure(self, host: str, failed: bool) -> None:
        """OFF_AFTER failures in a row on one host (not 404s or "no image" pictures) stop asking it in this run."""
        with self.lock:
            n = self.failures[host] = self.failures.get(host, 0) + 1 if failed else 0
        if failed and n == OFF_AFTER:
            self.log.line(f'host {host}: {n} failures in a row; not asked again in this run (its books stay PENDING)')
            self.dispatcher.turn_off(host)


# ---------------------------------------------------------------- status, export, guards

def status(covers: sqlite3.Connection, data_dir: Path) -> None:
    meta = dict(covers.execute('SELECT key, value FROM meta'))
    print(f"planned: {meta.get('planned_at', '-')} from {meta.get('planned_from', '-')}")
    total = covers.execute('SELECT count(*) FROM book_cover').fetchone()[0]
    print(f'books planned: {total}')
    for st, n in covers.execute('SELECT status, count(*) FROM book_cover GROUP BY status ORDER BY status'):
        print(f'  {st}: {n}')
    print('covers by source:')
    for source, n, b in covers.execute("SELECT source, count(*), coalesce(sum(bytes), 0) FROM book_cover "
                                       "WHERE status = 'DONE' GROUP BY source ORDER BY 2 DESC"):
        print(f'  {source}: {n} ({b / 1048576:.1f} MB)')
    files, size = covers.execute("SELECT count(DISTINCT file), coalesce(sum(bytes), 0) FROM book_cover "
                                 "WHERE status = 'DONE'").fetchone()
    print(f'files: {files} distinct, {size / 1048576:.1f} MB in the table (a shared file counted once per book)')
    print('pending by host (top 15):')
    for host, n in covers.execute("SELECT next_host, count(*) FROM book_cover WHERE status = 'PENDING' "
                                  "GROUP BY next_host ORDER BY 2 DESC LIMIT 15"):
        print(f'  {host}: {n}')
    print('none, last error (top 10):')
    for err, n in covers.execute("SELECT substr(coalesce(last_error, ''), 1, 60), count(*) FROM book_cover "
                                 "WHERE status = 'NONE' GROUP BY 1 ORDER BY 2 DESC LIMIT 10"):
        print(f'  {n}: {err}')
    print('runs:')
    seconds = 0.0
    for sid, started, finished, requests, done, none, size_, note in covers.execute(
            'SELECT id, started_at, finished_at, requests, done, none, bytes, note FROM run ORDER BY id'):
        took = ''
        if finished:
            delta = dt.datetime.fromisoformat(finished) - dt.datetime.fromisoformat(started)
            seconds += delta.total_seconds()
            took = f'{delta.total_seconds() / 3600:.1f} h'
        print(f'  #{sid} {started} -> {finished or "(running or stopped)"} {took}: {requests} requests, {done} covers, '
              f'{none} without, {size_ / 1048576:.1f} MB {note or ""}')
    print(f'runs total: {seconds / 3600:.1f} h')
    folder = data_dir / 'covers'
    if folder.exists():
        count, on_disk = 0, 0
        for f in folder.rglob('*.jpg'):
            count += 1
            on_disk += f.stat().st_size
        print(f'on disk: {count} .jpg files, {on_disk / 1048576:.1f} MB under {folder}')


def export(covers: sqlite3.Connection, data_dir: Path) -> Path:
    path = data_dir / 'covers' / 'book-covers.tsv'
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix('.tsv.part')
    with tmp.open('w', encoding='utf-8', newline='\n') as f:
        f.write('book_id\tbook_key\tisbn13\tstatus\tsource\tfile\tcover_url\n')
        for row in covers.execute('SELECT book_id, book_key, isbn13, status, source, file, cover_url FROM book_cover '
                                  'ORDER BY book_id'):
            f.write('\t'.join('' if v is None else str(v).replace('\t', ' ') for v in row) + '\n')
    os.replace(tmp, path)
    return path


def crawl_running() -> bool:
    """A `full` or `refill-pubdt` crawl of crawl_catalog.py in another process (Windows; elsewhere unknown = False)."""
    if sys.platform != 'win32':
        return False
    script = ("Get-CimInstance Win32_Process -Filter \"Name='python.exe'\" | Where-Object { $_.ProcessId -ne %d -and "
              "$_.CommandLine -like '*crawl_catalog.py*' -and ($_.CommandLine -like '* full*' -or "
              "$_.CommandLine -like '* refill-pubdt*') } | Measure-Object | Select-Object -ExpandProperty Count"
              % os.getpid())
    try:
        out = subprocess.run(['powershell', '-NoProfile', '-Command', script], capture_output=True, text=True, timeout=60)
        return out.stdout.strip() not in ('', '0')
    except (OSError, subprocess.TimeoutExpired):
        return False


def crawl_started() -> str | None:
    """Start time (YYYY-MM-DDTHH:MM, local) of a running `crawl_catalog.py full`, or None when no crawl runs."""
    if sys.platform != 'win32':
        return None
    script = ("Get-CimInstance Win32_Process -Filter \"Name='python.exe'\" | Where-Object { $_.CommandLine -like "
              "'*crawl_catalog.py*' -and $_.CommandLine -like '* full*' } | Sort-Object CreationDate | "
              "Select-Object -First 1 | ForEach-Object { $_.CreationDate.ToString('yyyy-MM-ddTHH:mm') }")
    out = subprocess.run(['powershell', '-NoProfile', '-Command', script], capture_output=True, text=True, timeout=60)
    return out.stdout.strip() or None


def busy_ips(db: Path | None = None) -> set[str]:
    """
    IPs a running list crawl still has libraries on (no run of theirs finished since that crawl started, not skipped,
    not on a never-asked server); empty when no crawl runs.
    """
    import socket
    started = crawl_started()
    if not started:
        return set()
    con = sqlite3.connect(f'file:{(db or cc.DB).as_posix()}?mode=ro', uri=True, timeout=120)
    try:
        finished = {r[0] for r in con.execute("SELECT library_id FROM crawl_run WHERE started_at >= ? AND status IN "
                                              "('SUCCEEDED', 'FAILED', 'MISMATCH')", (started,))}
        libraries = con.execute('SELECT id, base_url, crawl_skip FROM library').fetchall()
    finally:
        con.close()
    ips = set()
    for lib_id, url, skip in libraries:
        if skip or cc.is_blocked_host(url) or lib_id in finished:
            continue
        try:
            ips.add(socket.gethostbyname(urllib.parse.urlsplit(url).hostname))
        except (OSError, UnicodeError):
            pass
    return ips


def nlk_key_of(env: dict[str, str], data_dir: Path) -> str:
    """The National Library of Korea key: the dev .env (CHAEKCHECK_PICKS_NLK_API_KEY), else the data folder's secret."""
    key = env.get('CHAEKCHECK_PICKS_NLK_API_KEY', '').strip()
    if key:
        return key
    secret = data_dir / 'secrets' / 'nl-cert-key.txt'
    if secret.exists():
        return secret.read_text(encoding='utf-8').strip()
    return ''


# ---------------------------------------------------------------- main

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog='crawl_catalog.py fetch-covers', description=__doc__.split('\n')[0])
    parser.add_argument('--data-dir', default='', help='data folder (default: $CHAEKCHECK_DATA or ../chaekcheck-data)')
    parser.add_argument('--sqlite', default='', help='the collected DB (default: <data>/ebook-catalog.sqlite, read-only)')
    parser.add_argument('--env', default=str(rl.ROOT / '.env'), help='dev .env for the service DB and the NLK key')
    parser.add_argument('--db-port', help='the PC database port (default: DB_PORT of .env)')
    parser.add_argument('--workers', type=int, default=32, help='servers asked at the same time (default 32)')
    parser.add_argument('--gap', type=float, default=GAP, help='seconds between requests to one server (default 1.0)')
    parser.add_argument('--limit', type=int, help='plan only the first N books (by id; a trial)')
    parser.add_argument('--max-books', type=int, help='stop this run after N books got a cover or none (a trial)')
    parser.add_argument('--plan-only', action='store_true', help='read the books and write the plan, no request')
    parser.add_argument('--fetch-only', action='store_true', help='skip the plan step; fetch what is PENDING')
    parser.add_argument('--retry-none', action='store_true', help='ask again for the books that got no cover')
    parser.add_argument('--status', action='store_true', help='counts and sizes of the covers folder, no request')
    parser.add_argument('--export', action='store_true', help='write <data>/covers/book-covers.tsv (book id, key, file)')
    args = parser.parse_args(argv)
    sys.stdout.reconfigure(encoding='utf-8')

    data_dir = Path(args.data_dir) if args.data_dir else cc.ROOT
    covers_path = data_dir / 'covers' / 'covers.sqlite'
    if args.status or args.export:
        if not covers_path.exists():
            print(f'{covers_path} does not exist: nothing planned yet')
            return 1
        con = open_covers_db(covers_path)
        if args.export:
            print('written:', export(con, data_dir))
        if args.status:
            status(con, data_dir)
        return 0

    stamp = dt.datetime.now().strftime('%Y-%m-%d_%H%M')
    log = Log(data_dir / 'logs' / f'covers-{stamp}.log')
    env = rl.read_env(Path(args.env))
    nlk_key = nlk_key_of(env, data_dir)
    http = Http(log, [nlk_key])
    busy_source = None
    if not args.plan_only and crawl_running():
        # user 2026-10-24: covers while the list crawl runs, on the servers it does not use
        log.line('a list crawl is running: the servers (IPs) it still uses are skipped and checked again every '
                 f'{BUSY_RECHECK // 60} min')
        db = Path(args.sqlite) if args.sqlite else data_dir / 'ebook-catalog.sqlite'
        busy_source = lambda: busy_ips(db)  # noqa: E731
    try:
        rules = rl.load_rules(LISTS_DIR)
        order()
    except rl.CollectorFailure as ex:
        log.line(f'rules: {ex}; nothing was asked or changed')
        return 1
    covers = open_covers_db(covers_path)
    if not args.fetch_only:
        sqlite_path = Path(args.sqlite) if args.sqlite else data_dir / 'ebook-catalog.sqlite'
        collected = sqlite3.connect(f'file:{sqlite_path.as_posix()}?mode=ro', uri=True, timeout=120)
        collected.execute('PRAGMA busy_timeout=120000')
        try:
            source = BookSource.connect(env, args.db_port)
        except Exception as ex:  # noqa: BLE001
            log.line(f'service DB: cannot connect ({ex.__class__.__name__}); nothing planned')
            return 1
        log.line(f'plan: books from the PC database (SELECT only, {source.latest_import()}), addresses from {sqlite_path}')
        plan(covers, collected, source, log, bool(nlk_key), args.limit)
        collected.close()
        source.db.close()
    if args.plan_only:
        return 0
    if not nlk_key:
        log.line('National Library of Korea: no key (CHAEKCHECK_PICKS_NLK_API_KEY or secrets/nl-cert-key.txt); '
                 'books without a library cover end as NONE')
    note = f'workers {args.workers}, gap {args.gap}' + (f', max-books {args.max_books}' if args.max_books else '')
    run_id = covers.execute('INSERT INTO run (started_at, note) VALUES (?, ?)', (now(), note)).lastrowid
    covers.commit()
    covers.close()
    fetcher = Fetcher(data_dir, covers_path, http, log, nlk_key, rules, gap=args.gap, max_books=args.max_books,
                      retry_none=args.retry_none, busy_source=busy_source)
    result = fetcher.run(max(1, args.workers))
    covers = open_covers_db(covers_path)
    covers.execute('UPDATE run SET finished_at = ?, requests = ?, done = ?, none = ?, bytes = ? WHERE id = ?',
                   (now(), result['requests'], result['done'], result['none'], result['bytes'], run_id))
    covers.commit()
    log.line(f"fetch: {result['done']} covers {result['by_source']}, {result['none']} books without, "
             f"{result['requests']} requests, {result['bytes'] / 1048576:.1f} MB, {result['elapsed'] / 60:.1f} min, "
             f"{result['pending']} still pending")
    return 0


if __name__ == '__main__':
    sys.exit(main())
