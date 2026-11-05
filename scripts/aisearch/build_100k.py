"""The 100,000 books held by the most libraries (user 2026-10-26: "도서관 보유량 많은 거 순으로 10만권까지 임베딩 벡터",
"그 책들도 … 하이쿠 배치로 ai요약", "cycle 24 참고해서 프로필", "어지간한 작업은 하이쿠 배치로 … api써서",
"소개글 없는 건 따로 빼줘"): their list intros, and with Claude Haiku 5.5 through the Message Batches API the three
texts the 30,000-book round made, with the same instructions:

  search_line    AI search line (scripts/aisearch/make_search_lines.py SYSTEM; agent RULES.md "-" for no content)
  for_you_blurb  AI summary shown on the tab and the book window (scripts/crawl/foryou_blurbs.py SYSTEM and checks)
  profile        detailed profile for the ML first call (scripts/aisearch/eval/profile-RULES-detailed.md,
                 docs/features/aisearch/eval-data/taxonomy2.json), as JSON

No request to any library site: intros come from the collected DB (read-only), KDC from the national bibliography TTL
on this PC, books from the PC's service DB (SELECT only). The intro text stays on the PC; only the made texts go to the
service later. A book without an intro of INTRO_MIN characters is left out of every step and listed apart.

Run from the repository root (any python with psycopg for prepare/kdc; the data folder's venv for the rest):

  python -X utf8 scripts/aisearch/build_100k.py prepare        # targets, intros, left-out list, band counts
  python -X utf8 scripts/aisearch/build_100k.py kdc            # KDC and audience note by ISBN (TTL, minutes)
  <venv>/python -X utf8 scripts/aisearch/build_100k.py requests  # request files per kind (no API call)
  <venv>/python -X utf8 scripts/aisearch/build_100k.py submit    # sends the batches (costs money)
  <venv>/python -X utf8 scripts/aisearch/build_100k.py collect   # waits, checks, writes the files
  <venv>/python -X utf8 scripts/aisearch/build_100k.py retry     # asks again for the dropped books (then collect)
  python -X utf8 scripts/aisearch/build_100k.py profiles-tsv   # book-profiles.tsv for the first-call model folder
  python -X utf8 scripts/aisearch/build_100k.py book-texts     # the AI search import file (lines, exclude, blurbs)

profiles-tsv and book-texts each write two files: the plain one for today's readers and <name>-export.tsv whose first
line is `# export_id=<uuid of the PC DB's last catalog import>` for cycle 26's catalog import modes (write_both).

Files (data folder, outside git): aisearch/100k/ targets.tsv, left-out-no-intro.tsv, bands.json, nlk-kdc.tsv,
requests-<kind>.jsonl, batches.json, search-lines.tsv, profiles-detailed.jsonl, book-profiles.tsv, report.json;
ml/intro-books-100k.tsv; ml/foryou/book-texts-for_you_blurb-100k.tsv.
"""
from __future__ import annotations

import datetime as dt
import json
import os
import re
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DATA = Path(os.environ.get('CHAEKCHECK_DATA') or REPO.parent / 'chaekcheck-data')
OUT = DATA / 'aisearch' / '100k'
ML = DATA / 'ml'
ENV_FILE = Path(os.environ.get('CHAEKCHECK_ENV') or (REPO / '.env' if (REPO / '.env').exists()
                                                     else REPO.parent / 'chaekcheck-dev' / '.env'))
TTL_DIR = REPO.parent / 'chaekcheck-dev' / 'data' / 'nlk' / 'ttl' / 'book_ttl_20260807'
TOP = 100_000
INTRO_MIN = 50          # [안] characters; the cycle 23 session counted "소개 50자 이상" the same way (2026-10-26)
MODEL = 'claude-haiku-5-5'
CHUNK = 10_000          # requests per batch (a batch takes at most 100,000 requests or 256 MB)
PRICE_IN, PRICE_OUT = 0.10 / 2, 0.50 / 2   # US$ per million tokens, Haiku 5.5, batch half price (skill 2026-10-06)
KINDS = ('search_line', 'for_you_blurb', 'profile')

