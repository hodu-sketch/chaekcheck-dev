"""Recommended lists collector (cycle 21; ADR-0124, ADR-0128; plan design-delta "목록 파일", spec R3, R4).

Keeps the list files in scripts/crawl/lists/ that the catalog export carries (fields.tsv, leedongjin.tsv, snu100.tsv,
covers/). One run does, in order:

1. YouTube (Lee Dong-jin): reads the 「이달의 책」 playlist and the channel's uploads from the first page to the last,
   with the YouTube Data API only (www.googleapis.com; no page of www.youtube.com is ever requested, spec R4-1). A
   listed video still public gets today's checked_on; a video gone or not public has its rows deleted (30-day rule,
   YouTube developer policies III.E.4). A new video's book names (the [ ] 《 》 〈 〉 『 』 marks or the text after 🔖 in its
   title and description) are looked up in the ISBN bibliography API and written to leedongjin_pending.tsv with a field
   suggested from the API's one-digit SUBJECT (subject_fields.tsv); a person sets the field and moves the row
   (spec R4-3, changed 2026-10-16). A book of the year already listed moves to that year's December (spec R3-5).
   Titles and descriptions are read in memory only: no file or log line keeps them.
2. Editions: for every row not fixed by a person (pinned_by manual), the same book's editions in the PC's catalog
   (the current fixed edition, else the row's ISBN, gives the book: same main_title_norm and a shared author), without
   the excluded ones (spec R3-3, R3-4), and the one held most (enabled libraries, all subscription libraries as one,
   ADR-0125), then the first volume, then the smaller id: the same rule as Java's BookChoice.
3. Covers: per row with an edition, ① the ISBN bibliography's cover of the edition's ISBN, ② the collected catalog's
   cover addresses of its holdings, YES24 and Kyobo first, ③ the others; the first that loads is made 200px wide, JPEG
   quality 0.8 (originals over 5MB are not read), stored as covers/<sha1(url)>.jpg (spec R5-4, ADR-0091).

A failure of YouTube or the API (key refused, quota, no answer) leaves the list files as they were and writes one line
to the log (spec R4-6, AC-13); a database failure leaves the fixed editions as they were. Every request's host is
logged (AC-12). Keys come from the dev .env only (CHAEKCHECK_YOUTUBE_API_KEY, CHAEKCHECK_PICKS_NLK_API_KEY) and are
never written anywhere.

  python scripts/crawl/recommended_lists.py --sqlite ../chaekcheck-data/snapshots/ebook-catalog-2026-10-12-0135.sqlite
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import io
import json
import os
import re
import sqlite3
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LISTS_DIR = ROOT / 'scripts' / 'crawl' / 'lists'
LOG_FILE = ROOT / 'data' / 'recommended-lists' / 'collect.log'

MONTHLY_PLAYLIST = 'PLUpgTv3bu9rGxQBAMVaMvam27P0YbiY29'
YOUTUBE_API = 'https://www.googleapis.com/youtube/v3/'
SEOJI_API = 'https://www.nl.go.kr/seoji/SearchApi.do'
USER_AGENT = 'chaekcheck-collector/1.0 (+https://github.com/; contact via repository)'

COVER_WIDTH = 200
COVER_QUALITY = 80
COVER_MAX_BYTES = 5 * 1024 * 1024
REQUEST_INTERVAL = 1.0
PREFERRED_COVER_HOSTS = ('image.yes24.com', 'dcontent.dkyobobook.co.kr')

LEEDONGJIN_COLUMNS = ['month', 'position', 'year_book', 'video_id', 'field', 'title', 'author', 'publisher', 'isbn13',
                      'cover_url', 'pinned_title', 'pinned_author', 'pinned_publisher', 'pinned_isbn13', 'pinned_by',
                      'checked_on', 'note']
SNU_COLUMNS = ['toc_no', 'field', 'title', 'author', 'cover_url', 'pinned_title', 'pinned_author', 'pinned_publisher',
               'pinned_isbn13', 'pinned_by', 'note']
PENDING_COLUMNS = ['video_id', 'month', 'reason', 'title', 'author', 'isbn13', 'field_suggestion']

# spec R3-3, R3-4: editions never chosen
EXCLUDED_TITLE_WORDS = ('영어원서', '원서', '어린이', '초등', '청소년', '만화', '그림책', '논술', '공부가 되는', '100분 고전')
BOOK_NAME = re.compile(r'\[([^\[\]]+)\]|《([^《》]+)》|〈([^〈〉]+)〉|『([^『』]+)』|🔖\s*([^\n]+)')
# words that are the channel's own labels, never a book (e.g. "[이달의 책]")
NOT_A_BOOK = re.compile(r'이달의책|올해의책|베스트북|최고의책|파이아키아|Btv|이동진', re.IGNORECASE)
YEAR_BOOK_TITLE = re.compile(r'올해의\s*책|베스트북')

# ShownTitle (Java) reading of the volume, for the first volume (ADR-0125)
SERIES = re.compile(r'^\[[^\]]*\]\s*')
LAST_PARENTHESES = re.compile(r'\s*\(([^()]*)\)$')
ORIGINAL_EDITION = re.compile(r'\s+오리지널판$')
VOLUME_INSIDE = re.compile(r'(?:제\s*)?(\d{1,2}|상|중|하)\s*권?')
VOLUME_AT_END = re.compile(r'(?:[.,]\s*|\s+)(?:제\s*)?(\d{1,2}|상|중|하)\s*권?$|(?<=\D)(\d{1,2})권$')


class CollectorFailure(Exception):
    """An answer the run cannot go on with: the files stay as they were."""


# ---------------------------------------------------------------- log and requests

class Log:
    def __init__(self, path: Path | None):
        self.path = path
        self.lines: list[str] = []

    def line(self, text: str) -> None:
        stamp = dt.datetime.now().astimezone().isoformat(timespec='seconds')
        self.lines.append(text)
        print(text)
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with self.path.open('a', encoding='utf-8') as f:
                f.write(f'{stamp}\t{text}\n')


class Http:
    """GET only, one request per host at a time with REQUEST_INTERVAL between them; every host logged, no key."""

    def __init__(self, log: Log, secrets: list[str], sleep=time.sleep, opener=None):
        self.log = log
        self.secrets = [s for s in secrets if s]
        self.sleep = sleep
        self.opener = opener or urllib.request.urlopen
        self.last: dict[str, float] = {}
        self.hosts: list[str] = []

    def _hide(self, text: str) -> str:
        for secret in self.secrets:
            text = text.replace(secret, '***')
        return text

    def get(self, url: str, max_bytes: int | None = None) -> tuple[int, str, bytes]:
        parts = urllib.parse.urlsplit(url)
        host = parts.hostname or ''
        wait = REQUEST_INTERVAL - (time.monotonic() - self.last.get(host, -1e9))
        if wait > 0:
            self.sleep(wait)
        self.hosts.append(host)
        self.log.line(f'GET {host} {parts.path}')
        request = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
        try:
            with self.opener(request, timeout=30) as response:
                # read() for the whole body: read(-1) keeps the chunk marks of a chunked answer
                body = response.read(max_bytes + 1) if max_bytes else response.read()
                if max_bytes and len(body) > max_bytes:
                    raise CollectorFailure(f'{host}: larger than {max_bytes} bytes')
                return response.status, response.headers.get('Content-Type', ''), body
        except urllib.error.HTTPError as ex:
            return ex.code, ex.headers.get('Content-Type', '') if ex.headers else '', ex.read() if ex.fp else b''
        except (urllib.error.URLError, TimeoutError, OSError) as ex:
            raise CollectorFailure(self._hide(f'{host}: no answer ({ex.__class__.__name__})')) from None
        finally:
            self.last[host] = time.monotonic()

    def json(self, url: str, params: dict) -> dict:
        status, _, body = self.get(url + '?' + urllib.parse.urlencode(params))
        try:
            data = json.loads(body.decode('utf-8'))
        except ValueError:
            raise CollectorFailure(f'{urllib.parse.urlsplit(url).hostname}: HTTP {status}, not JSON') from None
        if status != 200:
            reason = ''
            if isinstance(data, dict) and isinstance(data.get('error'), dict):
                errors = data['error'].get('errors') or [{}]
                reason = errors[0].get('reason', '') or str(data['error'].get('code', ''))
            raise CollectorFailure(f'{urllib.parse.urlsplit(url).hostname}: HTTP {status} {reason}'.strip())
        return data


# ---------------------------------------------------------------- files

def read_tsv(path: Path) -> tuple[list[str], list[dict]]:
    if not path.exists():
        return [], []
    text = path.read_text(encoding='utf-8').lstrip('﻿').splitlines()
    if not text:
        return [], []
    header = text[0].split('\t')
    rows = []
    for line in text[1:]:
        if not line.strip():
            continue
        cells = line.split('\t')
        rows.append({column: (cells[i].strip() if i < len(cells) else '') for i, column in enumerate(header)})
    return header, rows


def write_tsv(path: Path, header: list[str], rows: list[dict]) -> None:
    lines = ['\t'.join(header)]
    for row in rows:
        lines.append('\t'.join(str(row.get(column, '') or '').replace('\t', ' ').replace('\n', ' ') for column in header))
    tmp = path.with_suffix(path.suffix + '.part')
    tmp.write_text('\n'.join(lines) + '\n', encoding='utf-8', newline='\n')
    os.replace(tmp, path)


def read_env(path: Path) -> dict[str, str]:
    values = {}
    if path.exists():
        for line in path.read_text(encoding='utf-8').splitlines():
            if '=' in line and not line.lstrip().startswith('#'):
                key, value = line.split('=', 1)
                values[key.strip()] = value.strip()
    return values


# ---------------------------------------------------------------- choosing an edition (same as Java BookChoice)

def volume_of(title: str) -> str | None:
    """The volume ShownTitle (Java) reads at the end of a title: '1', '2', '상', ... or None."""
    return read_title(title)[1]


def title_base(title: str) -> str:
    """The title without its edition marks and volume, as ShownTitle (Java) reads it."""
    return read_title(title)[0]


def read_title(title: str) -> tuple[str, str | None]:
    text = (title or '').strip()
    volume = None
    m = SERIES.match(text)
    if m and m.end() < len(text):
        text = text[m.end():]
    for _ in range(2):
        m = ORIGINAL_EDITION.search(text)
        if m:
            text = text[:m.start()]
        m = LAST_PARENTHESES.search(text)
        if m and m.start() > 0:
            inside = VOLUME_INSIDE.fullmatch(m.group(1).strip())
            if volume is None and inside:
                volume = inside.group(1)
            text = text[:m.start()]
    if volume is None:
        m = VOLUME_AT_END.search(text)
        if m and m.start() > 0:
            volume = m.group(1) or m.group(2)
            text = text[:m.start()]
    if volume and volume.isdigit():
        volume = str(int(volume))
    return re.sub(r'[\s.,:;·\-]+$', '', text), volume


def first_volume(title: str) -> bool:
    volume = volume_of(title)
    return volume is None or volume in ('1', '상')


def held(library_ids, kinds: dict[int, bool]) -> int:
    """Enabled libraries that do not lend by subscription, plus 1 when any enabled subscription library has it."""
    owned = 0
    subscription = False
    for library in library_ids:
        kind = kinds.get(int(library))
        if kind is None:
            continue
        if kind:
            subscription = True
        else:
            owned += 1
    return owned + (1 if subscription else 0)


def choose(candidates: list[dict], kinds: dict[int, bool]) -> dict | None:
    """Held most, then the first volume, then the smaller id (ADR-0125)."""
    if not candidates:
        return None
    return min(candidates, key=lambda b: (-held(b['library_ids'], kinds), not first_volume(b['title']), b['id']))


def excluded(book: dict) -> bool:
    title = book['title'] or ''
    if any(word in title for word in EXCLUDED_TITLE_WORDS):
        return True
    if '어린이' in (book.get('publisher') or ''):
        return True
    return any('번역AI' in (name or '') or '번역ai' in (name or '') for name in book.get('names', []))


# ---------------------------------------------------------------- the PC's catalog

class Catalog:
    """Reads the PC's PostgreSQL (SELECT only)."""

    def __init__(self, connection):
        self.db = connection

    @staticmethod
    def connect(env: dict[str, str], port: str | None):
        import psycopg
        return Catalog(psycopg.connect(host='localhost', port=port or env.get('DB_PORT', '5432'),
                                       dbname=env['DB_NAME'], user=env['DB_USERNAME'],
                                       password=env.get('DB_PASSWORD', ''), autocommit=True))

    def kinds(self) -> dict[int, bool]:
        with self.db.cursor() as cur:
            cur.execute("SELECT id, lending_model = 'SUBSCRIPTION' FROM library.library WHERE enabled")
            return {int(i): bool(s) for i, s in cur.fetchall()}

    def _books(self, where: str, params) -> list[dict]:
        with self.db.cursor() as cur:
            cur.execute(f"""
                SELECT b.id, b.title, b.author_display, b.publisher, b.isbn13, b.main_title_norm, b.library_ids,
                  coalesce(array_agg(c.name ORDER BY c.position) FILTER (WHERE c.id IS NOT NULL), '{{}}'),
                  coalesce(array_agg(c.name_exact ORDER BY c.position)
                    FILTER (WHERE c.role IN ('AUTHOR', 'UNKNOWN')), '{{}}')
                FROM catalog.book b LEFT JOIN catalog.book_contributor c ON c.book_id = b.id
                WHERE {where} GROUP BY b.id""", params)
            return [{'id': r[0], 'title': r[1], 'author': r[2], 'publisher': r[3], 'isbn13': r[4], 'main': r[5],
                     'library_ids': list(r[6] or []), 'names': list(r[7]), 'authors': set(r[8])} for r in cur.fetchall()]

    def by_isbn(self, isbn: str) -> list[dict]:
        return self._books('b.isbn13 = %s', (isbn,))

    def by_title(self, title: str, publisher: str | None) -> list[dict]:
        found = self._books('b.title = %s', (title,))
        if publisher:
            same = [b for b in found if (b['publisher'] or '').replace(' ', '') == publisher.replace(' ', '')]
            return same or found
        return found

    def same_book(self, anchor: dict) -> list[dict]:
        """
        Editions with the anchor's main title and one of its authors (all of them when it names none). Every volume
        of a set is one book here (main_title_norm keeps the volume: 「토지.2(1부2권)」), so the first volume can be
        chosen (ADR-0125, AC-7): the main title without its volume, read as ShownTitle reads it.
        """
        base = text_key(title_base(anchor['title'])) if anchor.get('title') else anchor['main']
        found = self._books('b.main_title_norm = %s', (anchor['main'],))
        if base and base != anchor['main']:
            known = {b['id'] for b in found}
            prefix = self._books("b.main_title_norm LIKE %s", (base.replace('%', r'\%').replace('_', r'\_') + '%',))
            found += [b for b in prefix if b['id'] not in known and text_key(title_base(b['title'])) == base]
        if not anchor['authors']:
            return found
        return [b for b in found if b['authors'] & anchor['authors']]

    def holding_keys(self, book_id: int) -> list[tuple[int, str]]:
        """(collection library id, item key) of a book's holdings, for the collected cover addresses."""
        with self.db.cursor() as cur:
            cur.execute("""
                SELECT l.collection_library_id, h.item_key FROM catalog.holding h
                JOIN collection.collection_link l ON l.library_id = h.library_id
                WHERE h.book_id = %s ORDER BY h.id""", (book_id,))
            return [(int(a), b) for a, b in cur.fetchall()]


