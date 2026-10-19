"""Recommended lists collector (cycle 21; ADR-0124, ADR-0128; plan design-delta "목록 파일", spec R3, R4).

Keeps the list files in scripts/crawl/lists/ that the catalog export carries (fields.tsv, leedongjin.tsv, snu100.tsv,
kaist.tsv, nobel.tsv, covers/). KAIST's list (user 2026-10-19) and the Bokklubben World Library (nobel.tsv, user
2026-10-20) get fixed editions and covers like the SNU 100; --lists names the lists a run works on (all by default).
One run does, in order:

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

# rules-index.md: 이달의 책 재생목록
MONTHLY_PLAYLIST = 'PLUpgTv3bu9rGxQBAMVaMvam27P0YbiY29'
YOUTUBE_API = 'https://www.googleapis.com/youtube/v3/'
SEOJI_API = 'https://www.nl.go.kr/seoji/SearchApi.do'
KAKAO_BOOK_API = 'https://dapi.kakao.com/v3/search/book'
# the National Library of Korea's holdings search; its imageUrl is a path on NLK_COVER_BASE
NLK_SEARCH_API = 'https://www.nl.go.kr/NL/search/openApi/search.do'
NLK_COVER_BASE = 'https://cover.nl.go.kr/'
# covers from outside the collected catalog (the ISBN bibliography and holdings, Kakao's Daum CDN, a publisher's page
# put in by hand: 「가족이라는 사치」 from Gimm-Young, user 2026-10-17); they are not taken again when an edition changes
OUTSIDE_COVER_HOSTS = ('www.nl.go.kr', 'nl.go.kr', 'cover.nl.go.kr', 't1.daumcdn.net', 'search1.kakaocdn.net',
                       'www.gimmyoung.com')
USER_AGENT = 'chaekcheck-collector/1.0 (+https://github.com/; contact via repository)'

# rules-index.md: 표지 크기 (ADR-0091, the server's chaekcheck.picks.fetch.cover)
COVER_WIDTH = 200
COVER_QUALITY = 80
COVER_MAX_BYTES = 5 * 1024 * 1024
REQUEST_INTERVAL = 1.0

LEEDONGJIN_COLUMNS = ['month', 'position', 'year_book', 'video_id', 'field', 'title', 'author', 'publisher', 'isbn13',
                      'cover_url', 'pinned_title', 'pinned_author', 'pinned_publisher', 'pinned_isbn13', 'pinned_by',
                      'checked_on', 'note']
SNU_COLUMNS = ['toc_no', 'field', 'title', 'author', 'cover_url', 'pinned_title', 'pinned_author', 'pinned_publisher',
               'pinned_isbn13', 'pinned_by', 'note']
KAIST_COLUMNS = ['field', 'position', 'title', 'author', 'publisher', 'isbn13', 'cover_url', 'pinned_title',
                 'pinned_author', 'pinned_publisher', 'pinned_isbn13', 'pinned_by', 'note']
# the Bokklubben World Library, "노벨연구소 선정 최고의 책" (user 2026-10-20): KAIST's columns without its year
NOBEL_COLUMNS = ['field', 'position', 'title', 'author', 'publisher', 'isbn13', 'cover_url', 'pinned_title',
                 'pinned_author', 'pinned_publisher', 'pinned_isbn13', 'pinned_by', 'note']
# the list files besides leedongjin.tsv and snu100.tsv; each may be missing
OPTIONAL_LISTS = {'kaist': KAIST_COLUMNS, 'nobel': NOBEL_COLUMNS}
LIST_NAMES = ('leedongjin', 'snu100', 'kaist', 'nobel')
PENDING_COLUMNS = ['video_id', 'month', 'reason', 'title', 'author', 'isbn13', 'field_suggestion']

BOOK_NAME = re.compile(r'\[([^\[\]]+)\]|《([^《》]+)》|〈([^〈〉]+)〉|『([^『』]+)』|🔖\s*([^\n]+)')

# rules-index.md: 화면 책 이름 — ShownTitle (Java) reading of the volume, for the sets and the first volume (ADR-0125)
SERIES = re.compile(r'^\[[^\]]*\]\s*')
LAST_PARENTHESES = re.compile(r'\s*[(\[]([^()\[\]]*)[)\]]$')
DASH_SUBTITLE = re.compile(r'\s+-\s+.*$')
PART_AT_END = re.compile(r'(?:[.,]\s*|\s+)(\d{1,2})\s*부\s*(\d{1,2})\s*권$')
GLUED_AT_END = re.compile(r'(?<=[가-힣])(\d{1,2})$')
ORIGINAL_EDITION = re.compile(r'\s+(?:오리지널판|세트)$')
VOLUME_INSIDE = re.compile(r'(?:제\s*)?(\d{1,2}|상|중|하)\s*권?')
VOLUME_AT_END = re.compile(r'(?:[.,]\s*|\s+)(?:제\s*)?(\d{1,2}|상|중|하)\s*권?$|(?<=\D)(\d{1,2})권$')


class CollectorFailure(Exception):
    """An answer the run cannot go on with: the files stay as they were."""


# ---------------------------------------------------------------- rules files (docs/maintenance/rules-index.md)

@dataclass
class Rules:
    """
    The collector's words and orders, read from the lists folder so they change without code (user note 2026-10-16:
    "다른 ai가 잘 파악하게"): collector_words.tsv (kind, word, note) and cover_hosts.tsv (order, host, note). The field
    suggestions are subject_fields.tsv.
    """
    year_book_title: tuple[str, ...]   # an upload's December title with one of them: a book-of-the-year video
    not_a_book: tuple[str, ...]        # the channel's own labels, compared without spaces, never a book name
    excluded_title: tuple[str, ...]    # editions never chosen (R3-3, R3-4)
    excluded_publisher: tuple[str, ...]
    excluded_name: tuple[str, ...]
    cover_hosts: tuple[str, ...]       # collected cover addresses tried first, in order (decision #6)
    cover_sources: tuple[str, ...]     # where covers come from, in order (cover_sources.tsv)
    placeholders: tuple[int, ...] = ()  # "no image" pictures (cover_placeholders.tsv), as image_hash values

    def is_placeholder(self, image: bytes) -> bool:
        mark = image_hash(image)
        return any(bin(mark ^ known).count('1') <= PLACEHOLDER_DISTANCE for known in self.placeholders)

    def year_book(self, title: str) -> bool:
        return any(word in (title or '') for word in self.year_book_title)

    def is_label(self, name: str) -> bool:
        squeezed = unicodedata.normalize('NFKC', name).replace(' ', '').lower()
        return any(word.replace(' ', '').lower() in squeezed for word in self.not_a_book)


WORD_KINDS = ('year_book_title', 'not_a_book', 'excluded_title', 'excluded_publisher', 'excluded_name')
# rules-index.md: 빈 표지 그림 — at most this many of the 256 bits may differ from a known "no image" picture
PLACEHOLDER_DISTANCE = 24


def image_hash(image: bytes) -> int:
    """16x16 grey average hash: the same picture made smaller or recompressed gives (nearly) the same 256 bits."""
    from PIL import Image
    with Image.open(io.BytesIO(image)) as picture:
        small = picture.convert('L').resize((16, 16), Image.LANCZOS)
        pixels = list(small.getdata())
    mean = sum(pixels) / len(pixels)
    return sum(1 << i for i, value in enumerate(pixels) if value > mean)


# rules-index.md: 표지 출처 차례 — the sources the collector knows (cover_sources.tsv picks and orders them)
COVER_SOURCES = ('nlk_isbn', 'collected_preferred', 'collected_other', 'nlk_holdings', 'kakao_book', 'nlk_title')
_rules: Rules | None = None


def load_rules(lists_dir: Path) -> Rules:
    """Reads the two rules files; a missing file or an unknown kind stops the run before any request."""
    header, rows = read_tsv(lists_dir / 'collector_words.tsv')
    if header[:2] != ['kind', 'word']:
        raise CollectorFailure(f'{lists_dir / "collector_words.tsv"}: missing or without the columns kind, word')
    words = {kind: [] for kind in WORD_KINDS}
    for row in rows:
        if row['kind'] not in words:
            raise CollectorFailure(f'collector_words.tsv: unknown kind {row["kind"]}')
        words[row['kind']].append(row['word'])
    header, hosts = read_tsv(lists_dir / 'cover_hosts.tsv')
    if header[:2] != ['order', 'host']:
        raise CollectorFailure(f'{lists_dir / "cover_hosts.tsv"}: missing or without the columns order, host')
    hosts.sort(key=lambda h: int(h['order']))
    header, sources = read_tsv(lists_dir / 'cover_sources.tsv')
    if header[:2] != ['order', 'source']:
        raise CollectorFailure(f'{lists_dir / "cover_sources.tsv"}: missing or without the columns order, source')
    unknown = [r['source'] for r in sources if r['source'] not in COVER_SOURCES]
    if unknown:
        raise CollectorFailure(f'cover_sources.tsv: unknown source {unknown[0]} (known: {", ".join(COVER_SOURCES)})')
    sources.sort(key=lambda r: int(r['order']))
    _, placeholders = read_tsv(lists_dir / 'cover_placeholders.tsv')
    return Rules(*(tuple(words[kind]) for kind in WORD_KINDS), tuple(h['host'] for h in hosts),
                 tuple(r['source'] for r in sources), tuple(int(r['hash'], 16) for r in placeholders))


def rules() -> Rules:
    global _rules
    if _rules is None:
        _rules = load_rules(LISTS_DIR)
    return _rules


# ---------------------------------------------------------------- log and requests

class Log:
    def __init__(self, path: Path | None):
        self.path = path
        self.lines: list[str] = []

    def line(self, text: str) -> None:
        stamp = dt.datetime.now().astimezone().isoformat(timespec='seconds')
        self.lines.append(text)
        try:
            print(text)
        except UnicodeEncodeError:  # a Windows console (cp949) and a title with a character it lacks (2026-10-20)
            print(text.encode('ascii', 'replace').decode('ascii'))
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
        return self.get_with(url, {}, max_bytes)

    def get_with(self, url: str, headers: dict, max_bytes: int | None = None) -> tuple[int, str, bytes]:
        parts = urllib.parse.urlsplit(url)
        host = parts.hostname or ''
        wait = REQUEST_INTERVAL - (time.monotonic() - self.last.get(host, -1e9))
        if wait > 0:
            self.sleep(wait)
        self.hosts.append(host)
        self.log.line(f'GET {host} {parts.path}')
        request = urllib.request.Request(url, headers=dict(headers, **{'User-Agent': USER_AGENT}))
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
    m = DASH_SUBTITLE.search(text)
    if m and m.start() > 0:
        text = text[:m.start()]
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
        part, end, glued = PART_AT_END.search(text), VOLUME_AT_END.search(text), GLUED_AT_END.search(text)
        if part and part.start() > 0:
            volume = f'{int(part.group(1))}부 {int(part.group(2))}권'
            text = text[:part.start()]
        elif end and end.start() > 0:
            volume = end.group(1) or end.group(2)
            text = text[:end.start()]
        elif glued and glued.start() > 1:
            volume = glued.group(1)
            text = text[:glued.start()]
    if volume and volume.isdigit():
        volume = str(int(volume))
    return re.sub(r'[\s.,:;·\-]+$', '', text), volume


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


def volume_order(volume: str) -> int:
    """상 = 1, 중 = 2, 하 = 3, a number as itself, '2부 3권' = 203 (Java BookChoice.volumeOrder)."""
    if '부 ' in volume:
        part, rest = volume.split('부 ')
        return int(part) * 100 + int(rest.rstrip('권'))
    return {'상': 1, '중': 2, '하': 3}.get(volume) or int(volume)


# rules-index.md: 판 고르기
def choose(candidates: list[dict], kinds: dict[int, bool]) -> dict | None:
    """
    The same rule as Java's BookChoice (ADR-0125, plan scope decision #7): a set (the same title without edition marks
    and volume, the same publisher, only the volume differing) is one edition, held as its volume held most and shown
    by its first volume (1 or 상, else the smallest); the edition held most wins, then the smaller id.
    """
    editions = []
    sets: dict[tuple[str, str], list[dict]] = {}
    for book in candidates:
        base, volume = read_title(book['title'])
        if volume is None:
            editions.append((held(book['library_ids'], kinds), book))
        else:
            sets.setdefault((text_key(base), text_key(book.get('publisher') or '')), []).append(book)
    for members in sets.values():
        first = min(members, key=lambda b: (volume_order(volume_of(b['title'])), b['id']))
        editions.append((max(held(b['library_ids'], kinds) for b in members), first))
    if not editions:
        return None
    return min(editions, key=lambda e: (-e[0], e[1]['id']))[1]


def excluded(book: dict) -> bool:
    title = book['title'] or ''
    if any(word in title for word in rules().excluded_title):
        return True
    if any(word in (book.get('publisher') or '') for word in rules().excluded_publisher):
        return True
    return any(word.lower() in (name or '').lower() for word in rules().excluded_name for name in book.get('names', []))


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

    def by_title_base(self, title: str) -> list[dict]:
        """
        Books whose title without its edition marks and volume is the given one (「한밤의 아이들. 1」 for 「한밤의
        아이들」; user 2026-10-20, the Bokklubben list names a work, not an edition).
        """
        base = text_key(title_base(title))
        if not base:
            return []
        found = self._books("b.main_title_norm LIKE %s", (base.replace('%', r'\%').replace('_', r'\_') + '%',))
        return [b for b in found if text_key(title_base(b['title'])) == base]

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
        kept = [b for b in found if b['authors'] & anchor['authors']]
        # the other volumes of a kept set, though their author is written another way (the same publisher's set is one
        # edition: 더클래식 「카라마조프가의 형제들. 1」 has 「도스토옙스키」, its volume 2 「표도르 도스토옙스키」; plan
        # matching-guide.md 6절, 2026-10-17)
        sets = {text_key(b['publisher'] or '') for b in kept if volume_of(b['title'])}
        known = {b['id'] for b in kept}
        return kept + [b for b in found if b['id'] not in known and volume_of(b['title'])
                       and text_key(b['publisher'] or '') in sets]

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
            if not anchors:
                # no book has the list's title as it is: the volumes of a set or a title with edition marks
                # (「백년 동안의 고독 - 혜원세계문학 50 _ 1」; user 2026-10-20)
                anchors = [b for b in catalog.by_title_base(row['title']) if author in b['authors']]
        if row.get('pinned_by') == 'manual':
            if anchors:
                chosen[index] = choose(anchors, kinds)['id']
            continue
        candidates: dict[int, dict] = {}
        for anchor in anchors:
            for book in catalog.same_book(anchor):
                candidates[book['id']] = book
        # same main title + a shared author only (R3-3). When every such edition is excluded (an adaptation for
        # children, a summary, a machine translation, R3-4) the row is left unmatched: no re-pick by the title alone,
        # which could land on another author's book (plan retro 21 decision #2, matching-guide.md 2-2, 4)
        best = choose([b for b in candidates.values() if not excluded(b)], kinds)
        name = row.get('title')
        if best is None:
            if candidates:
                log.line(f'edition: every same-author edition excluded for {name}; left unmatched (pin one as manual '
                         f'if a right edition exists)')
            elif row.get('pinned_title') or row.get('pinned_isbn13'):
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
            if name and not rules().is_label(name) and name not in names:
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

    def cover_by_title(self, title: str) -> str | None:
        for doc in self.by_title(title):
            url = (doc.get('TITLE_URL') or '').strip()
            if url:
                return https(url)
        return None

    def cover_url(self, isbn: str) -> str | None:
        for doc in self.search(isbn=isbn):
            url = (doc.get('TITLE_URL') or '').strip()
            if url:
                return 'https://' + url[len('http://'):] if url.startswith('http://') else url
        return None


class NlkHoldings:
    """
    The National Library of Korea's holdings search (www.nl.go.kr/NL/search/openApi/search.do, the same key as the ISBN
    bibliography): a cover for a book our catalog does not have, in place of Kakao book search, which may not be kept
    long (user 2026-10-17: "도서관정보나루라던가 아니면 공공도서관 api라던가 출처 많자나 … 그걸로 다시 조사하고 반영해봐";
    plan research cover-copyright.md 5). Its imageUrl is a path on cover.nl.go.kr.
    """

    def __init__(self, http: Http, key: str):
        self.http = http
        self.key = key

    def _search(self, **params) -> list[dict]:
        data = self.http.json(NLK_SEARCH_API, dict(params, key=self.key, apiType='json', pageSize=30))
        return data.get('result', []) or []

    @staticmethod
    def main_title(document: dict) -> str:
        title = re.sub(r'<[^>]+>', '', document.get('titleInfo') or '')
        return text_key(re.split(r'[:(]', title)[0])

    def covers(self, isbn: str | None, title: str | None, author: str | None, limit: int = 5) -> list[str]:
        """
        Cover addresses in order: by ISBN when there is one; then the title by the author, the exact main title first,
        then containing it. Unlike Kakao there is no "same title by anyone": the holdings have many other books with
        a classic's name (「광장」 is also an anthology), so a book whose author does not show stays a title card.
        """
        documents = []
        if isbn:
            documents += self._search(detailSearch='true', isbnOp='isbn', isbnCode=isbn)
        if title and author and author.split():
            key = text_key(title)
            # the family name (the last word) of the first author, as in KakaoBooks
            author_key = text_key(re.split(r'[,·/;]', author)[0].split()[-1])
            found = [d for d in self._search(srchTarget='title', kwd=title, category='도서')
                     if author_key and author_key in text_key(d.get('authorInfo') or '')]
            documents += [d for d in found if self.main_title(d) == key] + [d for d in found if key in self.main_title(d)]
        urls = []
        for document in documents:
            path = (document.get('imageUrl') or '').strip().lstrip('/')
            if path and NLK_COVER_BASE + path not in urls:
                urls.append(NLK_COVER_BASE + path)
        return urls[:limit]


class KakaoBooks:
    """
    Kakao book search (dapi.kakao.com/v3/search/book, REST key KAKAO_REST_API_KEY): a cover for a book our catalog
    does not have, or whose catalog covers did not load (user 2026-10-16: "책 표지 무조건 다 보였으면 해 인터넷 수집해서").
    The thumbnail's fname is the original image on the Daum CDN; that one is taken (the thumbnail is 120px wide).
    """

    def __init__(self, http: Http, key: str):
        self.http = http
        self.key = key

    def _search(self, **params) -> list[dict]:
        url = KAKAO_BOOK_API + '?' + urllib.parse.urlencode(dict(params, size=50))
        status, _, body = self.http.get_with(url, {'Authorization': 'KakaoAK ' + self.key})
        if status != 200:
            raise CollectorFailure(f'dapi.kakao.com: HTTP {status}')
        return json.loads(body.decode('utf-8')).get('documents', [])

    @staticmethod
    def image(document: dict) -> str | None:
        thumbnail = document.get('thumbnail') or ''
        if not thumbnail:
            return None
        original = urllib.parse.parse_qs(urllib.parse.urlsplit(thumbnail).query).get('fname', [None])[0]
        return https(original or thumbnail)

    def covers(self, isbn: str | None, title: str | None, author: str | None, limit: int = 5) -> list[str]:
        """
        Cover addresses in order: by ISBN when there is one; then the title, the exact main title first, then
        containing it, by the author; then the same title by anyone. A "no image" one is skipped later, so a few.
        """
        documents = []
        if isbn:
            documents += self._search(query=isbn, target='isbn')
        if title:
            key = text_key(title)
            # the family name (the last word) of the first author: 「제러미 리프킨」 also matches 「제레미 리프킨」
            author_key = text_key(re.split(r'[,·/;]', author or '')[0].split()[-1]) if author and author.split() else ''
            found = self._search(query=title, target='title')
            by_author = [d for d in found if not author_key or any(author_key in text_key(a) for a in d.get('authors', []))]

            def same_title(d):
                return text_key(re.split(r'[:(]', d.get('title') or '')[0]) == key
            # last: the same title by anyone (a classic Kakao names by its translator, 「보조법어」 by 김탄허)
            documents += [d for d in by_author if same_title(d)] + [d for d in by_author if key in text_key(d.get('title') or '')]                 + [d for d in found if same_title(d)]
        urls = []
        for document in documents:
            url = self.image(document)
            if url and url not in urls:
                urls.append(url)
        return urls[:limit]


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
    # the list starts with its first month (research 2023-12, spec R3-5): older videos are not looked at [안]
    first_month = min((r['month'] for r in rows), default='0000-00')
    monthly_ids = {v.video_id for v in monthly}
    # December uploads outside the playlist whose title has a year-book word (collector_words.tsv; widened to "올해의"
    # 2026-10-16: a "올해의 영화" video may come in too, a person sorts it out in the pending file)
    year_books = [v for v in uploads if v.public and v.video_id not in monthly_ids and v.published[5:7] == '12'
                  and rules().year_book(v.title)]
    for video, year_book in [(v, None) for v in monthly if v.public] + [(v, v.published[:4]) for v in year_books]:
        if video.video_id in listed:
            continue
        month = f'{video.published[:4]}-12' if year_book else video_month(video)
        if month < first_month:
            continue
        if year_book is None and rules().year_book(video.title) and month.endswith('-12'):
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
    order = {host: i for i, host in enumerate(rules().cover_hosts)}
    preferred = sorted((u for u in urls if urllib.parse.urlsplit(u).hostname in order),
                       key=lambda u: order[urllib.parse.urlsplit(u).hostname])
    return preferred + [u for u in urls if u not in preferred]


def fetch_covers(lists_dir: Path, rows: list[dict], chosen: dict[int, int], catalog: Catalog | None,
                 sqlite: sqlite3.Connection | None, seoji: Seoji | None, http: Http, log: Log,
                 kakao: KakaoBooks | None = None, attempts: int = 5) -> None:
    """
    Every row gets a cover when any source has one (user 2026-10-16), in the order of cover_sources.tsv: the ISBN
    bibliography's cover of the edition (or of the row's ISBN), the collected catalog's covers of our edition (YES24 and
    Kyobo first, then the others), then the NLK holdings search. A row our catalog does not have uses the first and the
    last. Kakao book search is not in the order since 2026-10-17 (its thumbnails may not be kept long).
    """
    for index, row in enumerate(rows):
        book_id = chosen.get(index)
        isbn = row.get('pinned_isbn13') or row.get('isbn13')
        # cover_title: the title to search a cover by when the list's title is not a book's (e.g. a four-book set)
        search_title = row.get('cover_title') or row.get('title')
        collected = []
        if sqlite is not None and catalog is not None and book_id is not None:
            collected = collected_covers(sqlite, catalog.holding_keys(book_id))
        current = row.get('cover_url')
        # keep the cover made before, unless the edition changed: a catalog cover that is not this edition's is
        # taken again (「토지」 went from volume 2 to volume 1); a cover from search stays (it is checked by hand)
        stale = current and collected and urllib.parse.urlsplit(current).hostname not in OUTSIDE_COVER_HOSTS \
            and current not in collected
        if current and cover_file(lists_dir, current).exists() and not stale:
            if not rules().is_placeholder(cover_file(lists_dir, current).read_bytes()):
                continue
        order = {host: i for i, host in enumerate(rules().cover_hosts)}
        by_source = {
            'nlk_isbn': lambda: [seoji.cover_url(isbn)] if seoji and isbn else [],
            'collected_preferred': lambda: [u for u in collected if urllib.parse.urlsplit(u).hostname in order],
            'collected_other': lambda: [u for u in collected if urllib.parse.urlsplit(u).hostname not in order],
            'nlk_holdings': lambda: NlkHoldings(seoji.http, seoji.key).covers(isbn, search_title, row.get('author'))
            if seoji else [],
            'kakao_book': lambda: kakao.covers(isbn, search_title, row.get('author')) if kakao else [],
            'nlk_title': lambda: [seoji.cover_by_title(search_title)] if seoji and search_title else [],
        }
        row['cover_url'] = ''
        for source in rules().cover_sources:
            if row['cover_url']:
                break
            tried = 0  # at most `attempts` addresses of one source
            try:
                urls = [u for u in by_source[source]() if u]
            except Exception as ex:  # noqa: BLE001 - one odd answer must not stop a 2,500-row run (2026-10-20)
                log.line(f'cover: {source} lookup failed ({ex.__class__.__name__}: {ex})')
                continue
            for url in urls:
                if tried >= attempts:
                    break
                if cover_file(lists_dir, url).exists():  # made by an earlier run: no request
                    if rules().is_placeholder(cover_file(lists_dir, url).read_bytes()):
                        continue
                    row['cover_url'] = url
                    break
                tried += 1
                try:
                    status, _, body = http.get(url, COVER_MAX_BYTES)
                    if status != 200:
                        continue
                    small = shrink(body)
                    if rules().is_placeholder(small):
                        log.line(f'cover: {urllib.parse.urlsplit(url).hostname} gave a "no image" picture')
                        continue
                    path = cover_file(lists_dir, url)
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(small)
                    row['cover_url'] = url
                    log.line(f"cover: {row.get('title')} from {source}")
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
    global _rules
    lists_dir = Path(args.lists_dir)
    env = read_env(Path(args.env))
    youtube_key = env.get('CHAEKCHECK_YOUTUBE_API_KEY', '')
    seoji_key = env.get('CHAEKCHECK_PICKS_NLK_API_KEY', '')
    log = Log(Path(args.log) if args.log else None)
    http = Http(log, [youtube_key, seoji_key, env.get('KAKAO_REST_API_KEY', '')], sleep=sleep, opener=http_opener)
    today = args.today or dt.date.today().isoformat()

    lee_header, lee = read_tsv(lists_dir / 'leedongjin.tsv')
    snu_header, snu = read_tsv(lists_dir / 'snu100.tsv')
    optional = {}
    for name in OPTIONAL_LISTS:
        path = lists_dir / f'{name}.tsv'
        optional[name] = read_tsv(path) if path.exists() else ([], [])
    kaist = optional['kaist'][1]
    nobel = optional['nobel'][1]
    # the lists this run works on (user 2026-10-20: another session may be rebuilding one of them)
    working = set(LIST_NAMES if not getattr(args, 'lists', None) else args.lists.split(','))
    unknown = working - set(LIST_NAMES)
    if unknown:
        log.line(f'lists: {sorted(unknown)} unknown; nothing was asked or changed')
        return 1
    _, pending = read_tsv(lists_dir / 'leedongjin_pending.tsv')
    _, subjects = read_tsv(lists_dir / 'subject_fields.tsv')
    subject_fields = {s['subject']: s['field_suggestion'] for s in subjects}
    seoji = Seoji(http, seoji_key) if seoji_key else None
    kakao_key = env.get('KAKAO_REST_API_KEY', '')
    kakao = KakaoBooks(http, kakao_key) if kakao_key else None
    status = 0
    try:
        _rules = load_rules(lists_dir)
    except CollectorFailure as ex:
        log.line(f'rules: {ex}; nothing was asked or changed')
        return 1

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
    chosen_kaist: dict[int, int] = {}
    chosen_nobel: dict[int, int] = {}
    rows_of = {'leedongjin': lee, 'snu100': snu, 'kaist': kaist, 'nobel': nobel}
    if not args.skip_editions:
        try:
            catalog = catalog or Catalog.connect(env, args.db_port)
            kinds = catalog.kinds()
            chosen = {name: choose_editions(rows_of[name], catalog, kinds, log) if name in working else {}
                      for name in LIST_NAMES}
            chosen_lee, chosen_snu = chosen['leedongjin'], chosen['snu100']
            chosen_kaist, chosen_nobel = chosen['kaist'], chosen['nobel']
            log.line('editions: ' + ', '.join(f'{name} {len(chosen[name])}/{len(rows_of[name])}'
                                              for name in LIST_NAMES if name in working))
        except Exception as ex:  # a database that does not answer keeps the fixed editions as they were
            log.line(f'editions: database failed, the fixed editions stay as they were ({ex.__class__.__name__})')
            catalog = None
            status = 1

    if not args.skip_covers:
        if sqlite is None and args.sqlite:
            sqlite = sqlite3.connect(f'file:{Path(args.sqlite).as_posix()}?mode=ro', uri=True)
        chosen_of = {'leedongjin': chosen_lee, 'snu100': chosen_snu, 'kaist': chosen_kaist, 'nobel': chosen_nobel}
        for name in LIST_NAMES:
            if name in working:
                fetch_covers(lists_dir, rows_of[name], chosen_of[name], catalog, sqlite, seoji, http, log, kakao)
        # every list's covers are kept, the ones this run did not work on too
        log.line(f'covers: {remove_unused_covers(lists_dir, lee + snu + kaist + nobel)} unused files removed')

    if 'leedongjin' in working or not args.skip_youtube:
        write_tsv(lists_dir / 'leedongjin.tsv', lee_header or LEEDONGJIN_COLUMNS, lee)
    if 'snu100' in working:
        write_tsv(lists_dir / 'snu100.tsv', snu_header or SNU_COLUMNS, snu)
    for name, columns in OPTIONAL_LISTS.items():
        header, rows = optional[name]
        if rows and name in working:
            write_tsv(lists_dir / f'{name}.tsv', header or columns, rows)
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
    parser.add_argument('--lists', help='comma-separated lists to choose editions and covers for (default: all of '
                        + ', '.join(LIST_NAMES) + '); the other list files are left as they are (user 2026-10-20)')
    return run(parser.parse_args(argv))


if __name__ == '__main__':
    sys.exit(main())