# ---- search_line: make_search_lines.py SYSTEM, with the agent rule for an intro without content (RULES.md)
LINE_INTRO_CHARS = 1200
LINE_SYSTEM = """너는 전자책 검색 서비스의 색인 작성자다. 책 소개 원문을 읽고, 이 책을 찾는 사람이 검색창에 쓸 만한 말이 들어간 \
찾기용 한 줄을 쓴다.

규칙:
- 한국어 한두 문장, 80~150자.
- 무엇을 다루는 책인지(분야·주제·장르), 어떤 내용인지, 어떤 분위기이거나 누구에게 맞는지를 담는다.
- 사람들이 실제로 쓸 낱말을 쓴다(예: 위로, 성장, 추리, 경제 입문, 육아, 조선 역사, 과학 교양, 연애, 직장 생활).
- 소개에 없는 내용은 지어내지 않는다. 줄거리의 결말은 밝히지 않는다.
- 제목·저자 이름을 되풀이하지 않는다. "이 책은", 광고 문구, 수상·판매량·추천사는 쓰지 않는다.
- 줄바꿈·탭을 쓰지 않는다.
- 소개에 내용이 없으면(제목·권차·"."·전자책 정보뿐, 또는 다른 책 소개) 한 줄 대신 - 하나만 쓴다. \
"소개 정보가 없다", "확인되지 않는다" 같은 말은 쓰지 않는다."""
LINE_SCHEMA = {'type': 'object', 'properties': {'line': {'type': 'string'}}, 'required': ['line'],
               'additionalProperties': False}

# ---- for_you_blurb: foryou_blurbs.py SYSTEM and checks (R8-4)
BLURB_INTRO_CHARS = 1500
BLURB_SYSTEM = """당신은 전자도서관 책 추천 탭에 들어갈 짧은 소개 글을 씁니다.
규칙:
- 해요체로 한두 문장, 공백 포함 80~150자.
- 이 책이 무엇에 관한 책인지, 어떤 독자에게 맞는지 담담하게 알려 줍니다.
- 칭찬하지 않습니다("최고의", "감동적인", "꼭 읽어야 할", "베스트셀러" 같은 말 금지).
- 소설의 결말이나 반전을 말하지 않습니다.
- AI, 인공지능, 요약, 소개 원문 같은 말을 쓰지 않습니다.
- 주어진 소개 원문의 문장을 옮기지 않습니다. 원문과 8자 이상 연달아 같은 글자가 나오지 않게 자기 말로 다시 씁니다.
- 주어진 정보만으로 책 내용을 알 수 없으면 추측하지 말고 NONE 한 단어만 씁니다.
- 글만 씁니다. 따옴표, 머리말, 설명을 붙이지 않습니다."""
BLURB_MIN, BLURB_MAX, COPY_RUN = 80, 150, 8
# retry round (2026-10-26): at effort low most first answers ended in 합니다체 ("~책입니다."), which R8-4 drops; the books
# dropped for a checkable rule are asked again with the ending spelled out (the first round's rules unchanged)
BLURB_RETRY_RULE = """
- 모든 문장을 해요체 '~요'로 끝냅니다(예: ~책이에요, ~담았어요, ~어울려요). '~입니다', '~합니다', '~다.'로 끝내지 않습니다.
- 공백 포함 150자를 넘기지 않습니다. 소개 원문의 낱말을 길게 이어서 옮기지 않습니다.
- 띄어쓰기를 빼고 보아 소개 원문과 8글자 넘게 똑같이 이어지는 부분이 없어야 합니다. 긴 이름·용어·문구는 줄이거나 \
다른 말로 바꿔 씁니다."""
# (the last rule was added for the second retry round, 2026-10-26: 11,587 blurbs of the first retry copied 8+ characters)
RETRY_REASONS = {'search_line': {'stop_max_tokens', 'bad_json', 'length'},
                 'for_you_blurb': {'not_haeyo', 'copied', 'length', 'banned_word', 'stop_max_tokens', 'shape'},
                 'profile': {'stop_max_tokens', 'bad_json'}}
BANNED = re.compile(r'AI|인공지능|최고의|감동적|꼭 읽어야|베스트셀러|필독')