def choose_editions(rows: list[dict], catalog: Catalog, kinds: dict[int, bool], log: Log) -> dict[int, int]:
    """Fixed editions chosen again (not the manual ones); row index → book id of every row with an edition."""
    chosen: dict[int, int] = {}
    for index, row in enumerate(rows):
        anchors = []
        if row.get('pinned_isbn13'):
            anchors = catalog.by_isbn(row['pinned_isbn13'])
        if not anchors and row.get('pinned_title'):
            anchors = catalog.by_title(row['pinned_title'], row.get('pinned_publisher'))
        if not anchors and row.get('isbn13'):
            anchors = catalog.by_isbn(row['isbn13'])
        if not anchors and row.get('title') and row.get('author'):
            # the list's own title and first author, as the export's last step matches (handoff 4-4 ④)
            author = text_key(re.split(r'[,·/;]', row['author'])[0])
            anchors = [b for b in catalog.by_title(row['title'], None) if author in b['authors']]
        if row.get('pinned_by') == 'manual':
            if anchors:
                chosen[index] = choose(anchors, kinds)['id']
            continue
        candidates: dict[int, dict] = {}
        for anchor in anchors:
            for book in catalog.same_book(anchor):
                candidates[book['id']] = book
        best = choose([b for b in candidates.values() if not excluded(b)], kinds)
        if best is None and anchors:
            # every edition of the anchor's authors is excluded (an adaptation for children, a summary, a machine
            # translation, R3-4): the same main title by anyone, as the research did for the classics (R3-3)
            for anchor in anchors:
                for book in catalog.same_book(dict(anchor, authors=set())):
                    candidates[book['id']] = book
            # and the list's own title as a main title (the anchor's may carry a subtitle without " : ")
            for book in catalog.same_book({'main': text_key(row.get('title')), 'authors': set()}):
                candidates[book['id']] = book
            best = choose([b for b in candidates.values() if not excluded(b)], kinds)
        name = row.get('title')
        if best is None:
            if row.get('pinned_title') or row.get('pinned_isbn13'):
                log.line(f'edition: none left for {name}; the export matches the row by its own title')
            for column in ('pinned_title', 'pinned_author', 'pinned_publisher', 'pinned_isbn13', 'pinned_by'):
                row[column] = ''
            continue
        row['pinned_title'] = best['title'] or ''
        row['pinned_author'] = best['author'] or ''
        row['pinned_publisher'] = best['publisher'] or ''
        row['pinned_isbn13'] = best['isbn13'] or ''
        row['pinned_by'] = 'collector'
        chosen[index] = best['id']
    return chosen