# ---- profile: profile-RULES-detailed.md as a system prompt, taxonomy2.json as enums
PROFILE_INTRO_CHARS = 600
TAXONOMY = json.loads((REPO / 'docs' / 'features' / 'aisearch' / 'eval-data' / 'taxonomy2.json').read_text(encoding='utf-8'))
PROFILE_SYSTEM = """책 프로필 만들기 — 상세 사전 (AI 책 찾기용)

책 한 권의 제목·저자·KDC·대상(국가서지)·소개(앞 600자)를 받아 아래 JSON 하나를 만든다. KDC·대상은 국립중앙도서관 \
국가서지 값이고 비어 있을 수 있다. 참고만 하고, 소개와 다르면 소개를 따른다. 소개·제목·저자·KDC에서 알 수 있는 것만 쓰고 \
지어내지 않는다. 모든 값은 아래 사전의 글자를 그대로 쓴다(사전에 없는 값은 쓰지 않는다).

- field: 사전 field의 키 하나(큰 칸)
- subfield: 그 큰 칸 목록 안의 값 하나(작은 칸). 큰 칸 목록이 비어 있으면(소설·기타) 빈 문자열
- fiction: 지어낸 이야기(소설·시·희곡·동화·만화 이야기)이면 true
- genre: fiction이 true일 때만 사전 genre에서 1~2개, 아니면 빈 배열
- country: 문학(소설·시·희곡·에세이·어린이/청소년 소설)일 때만 지은이의 나라를 사전 country에서 하나, 아니면 빈 문자열. \
KDC 81x=한국, 82x=중국, 83x=일본, 84x=영미, 85x=독일, 86x=프랑스, 89x 중 러시아 문학=러시아
- audience: 사전 audience에서 하나(ISBN 부가기호의 독자 대상과 같은 뜻: 일반 성인 교양서=교양, 생활·실용서=실용, \
청소년=청소년, 중고생 참고서=중고 학습, 초등 참고서=초등 학습, 어린이책=아동, 전공·전문서=전문)
- level: 사전 level에서 하나(모르면 교양)
- form: 사전 form에서 0~2개(해당할 때만)
- topics: 1~5개, 사람들이 찾을 때 쓸 한국어 낱말(사전 없음, 자유)
- mood: 사전 mood에서 0~2개
- 소개에 내용이 없으면 제목·저자·KDC만으로 field·fiction·audience를 정하고 나머지는 비운다

사전:
""" + json.dumps({k: v for k, v in TAXONOMY.items() if k != 'basis'}, ensure_ascii=False)
PROFILE_SCHEMA = {
    'type': 'object',
    'properties': {
        'field': {'type': 'string', 'enum': list(TAXONOMY['field'])},
        'subfield': {'type': 'string'},
        'fiction': {'type': 'boolean'},
        'genre': {'type': 'array', 'items': {'type': 'string', 'enum': TAXONOMY['genre']}},
        'country': {'type': 'string', 'enum': [''] + TAXONOMY['country']},
        'audience': {'type': 'string', 'enum': TAXONOMY['audience']},
        'level': {'type': 'string', 'enum': TAXONOMY['level']},
        'form': {'type': 'array', 'items': {'type': 'string', 'enum': TAXONOMY['form']}},
        'topics': {'type': 'array', 'items': {'type': 'string'}},
        'mood': {'type': 'array', 'items': {'type': 'string', 'enum': TAXONOMY['mood']}},
    },
    'required': ['field', 'subfield', 'fiction', 'genre', 'country', 'audience', 'level', 'form', 'topics', 'mood'],
    'additionalProperties': False,
}


def env() -> dict[str, str]:
    values = {}
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text(encoding='utf-8').splitlines():
            if '=' in line and not line.lstrip().startswith('#'):
                k, v = line.split('=', 1)
                values[k.strip()] = v.strip()
    return values


def db():
    import psycopg
    e = env()
    return psycopg.connect(host='localhost', port=e.get('DB_PORT', '5432'), dbname=e['DB_NAME'],
                           user=e['DB_USERNAME'], password=e.get('DB_PASSWORD', ''))


def clean(text: str | None) -> str:
    return ' '.join((text or '').split())


def year(published) -> str:
    return str(published)[:4] if published else ''


def read_tsv(path: Path) -> list[dict]:
    lines = path.read_text(encoding='utf-8').split('\n')
    head = lines[0].split('\t')
    return [dict(zip(head, line.split('\t'))) for line in lines[1:] if line]


# ---------------------------------------------------------------- prepare

def prepare() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    con = db()
    cur = con.cursor()
    cur.execute('SELECT id, title, author_display, publisher, published_on, isbn13, cardinality(library_ids) '
                'FROM catalog.book ORDER BY cardinality(library_ids) DESC, id LIMIT %s', (TOP,))
    books = cur.fetchall()
    ids = [b[0] for b in books]
    print(f'{len(books)} books, the last held by {books[-1][6]} libraries', flush=True)
    collected = sqlite3.connect(f'file:{(DATA / "ebook-catalog.sqlite").as_posix()}?mode=ro', uri=True, timeout=120)
    collected.execute('PRAGMA busy_timeout=120000')
    best: dict[int, tuple[str, str]] = {}
    started = time.monotonic()
    n = 0
    with con.cursor(name='build_100k_holdings') as hc:
        hc.itersize = 50_000
        hc.execute("""SELECT h.book_id, l.collection_library_id, h.item_key, h.platform FROM catalog.holding h
                      JOIN collection.collection_link l ON l.library_id = h.library_id WHERE h.book_id = ANY(%s)""",
                   (ids,))
        for book_id, lib, item, platform in hc:
            n += 1
            row = collected.execute('SELECT intro_raw FROM holding WHERE library_id = ? AND item_key = ?',
                                    (int(lib), item)).fetchone()
            if row and row[0]:
                text = clean(row[0])
                if len(text) > len(best.get(book_id, ('', ''))[1]):
                    best[book_id] = (platform, text)
            if n % 1_000_000 == 0:
                print(f'{n} holdings, {time.monotonic() - started:.0f}s', flush=True)
    con.rollback()
    print(f'{n} holdings read in {time.monotonic() - started:.0f}s', flush=True)
    with (ML / 'intro-books-100k.tsv').open('w', encoding='utf-8', newline='\n') as f:
        f.write('book_id\tplatform\tlength\tintro\n')
        for book_id in ids:
            if book_id in best:
                platform, text = best[book_id]
                f.write(f'{book_id}\t{platform}\t{len(text)}\t{text}\n')
    left_out = []
    with (OUT / 'targets.tsv').open('w', encoding='utf-8', newline='\n') as f:
        f.write('rank\tbook_id\ttitle\tauthor\tpublisher\tyear\tisbn13\tlibraries\tintro_chars\n')
        for rank, (book_id, title, author, publisher, published, isbn, libs) in enumerate(books, 1):
            chars = len(best.get(book_id, ('', ''))[1])
            f.write('\t'.join(map(str, (rank, book_id, clean(title), clean(author), clean(publisher), year(published),
                                        isbn or '', libs, chars))) + '\n')
            if chars < INTRO_MIN:
                left_out.append((rank, book_id, clean(title), clean(author), libs,
                                 'no_intro' if chars == 0 else f'short_intro_{chars}'))
    with (OUT / 'left-out-no-intro.tsv').open('w', encoding='utf-8', newline='\n') as f:
        f.write('rank\tbook_id\ttitle\tauthor\tlibraries\treason\n')
        for row in left_out:
            f.write('\t'.join(map(str, row)) + '\n')
    # covers (fetch-covers) and intros by band, for the cycle 23 session
    covers = sqlite3.connect(f'file:{(DATA / "covers" / "covers.sqlite").as_posix()}?mode=ro', uri=True, timeout=120)
    status = dict(covers.execute('SELECT book_id, status FROM book_cover').fetchall())
    bands = {}
    for name, lo, hi in (('1-30000', 1, 30_000), ('30001-50000', 30_001, 50_000), ('50001-100000', 50_001, TOP)):
        part = books[lo - 1:hi]
        bands[name] = {
            'books': len(part), 'min_libraries': part[-1][6],
            'intro_50_plus': sum(1 for b in part if len(best.get(b[0], ('', ''))[1]) >= INTRO_MIN),
            'cover_done': sum(1 for b in part if status.get(b[0]) == 'DONE'),
            'cover_pending': sum(1 for b in part if status.get(b[0]) == 'PENDING'),
            'cover_none': sum(1 for b in part if status.get(b[0]) == 'NONE'),
        }
    summary = {'made_at': dt.datetime.now().isoformat(timespec='seconds'), 'books': len(books),
               'with_intro': len(books) - len(left_out), 'left_out_no_intro': len(left_out), 'bands': bands}
    (OUT / 'bands.json').write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    return 0


# ---------------------------------------------------------------- kdc

def kdc() -> int:
    targets = read_tsv(OUT / 'targets.tsv')
    books = OUT / 'isbn-books.tsv'
    with books.open('w', encoding='utf-8', newline='\n') as f:
        for t in targets:
            if t['isbn13'] and int(t['intro_chars']) >= INTRO_MIN:
                f.write(f"{t['book_id']}\t{t['isbn13']}\n")
    script = REPO / 'scripts' / 'aisearch' / 'eval' / 'nlk_kdc.py'
    subprocess.run([sys.executable, '-X', 'utf8', str(script), str(TTL_DIR), str(books), str(OUT / 'nlk-kdc.tsv')],
                   check=True)
    return 0


# ---------------------------------------------------------------- requests

def done_ids(kind: str) -> set[int]:
    """Books that already have this text (the 30,000-book round), never asked again."""
    if kind == 'profile':
        path = DATA / 'aisearch' / 'book-profiles-detailed-2026-10-25.jsonl'
        return {json.loads(line)['id'] for line in path.read_text(encoding='utf-8').splitlines() if line.strip()}
    con = db()
    cur = con.cursor()
    if kind == 'search_line':
        cur.execute('SELECT book_id FROM aisearch.search_book WHERE line IS NOT NULL')
    else:
        cur.execute('SELECT book_id FROM foryou.book_blurb')
    ids = {r[0] for r in cur.fetchall()}
    con.close()
    return ids