# ---------------------------------------------------------------- YouTube

@dataclass
class Video:
    video_id: str
    published: str  # YYYY-MM-DD
    public: bool
    title: str = field(repr=False, default='')
    description: str = field(repr=False, default='')


class YouTube:
    def __init__(self, http: Http, key: str):
        self.http = http
        self.key = key

    def _call(self, method: str, **params) -> dict:
        return self.http.json(YOUTUBE_API + method, dict(params, key=self.key))

    def playlist(self, playlist_id: str) -> list[Video]:
        videos, token = [], None
        while True:
            params = dict(part='snippet,contentDetails,status', playlistId=playlist_id, maxResults=50)
            if token:
                params['pageToken'] = token
            page = self._call('playlistItems', **params)
            for item in page.get('items', []):
                details = item.get('contentDetails', {})
                snippet = item.get('snippet', {})
                videos.append(Video(details.get('videoId', ''), (details.get('videoPublishedAt') or '')[:10],
                                    item.get('status', {}).get('privacyStatus') == 'public',
                                    snippet.get('title', ''), snippet.get('description', '')))
            token = page.get('nextPageToken')
            if not token:
                return videos

    def uploads_playlist(self) -> str:
        items = self._call('playlists', part='snippet', id=MONTHLY_PLAYLIST).get('items', [])
        if not items:
            raise CollectorFailure('www.googleapis.com: the 이달의 책 playlist is gone')
        channel = items[0]['snippet']['channelId']
        channels = self._call('channels', part='contentDetails', id=channel).get('items', [])
        if not channels:
            raise CollectorFailure('www.googleapis.com: the channel is gone')
        return channels[0]['contentDetails']['relatedPlaylists']['uploads']


def book_names(video: Video) -> list[str]:
    names = []
    for text in (video.title, video.description):
        for match in BOOK_NAME.finditer(text or ''):
            name = next(g for g in match.groups() if g)
            name = name.strip().split('\n')[0].strip(' "\'')
            if name and not NOT_A_BOOK.search(unicodedata.normalize('NFKC', name).replace(' ', '')) and name not in names:
                names.append(name)
    return names


def video_month(video: Video) -> str:
    """The month the title names ("N월의 책"), else the month it was published (research 3)."""
    published = video.published or dt.date.today().isoformat()
    year, month = int(published[:4]), int(published[5:7])
    m = re.search(r'(\d{1,2})\s*월', video.title or '')
    if m and 1 <= int(m.group(1)) <= 12:
        named = int(m.group(1))
        if named > month + 1:  # a December book shown in January
            year -= 1
        month = named
    return f'{year:04d}-{month:02d}'


def text_key(text: str) -> str:
    return re.sub(r'[\W_]+', '', unicodedata.normalize('NFKC', text or '').lower())


class Seoji:
    """The National Library of Korea's ISBN bibliography API (www.nl.go.kr/seoji)."""

    def __init__(self, http: Http, key: str):
        self.http = http
        self.key = key

    def search(self, **params) -> list[dict]:
        data = self.http.json(SEOJI_API, dict(params, cert_key=self.key, result_style='json', page_no=1, page_size=20))
        return data.get('docs', []) or []

    def by_title(self, name: str) -> list[dict]:
        key = text_key(name)
        return [d for d in self.search(title=name) if text_key((d.get('TITLE') or '').split(':')[0]) == key]

    def cover_url(self, isbn: str) -> str | None:
        for doc in self.search(isbn=isbn):
            url = (doc.get('TITLE_URL') or '').strip()
            if url:
                return 'https://' + url[len('http://'):] if url.startswith('http://') else url
        return None