def requests_() -> int:
    targets = [t for t in read_tsv(OUT / 'targets.tsv') if int(t['intro_chars']) >= INTRO_MIN]
    intros = {int(r['book_id']): r['intro'] for r in read_tsv(ML / 'intro-books-100k.tsv')}
    nlk = {}
    if (OUT / 'nlk-kdc.tsv').exists():
        for line in (OUT / 'nlk-kdc.tsv').read_text(encoding='utf-8').split('\n'):
            cells = line.split('\t')
            if len(cells) >= 5 and cells[0].isdigit():
                nlk[int(cells[0])] = (cells[2], cells[4])   # book_id, isbn, kdc, keywords, audience note, ...
    counts = {}
    for kind in KINDS:
        skip = done_ids(kind)
        n = 0
        with (OUT / f'requests-{kind}.jsonl').open('w', encoding='utf-8', newline='\n') as f:
            for t in targets:
                book_id = int(t['book_id'])
                if book_id in skip:
                    continue
                intro = intros[book_id]
                line = ' · '.join(p for p in (t['title'], t['author'], t['publisher'], t['year']) if p)
                if kind == 'search_line':
                    user = f"제목: {t['title']}\n저자: {t['author'] or '-'}\n출판사: {t['publisher'] or '-'}\n\n소개:\n" \
                           f"{intro[:LINE_INTRO_CHARS]}"
                elif kind == 'for_you_blurb':
                    user = f'책: {line}\n소개 원문:\n{intro[:BLURB_INTRO_CHARS]}'
                else:
                    k, aud = nlk.get(book_id, ('', ''))
                    user = (f"제목: {t['title']}\n저자: {t['author'] or '-'}\nKDC: {k or '-'}\n대상: {aud or '-'}\n"
                            f"소개: {intro[:PROFILE_INTRO_CHARS]}")
                f.write(json.dumps({'book_id': book_id, 'user': user}, ensure_ascii=False) + '\n')
                n += 1
        counts[kind] = {'requests': n, 'already_made': len(skip & {int(t['book_id']) for t in targets})}
    print(json.dumps(counts, ensure_ascii=False, indent=1))
    return 0


# ---------------------------------------------------------------- batches

def client():
    import anthropic
    key = os.environ.get('CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY') or env().get('CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY')
    if not key:
        raise SystemExit('CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY is not in the environment or .env')
    return anthropic.Anthropic(api_key=key)


def params(kind: str, user: str, retry: bool = False) -> dict:
    if kind == 'search_line':
        return dict(model=MODEL, max_tokens=1200 if retry else 400, system=LINE_SYSTEM,
                    messages=[{'role': 'user', 'content': user}],
                    output_config={'effort': 'low', 'format': {'type': 'json_schema', 'schema': LINE_SCHEMA}})
    if kind == 'for_you_blurb':
        return dict(model=MODEL, max_tokens=4000 if retry else 2000,
                    system=BLURB_SYSTEM + (BLURB_RETRY_RULE if retry else ''),
                    messages=[{'role': 'user', 'content': user}], output_config={'effort': 'low'})
    return dict(model=MODEL, max_tokens=3000 if retry else 1500,
                system=[{'type': 'text', 'text': PROFILE_SYSTEM, 'cache_control': {'type': 'ephemeral'}}],
                messages=[{'role': 'user', 'content': user}],
                output_config={'effort': 'low', 'format': {'type': 'json_schema', 'schema': PROFILE_SCHEMA}})


def submit() -> int:
    from anthropic.types.message_create_params import MessageCreateParamsNonStreaming
    from anthropic.types.messages.batch_create_params import Request
    c = client()
    record_path = OUT / 'batches.json'
    record = json.loads(record_path.read_text()) if record_path.exists() else {'batches': []}
    sent = {(b['kind'], b['part']) for b in record['batches']}
    for kind in KINDS:
        rows = [json.loads(line) for line in (OUT / f'requests-{kind}.jsonl').read_text(encoding='utf-8').splitlines()]
        for part, start in enumerate(range(0, len(rows), CHUNK)):
            if (kind, part) in sent:
                continue
            chunk = rows[start:start + CHUNK]
            reqs = [Request(custom_id=f"{kind.replace('_', '-')}-{r['book_id']}",
                            params=MessageCreateParamsNonStreaming(**params(kind, r['user']))) for r in chunk]
            batch = c.messages.batches.create(requests=reqs)
            record['batches'].append({'kind': kind, 'part': part, 'id': batch.id, 'requests': len(chunk),
                                      'submitted_at': dt.datetime.now().isoformat(timespec='seconds')})
            record_path.write_text(json.dumps(record, indent=1))
            print(f'{kind} part {part}: batch {batch.id}, {len(chunk)} requests', flush=True)
    return 0