def sync_youtube(rows: list[dict], pending: list[dict], youtube: YouTube, seoji: Seoji | None,
                 subject_fields: dict[str, str], today: str, log: Log) -> tuple[list[dict], list[dict]]:
    """New rows and pending rows after reading both playlists to the end; raises CollectorFailure on an API error."""
    monthly = youtube.playlist(MONTHLY_PLAYLIST)
    uploads = youtube.playlist(youtube.uploads_playlist())
    public = {v.video_id for v in monthly + uploads if v.public}
    kept = []
    for row in rows:
        if row['video_id'] in public:
            row['checked_on'] = today
            kept.append(row)
        else:
            log.line(f"youtube: {row['video_id']} is gone or not public; its row ({row['title']}) is deleted")
    rows = kept
    pending = [p for p in pending if p['video_id'] in public]
    listed = {r['video_id'] for r in rows} | {p['video_id'] for p in pending}
    monthly_ids = {v.video_id for v in monthly}
    year_books = [v for v in uploads if v.public and v.video_id not in monthly_ids and v.published[5:7] == '12'
                  and YEAR_BOOK_TITLE.search(v.title or '')]
    for video, year_book in [(v, None) for v in monthly if v.public] + [(v, v.published[:4]) for v in year_books]:
        if video.video_id in listed:
            continue
        month = f'{video.published[:4]}-12' if year_book else video_month(video)
        if year_book is None and video.title and YEAR_BOOK_TITLE.search(video.title) and month.endswith('-12'):
            year_book = month[:4]
        names = book_names(video)
        if not names:
            pending.append({'video_id': video.video_id, 'month': month, 'reason': 'no_book_name'})
            log.line(f'youtube: {video.video_id} names no book; left for a person')
            continue
        listed_names = [n for n in names if any(text_key(r['title']) == text_key(n) for r in rows)]
        if listed_names:
            # a video whose book is listed was handled already; a book of the year moves to that December (R3-5)
            for name in listed_names if year_book else []:
                same = next(r for r in rows if text_key(r['title']) == text_key(name))
                if same['month'] < month:
                    same.update(month=month, video_id=video.video_id, year_book=year_book, position='')
                    log.line(f'youtube: {name} is a book of the year {year_book}; moved to {month}')
            listed.add(video.video_id)
            continue
        for name in names:
            found = seoji.by_title(name) if seoji else []
            isbns = sorted({d.get('EA_ISBN') for d in found if d.get('EA_ISBN')})
            doc = found[0] if found else {}
            reason = 'not_found' if not found else ('many_books' if len(isbns) > 1 else '')
            pending.append({'video_id': video.video_id, 'month': month, 'reason': reason, 'title': name,
                            'author': (doc.get('AUTHOR') or '').split(';')[0].strip(),
                            'isbn13': isbns[0] if isbns else '',
                            'field_suggestion': subject_fields.get((doc.get('SUBJECT') or '').strip(), '')})
            log.line(f'youtube: new book in {video.video_id} ({month}), left for a person to set its field')
        listed.add(video.video_id)
    renumber(rows)
    return rows, pending


def renumber(rows: list[dict]) -> None:
    """Positions inside a month, in file order (a moved book goes after the month's own books)."""
    rows.sort(key=lambda r: (r['month'], int(r['position']) if str(r.get('position') or '').isdigit() else 10 ** 6))
    counts: dict[str, int] = {}
    for row in rows:
        counts[row['month']] = counts.get(row['month'], 0) + 1
        row['position'] = str(counts[row['month']])


# ---------------------------------------------------------------- covers

def shrink(image_bytes: bytes) -> bytes:
    """200px wide JPEG, quality 0.8 (ADR-0091); raises ValueError for anything that is not an image."""
    from PIL import Image
    with Image.open(io.BytesIO(image_bytes)) as image:
        image.load()
        if image.mode not in ('RGB', 'L'):
            background = Image.new('RGB', image.size, 'white')
            converted = image.convert('RGBA')
            background.paste(converted, mask=converted.getchannel('A'))
            image = background
        elif image.mode == 'L':
            image = image.convert('RGB')
        if image.width != COVER_WIDTH:
            height = max(1, round(image.height * COVER_WIDTH / image.width))
            image = image.resize((COVER_WIDTH, height), Image.LANCZOS)
        out = io.BytesIO()
        image.save(out, 'JPEG', quality=COVER_QUALITY, optimize=True)
        return out.getvalue()