def retry() -> int:
    """Asks again for the books the last collect dropped for a reason in RETRY_REASONS (retry parts in batches.json)."""
    from anthropic.types.message_create_params import MessageCreateParamsNonStreaming
    from anthropic.types.messages.batch_create_params import Request
    c = client()
    record_path = OUT / 'batches.json'
    record = json.loads(record_path.read_text())
    round_no = 1 + max((int(str(b['part']).split('-')[0][5:]) for b in record['batches']
                        if str(b['part']).startswith('retry')), default=0)
    for kind in KINDS:
        wanted = {int(r['book_id']) for r in read_tsv(OUT / f'dropped-{kind}.tsv') if r['reason'] in RETRY_REASONS[kind]}
        rows = [json.loads(line) for line in (OUT / f'requests-{kind}.jsonl').read_text(encoding='utf-8').splitlines()]
        rows = [r for r in rows if r['book_id'] in wanted]
        for part, start in enumerate(range(0, len(rows), CHUNK)):
            chunk = rows[start:start + CHUNK]
            reqs = [Request(custom_id=f"{kind.replace('_', '-')}-{r['book_id']}",
                            params=MessageCreateParamsNonStreaming(**params(kind, r['user'], retry=True))) for r in chunk]
            batch = c.messages.batches.create(requests=reqs)
            record['batches'].append({'kind': kind, 'part': f'retry{round_no}-{part}', 'id': batch.id,
                                      'requests': len(chunk),
                                      'submitted_at': dt.datetime.now().isoformat(timespec='seconds')})
            record_path.write_text(json.dumps(record, indent=1))
            print(f'{kind} retry{round_no}-{part}: batch {batch.id}, {len(chunk)} requests', flush=True)
    return 0


def copied_run(text: str, source: str, run: int = COPY_RUN) -> str | None:
    a = re.sub(r'\s+', '', text)
    b = re.sub(r'\s+', '', source or '')
    if len(a) < run or len(b) < run:
        return None
    grams = {b[i:i + run] for i in range(len(b) - run + 1)}
    return next((a[i:i + run] for i in range(len(a) - run + 1) if a[i:i + run] in grams), None)


def check_blurb(text: str, intro: str) -> str | None:
    if text.strip().upper() == 'NONE':
        return 'none'
    if not BLURB_MIN <= len(text) <= BLURB_MAX:
        return 'length'
    if '\n' in text or text.startswith(('"', '“', "'")):
        return 'shape'
    if BANNED.search(text):
        return 'banned_word'
    if not re.search(r'(요|죠)[.!?]?$', text):
        return 'not_haeyo'
    if copied_run(text, intro):
        return 'copied'
    return None


def check_profile(o: dict) -> tuple[dict, list[str]]:
    """Keeps the dictionary values only; returns the cleaned profile and what was fixed."""
    fixed = []
    subs = TAXONOMY['field'].get(o['field'], [])
    if o['subfield'] not in subs:
        if o['subfield']:
            fixed.append('subfield')
        o['subfield'] = subs[0] if False else ''
    if not o['fiction'] and o['genre']:
        o['genre'] = []
        fixed.append('genre_not_fiction')
    o['genre'] = o['genre'][:2]
    o['form'] = o['form'][:2]
    o['mood'] = o['mood'][:2]
    o['topics'] = [clean(t) for t in o['topics'] if clean(t)][:5]
    return o, fixed