def cover_file(lists_dir: Path, url: str) -> Path:
    return lists_dir / 'covers' / (hashlib.sha1(url.encode('utf-8')).hexdigest() + '.jpg')


def https(url: str) -> str:
    return 'https://' + url[len('http://'):] if url.startswith('http://') else url


def collected_covers(sqlite: sqlite3.Connection, keys: list[tuple[int, str]]) -> list[str]:
    urls = []
    for library, item in keys:
        row = sqlite.execute('SELECT cover_url FROM holding WHERE library_id = ? AND item_key = ?', (library, item)).fetchone()
        if row and row[0] and row[0].startswith(('http://', 'https://')):
            url = https(row[0])
            if url not in urls:
                urls.append(url)
    preferred = [u for u in urls if urllib.parse.urlsplit(u).hostname in PREFERRED_COVER_HOSTS]
    return preferred + [u for u in urls if u not in preferred]


def fetch_covers(lists_dir: Path, rows: list[dict], chosen: dict[int, int], catalog: Catalog,
                 sqlite: sqlite3.Connection | None, seoji: Seoji | None, http: Http, log: Log,
                 attempts: int = 4) -> None:
    for index, row in enumerate(rows):
        if index not in chosen:
            continue
        current = row.get('cover_url')
        if current and cover_file(lists_dir, current).exists():
            continue
        candidates = []
        if seoji and row.get('pinned_isbn13'):
            try:
                url = seoji.cover_url(row['pinned_isbn13'])
                if url:
                    candidates.append(url)
            except CollectorFailure as ex:
                log.line(f'cover: ISBN bibliography lookup failed ({ex})')
        if sqlite is not None:
            candidates += [u for u in collected_covers(sqlite, catalog.holding_keys(chosen[index])) if u not in candidates]
        row['cover_url'] = ''
        stored = next((url for url in candidates if cover_file(lists_dir, url).exists()), None)
        if stored:  # made by an earlier run: no request
            row['cover_url'] = stored
            continue
        for url in candidates[:attempts]:
            try:
                status, _, body = http.get(url, COVER_MAX_BYTES)
                if status != 200:
                    continue
                path = cover_file(lists_dir, url)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(shrink(body))
                row['cover_url'] = url
                break
            except (CollectorFailure, ValueError, OSError) as ex:
                log.line(f'cover: {urllib.parse.urlsplit(url).hostname} failed ({ex.__class__.__name__})')
        if not row['cover_url']:
            log.line(f"cover: none for {row.get('title')}; the screen shows its title")


def remove_unused_covers(lists_dir: Path, rows: list[dict]) -> int:
    used = {cover_file(lists_dir, r['cover_url']).name for r in rows if r.get('cover_url')}
    removed = 0
    for path in (lists_dir / 'covers').glob('*.jpg'):
        if path.name not in used:
            path.unlink()
            removed += 1
    return removed