def collect() -> int:
    c = client()
    record = json.loads((OUT / 'batches.json').read_text())
    while True:
        states = [c.messages.batches.retrieve(b['id']) for b in record['batches']]
        waiting = [s for s in states if s.processing_status != 'ended']
        if not waiting:
            break
        print(f'{dt.datetime.now():%H:%M:%S} {len(waiting)}/{len(states)} batches running, '
              f'{sum(s.request_counts.processing for s in waiting)} requests left', flush=True)
        time.sleep(120)
    intros = {int(r['book_id']): r['intro'] for r in read_tsv(ML / 'intro-books-100k.tsv')}
    made_on = dt.date.today().isoformat()
    ok = {k: {} for k in KINDS}            # book -> output line, the first answer that passes
    last_fail = {k: {} for k in KINDS}     # book -> why its last answer did not pass
    fixed_counts = {}
    failed = {k: {} for k in KINDS}
    usage = {k: [0, 0, 0, 0] for k in KINDS}   # input, cache read, cache write, output
    for b in record['batches']:
        kind = b['kind']
        for result in c.messages.batches.results(b['id']):
            book_id = int(result.custom_id.rsplit('-', 1)[1])
            if result.result.type != 'succeeded':
                failed[kind][result.result.type] = failed[kind].get(result.result.type, 0) + 1
                last_fail[kind].setdefault(book_id, result.result.type)
                continue
            msg = result.result.message
            u = usage[kind]
            u[0] += msg.usage.input_tokens
            u[1] += msg.usage.cache_read_input_tokens or 0
            u[2] += msg.usage.cache_creation_input_tokens or 0
            u[3] += msg.usage.output_tokens
            if book_id in ok[kind]:
                continue
            text = next((x.text for x in msg.content if x.type == 'text'), '').strip()
            why, line = None, None
            if msg.stop_reason != 'end_turn':
                why = f'stop_{msg.stop_reason}'
            elif kind == 'search_line':
                try:
                    found = clean(json.loads(text)['line'])
                except (ValueError, KeyError):
                    found, why = '', 'bad_json'
                if not why and found in ('-', ''):
                    why = 'no_content'
                elif not why and len(found) > 300:
                    why = 'length'
                if not why:
                    line = f'search_line\t{book_id}\tintro\t{made_on}\t{found}'
            elif kind == 'for_you_blurb':
                why = check_blurb(text, intros.get(book_id, ''))
                if not why:
                    line = f'for_you_blurb\t{book_id}\thaiku-batch:{b["id"]}\t{made_on}\t{text}'
            else:
                try:
                    profile, fixed = check_profile(json.loads(text))
                    for f in fixed:
                        fixed_counts[f] = fixed_counts.get(f, 0) + 1
                    line = json.dumps({'id': book_id, **profile}, ensure_ascii=False)
                except (ValueError, KeyError, TypeError):
                    why = 'bad_json'
            if line:
                ok[kind][book_id] = line
                last_fail[kind].pop(book_id, None)
            else:
                last_fail[kind][book_id] = why
    with (OUT / 'search-lines.tsv').open('w', encoding='utf-8', newline='\n') as f:
        f.write('kind\tbook_id\tsource\tmade_on\ttext\n' + ''.join(ok['search_line'][i] + '\n'
                                                                  for i in sorted(ok['search_line'])))
    (ML / 'foryou').mkdir(parents=True, exist_ok=True)
    with (ML / 'foryou' / 'book-texts-for_you_blurb-100k.tsv').open('w', encoding='utf-8', newline='\n') as f:
        f.write('kind\tbook_id\tsource\tmade_on\ttext\n' + ''.join(ok['for_you_blurb'][i] + '\n'
                                                                  for i in sorted(ok['for_you_blurb'])))
    with (OUT / 'profiles-detailed.jsonl').open('w', encoding='utf-8', newline='\n') as f:
        f.write(''.join(ok['profile'][i] + '\n' for i in sorted(ok['profile'])))
    dropped = {}
    for kind in KINDS:
        rows = sorted((i, why) for i, why in last_fail[kind].items() if i not in ok[kind])
        dropped[kind] = {}
        for _, why in rows:
            dropped[kind][why] = dropped[kind].get(why, 0) + 1
        (OUT / f'dropped-{kind}.tsv').write_text('book_id\treason\n' + ''.join(f'{i}\t{w}\n' for i, w in rows),
                                                  encoding='utf-8')
    no_content = sorted(i for i, why in last_fail['search_line'].items()
                        if why == 'no_content' and i not in ok['search_line'])
    (OUT / 'search-line-no-content.tsv').write_text('book_id\n' + ''.join(f'{i}\n' for i in no_content),
                                                     encoding='utf-8')
    # batch prices: input and output half price; cache reads 10% and cache writes 125% of the input price
    cost = sum(u[0] * PRICE_IN + u[1] * PRICE_IN * 0.1 + u[2] * PRICE_IN * 1.25 + u[3] * PRICE_OUT
               for u in usage.values()) / 1e6
    report = {'made_at': dt.datetime.now().isoformat(timespec='seconds'), 'batches': len(record['batches']),
              'kept': {k: len(v) for k, v in ok.items()}, 'dropped': dropped, 'profile_fixed': fixed_counts,
              'failed': failed,
              'tokens': {k: {'input': u[0], 'cache_read': u[1], 'cache_write': u[2], 'output': u[3]}
                         for k, u in usage.items()},
              'cost_usd': round(cost, 2)}
    (OUT / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=1))
    return 0


EXPORT_ID_PREFIX = '# export_id='


def export_id() -> str:
    """The export_id of the PC service DB's last SUCCEEDED catalog import: the book numbers these files use (cycle 26
    handoff R5: the importer refuses a file of another export)."""
    with db() as conn:
        row = conn.execute("SELECT export_id FROM collection.catalog_import WHERE status = 'SUCCEEDED' "
                           "AND export_id IS NOT NULL ORDER BY id DESC LIMIT 1").fetchone()
    if not row:
        raise SystemExit('no SUCCEEDED catalog import with an export_id in the PC service DB')
    return str(row[0])


def write_both(path: Path, body: str, first: str) -> Path:
    """Writes the file as today's readers take it (no first line), and beside it <name>-export.tsv that starts with
    `# export_id=` for cycle 26's catalog import modes (book-texts, book-profiles). Today's book-texts import refuses a
    first row that is not its header, and the model folder's book-profiles.tsv is read line by line by the backend, so
    the plain file stays the one to use until cycle 26's deploy 1."""
    path.write_text(body, encoding='utf-8', newline='\n')
    export = path.with_name(path.stem + '-export' + path.suffix)
    export.write_text(EXPORT_ID_PREFIX + first + '\n' + body, encoding='utf-8', newline='\n')
    return export