# ---------------------------------------------------------------- run

def run(args, http_opener=None, catalog: Catalog | None = None, sqlite: sqlite3.Connection | None = None,
        sleep=time.sleep) -> int:
    lists_dir = Path(args.lists_dir)
    env = read_env(Path(args.env))
    youtube_key = env.get('CHAEKCHECK_YOUTUBE_API_KEY', '')
    seoji_key = env.get('CHAEKCHECK_PICKS_NLK_API_KEY', '')
    log = Log(Path(args.log) if args.log else None)
    http = Http(log, [youtube_key, seoji_key], sleep=sleep, opener=http_opener)
    today = args.today or dt.date.today().isoformat()

    lee_header, lee = read_tsv(lists_dir / 'leedongjin.tsv')
    snu_header, snu = read_tsv(lists_dir / 'snu100.tsv')
    _, pending = read_tsv(lists_dir / 'leedongjin_pending.tsv')
    _, subjects = read_tsv(lists_dir / 'subject_fields.tsv')
    subject_fields = {s['subject']: s['field_suggestion'] for s in subjects}
    seoji = Seoji(http, seoji_key) if seoji_key else None
    status = 0

    if not args.skip_youtube:
        try:
            if not youtube_key:
                raise CollectorFailure('no CHAEKCHECK_YOUTUBE_API_KEY in .env')
            lee, pending = sync_youtube(lee, pending, YouTube(http, youtube_key), seoji, subject_fields, today, log)
        except CollectorFailure as ex:
            log.line(f'youtube: failed, the list files stay as they were: {ex}')
            return 1

    chosen_lee: dict[int, int] = {}
    chosen_snu: dict[int, int] = {}
    if not args.skip_editions:
        try:
            catalog = catalog or Catalog.connect(env, args.db_port)
            kinds = catalog.kinds()
            chosen_lee = choose_editions(lee, catalog, kinds, log)
            chosen_snu = choose_editions(snu, catalog, kinds, log)
            log.line(f'editions: leedongjin {len(chosen_lee)}/{len(lee)}, snu100 {len(chosen_snu)}/{len(snu)}')
        except Exception as ex:  # a database that does not answer keeps the fixed editions as they were
            log.line(f'editions: database failed, the fixed editions stay as they were ({ex.__class__.__name__})')
            catalog = None
            status = 1

    if not args.skip_covers and catalog is not None:
        if sqlite is None and args.sqlite:
            sqlite = sqlite3.connect(f'file:{Path(args.sqlite).as_posix()}?mode=ro', uri=True)
        fetch_covers(lists_dir, lee, chosen_lee, catalog, sqlite, seoji, http, log)
        fetch_covers(lists_dir, snu, chosen_snu, catalog, sqlite, seoji, http, log)
        log.line(f'covers: {remove_unused_covers(lists_dir, lee + snu)} unused files removed')

    write_tsv(lists_dir / 'leedongjin.tsv', lee_header or LEEDONGJIN_COLUMNS, lee)
    write_tsv(lists_dir / 'snu100.tsv', snu_header or SNU_COLUMNS, snu)
    if pending or (lists_dir / 'leedongjin_pending.tsv').exists():
        write_tsv(lists_dir / 'leedongjin_pending.tsv', PENDING_COLUMNS, pending)
    log.line(f"hosts asked: {sorted(set(http.hosts))}")
    return status


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--lists-dir', default=str(LISTS_DIR))
    parser.add_argument('--env', default=str(ROOT / '.env'))
    parser.add_argument('--db-port', help='the PC database port (default: DB_PORT of .env)')
    parser.add_argument('--sqlite', help='the collected catalog copy, for the cover addresses')
    parser.add_argument('--log', default=str(LOG_FILE))
    parser.add_argument('--today', help='YYYY-MM-DD (tests)')
    parser.add_argument('--skip-youtube', action='store_true')
    parser.add_argument('--skip-editions', action='store_true')
    parser.add_argument('--skip-covers', action='store_true')
    return run(parser.parse_args(argv))


if __name__ == '__main__':
    sys.exit(main())