def profiles_tsv() -> int:
    """book-profiles.tsv (export_first_call.py format) for the 27,227 old and the new profiles together, and
    book-profiles-export.tsv with the `# export_id=` first line (write_both)."""
    rows = []
    for path in (DATA / 'aisearch' / 'book-profiles-detailed-2026-10-25.jsonl', OUT / 'profiles-detailed.jsonl'):
        for line in path.read_text(encoding='utf-8').splitlines():
            if line.strip():
                rows.append(json.loads(line))
    seen, out = set(), []
    for o in rows:
        if o['id'] in seen:
            continue
        seen.add(o['id'])
        fic = '' if o.get('fiction') is None else ('1' if o['fiction'] else '0')
        out.append('\t'.join([str(o['id']), o.get('field') or '', o.get('subfield') or '', fic,
                              '|'.join(o.get('genre') or []), o.get('country') or '', o.get('audience') or '',
                              o.get('level') or '', '|'.join(o.get('form') or [])]) + '\n')
    export = write_both(OUT / 'book-profiles.tsv', ''.join(out), export_id())
    print(f'{len(seen)} profiles -> {OUT / "book-profiles.tsv"} and {export}')
    return 0


def book_texts() -> int:
    """
    The AI search import file for 100,000 books (docs/maintenance/6-aisearch.md 2·6절): the 30,000-book file as it was
    (lines, and search_exclude for the books left out then) + the new search lines + search_exclude for the new books
    without an intro or whose intro had no content. Import it with book-texts-top-books=100000.
    """
    base = DATA / 'aisearch' / 'book-texts-30k-exclude-2026-10-25.tsv'
    rows, seen = [], set()
    for line in base.read_text(encoding='utf-8').split('\n')[1:]:
        cells = line.rstrip('\r').split('\t')
        if len(cells) == 5:
            rows.append(cells)
            seen.add((cells[0], cells[1]))
    old = len(rows)
    for line in (OUT / 'search-lines.tsv').read_text(encoding='utf-8').split('\n')[1:]:
        cells = line.split('\t')
        if len(cells) == 5 and ('search_line', cells[1]) not in seen and ('search_exclude', cells[1]) not in seen:
            rows.append(cells)
            seen.add(('search_line', cells[1]))
    lines_added = len(rows) - old
    exclude = [(r['book_id'], r['reason']) for r in read_tsv(OUT / 'left-out-no-intro.tsv')]
    exclude += [(r['book_id'], 'search_line_no_content') for r in read_tsv(OUT / 'search-line-no-content.tsv')]
    excl_added = 0
    for book_id, reason in exclude:
        if ('search_line', book_id) in seen or ('search_exclude', book_id) in seen:
            continue
        rows.append(['search_exclude', book_id, '', '', reason])
        seen.add(('search_exclude', book_id))
        excl_added += 1
    # the AI summaries of this round too (cycle 26 handoff R5: book-texts holds for_you_blurb, search_line and
    # search_exclude)
    blurbs = 0
    for line in (ML / 'foryou' / 'book-texts-for_you_blurb-100k.tsv').read_text(encoding='utf-8').split('\n')[1:]:
        cells = line.split('\t')
        if len(cells) == 5 and cells[0] == 'for_you_blurb' and ('for_you_blurb', cells[1]) not in seen:
            rows.append(cells)
            seen.add(('for_you_blurb', cells[1]))
            blurbs += 1
    path = OUT / f'book-texts-100k-exclude-{dt.date.today().isoformat()}.tsv'
    export = write_both(path, 'kind\tbook_id\tsource\tmade_on\ttext\n' + ''.join('\t'.join(c) + '\n' for c in rows),
                        export_id())
    print(json.dumps({'file': str(path), 'export_file': str(export), 'rows': len(rows), 'from_30k_file': old,
                      'new_search_lines': lines_added, 'new_search_exclude': excl_added, 'for_you_blurb': blurbs},
                     ensure_ascii=False))
    return 0


def main() -> int:
    sys.stdout.reconfigure(encoding='utf-8')
    steps = {'prepare': prepare, 'kdc': kdc, 'requests': requests_, 'submit': submit, 'collect': collect,
             'retry': retry,
             'profiles-tsv': profiles_tsv, 'book-texts': book_texts}
    step = sys.argv[1] if len(sys.argv) > 1 else ''
    if step not in steps:
        print(__doc__)
        return 2
    return steps[step]()


if __name__ == '__main__':
    sys.exit(main())
