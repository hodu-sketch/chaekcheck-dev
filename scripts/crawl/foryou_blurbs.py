"""Cycle 23 step 10, first round (user 2026-10-25: "각각 ai 요약 글 haiku batch로 … 없는 거 글 넣어줬으면 하는데 원래
추천도서에 있는 거"): the tab blurbs (kind for_you_blurb, spec R8) of the recommended-list books that have none yet,
written by Claude Haiku 5.5 through the Message Batches API. No request to any library site; SELECTs on the PC's service
DB and the collected DB. The intro text stays on the PC; only the blurbs go to the service (R8-2).

Run with the data folder's venv (anthropic SDK) and ANTHROPIC_API_KEY in the environment or in ../.env:

  <data>/ml/venv/Scripts/python.exe -X utf8 scripts/crawl/foryou_blurbs.py prepare   # requests file, no API call
  <data>/ml/venv/Scripts/python.exe -X utf8 scripts/crawl/foryou_blurbs.py submit    # sends the batch (costs money)
  <data>/ml/venv/Scripts/python.exe -X utf8 scripts/crawl/foryou_blurbs.py collect   # waits, checks, writes the TSV

- Books: picks.list_book.book_id of every list, without a row in foryou.book_blurb (R8-7: only the books without one).
- Input: title · author · publisher · year and the longest list intro of the book's holdings (first INTRO_CHARS
  characters); without an intro the bibliographic line alone, and the model answers NONE when it cannot write without
  guessing (R8-2 "모르면 쓰지 않음").
- Rules (R8-4): 해요체, one or two sentences, 80~150 characters, no praise, no ending given away, no mention of AI, no
  run of 8 or more characters copied from the intro. A blurb that breaks a checkable rule is dropped and counted; the
  next prepare asks for that book again.
- Writes <data>/ml/foryou/book-texts-for_you_blurb.tsv (header kind, book_id, source, made_on, text), which the
  collection import mode book-texts reads, and blurbs-report.json (counts, tokens, cost at the batch price).
"""
from __future__ import annotations

import datetime as dt
import json
import os
import re
import sqlite3
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DATA = Path(os.environ.get('CHAEKCHECK_DATA') or REPO.parent / 'chaekcheck-data')
OUT = DATA / 'ml' / 'foryou'
MODEL = 'claude-haiku-5-5'
INTRO_CHARS = 1500
MIN_CHARS, MAX_CHARS = 80, 150
COPY_RUN = 8
# US$ per million tokens, Haiku 5.5 up to 100K-token prompts, half price in a batch (claude-api skill, 2026-10-06)
PRICE_IN, PRICE_OUT = 0.10 / 2, 0.50 / 2

SYSTEM = """당신은 전자도서관 책 추천 탭에 들어갈 짧은 소개 글을 씁니다.
규칙:
- 해요체로 한두 문장, 공백 포함 80~150자.
- 이 책이 무엇에 관한 책인지, 어떤 독자에게 맞는지 담담하게 알려 줍니다.
- 칭찬하지 않습니다("최고의", "감동적인", "꼭 읽어야 할", "베스트셀러" 같은 말 금지).
- 소설의 결말이나 반전을 말하지 않습니다.
- AI, 인공지능, 요약, 소개 원문 같은 말을 쓰지 않습니다.
- 주어진 소개 원문의 문장을 옮기지 않습니다. 원문과 8자 이상 연달아 같은 글자가 나오지 않게 자기 말로 다시 씁니다.
- 주어진 정보만으로 책 내용을 알 수 없으면 추측하지 말고 NONE 한 단어만 씁니다.
- 글만 씁니다. 따옴표, 머리말, 설명을 붙이지 않습니다."""


def env() -> dict[str, str]:
    values = {}
    path = REPO / '.env'
    if path.exists():
        for line in path.read_text(encoding='utf-8').splitlines():
            if '=' in line and not line.lstrip().startswith('#'):
                k, v = line.split('=', 1)
                values[k.strip()] = v.strip()
    return values


def bibliographic(title, author, publisher, published) -> str:
    parts = [title or '', author or '', publisher or '']
    if published:
        parts.append(str(published)[:4])
    return ' · '.join(p for p in parts if p)


def copied_run(text: str, source: str, run: int = COPY_RUN) -> str | None:
    """The first run of `run` characters (spaces left out) that the text shares with the source, or None (R8-4)."""
    a = re.sub(r'\s+', '', text)
    b = re.sub(r'\s+', '', source or '')
    if len(a) < run or len(b) < run:
        return None
    grams = {b[i:i + run] for i in range(len(b) - run + 1)}
    for i in range(len(a) - run + 1):
        if a[i:i + run] in grams:
            return a[i:i + run]
    return None


BANNED = re.compile(r'AI|인공지능|최고의|감동적|꼭 읽어야|베스트셀러|필독')


def check(text: str, intro: str) -> str | None:
    """Why the blurb breaks a checkable rule of R8-4, or None when it keeps them."""
    if text.strip().upper() == 'NONE':
        return 'none'
    if not MIN_CHARS <= len(text) <= MAX_CHARS:
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


def db_connect():
    import psycopg
    e = env()
    return psycopg.connect(host='localhost', port=e.get('DB_PORT', '5432'), dbname=e['DB_NAME'],
                           user=e['DB_USERNAME'], password=e.get('DB_PASSWORD', ''), autocommit=True)


def targets() -> list[dict]:
    db = db_connect()
    cur = db.cursor()
    cur.execute("""
        SELECT b.id, b.title, b.author_display, b.publisher, b.published_on FROM catalog.book b
        WHERE b.id IN (SELECT book_id FROM picks.list_book WHERE book_id IS NOT NULL)
          AND NOT EXISTS (SELECT 1 FROM foryou.book_blurb x WHERE x.book_id = b.id)
        ORDER BY b.id""")
    books = cur.fetchall()
    ids = [b[0] for b in books]
    cur.execute("""
        SELECT h.book_id, l.collection_library_id, h.item_key FROM catalog.holding h
        JOIN collection.collection_link l ON l.library_id = h.library_id WHERE h.book_id = ANY(%s)""", (ids,))
    holdings = cur.fetchall()
    db.close()
    collected = sqlite3.connect(f'file:{(DATA / "ebook-catalog.sqlite").as_posix()}?mode=ro', uri=True, timeout=120)
    collected.execute('PRAGMA busy_timeout=120000')
    intros: dict[int, str] = {}
    for book_id, lib, item in holdings:
        row = collected.execute('SELECT intro_raw FROM holding WHERE library_id = ? AND item_key = ?',
                                (int(lib), item)).fetchone()
        if row and row[0] and len(row[0]) > len(intros.get(book_id, '')):
            intros[book_id] = row[0]
    collected.close()
    return [{'book_id': b[0], 'line': bibliographic(*b[1:5]), 'intro': intros.get(b[0], '')[:INTRO_CHARS]}
            for b in books]


def prompt_of(book: dict) -> str:
    text = f'책: {book["line"]}\n'
    text += f'소개 원문:\n{book["intro"]}' if book['intro'] else '소개 원문: 없음'
    return text


def prepare() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    books = targets()
    with (OUT / 'blurb-requests.jsonl').open('w', encoding='utf-8', newline='\n') as f:
        for book in books:
            f.write(json.dumps(book, ensure_ascii=False) + '\n')
    with_intro = sum(1 for b in books if b['intro'])
    chars = sum(len(prompt_of(b)) for b in books) + len(SYSTEM) * len(books)
    print(json.dumps({'books': len(books), 'with_intro': with_intro, 'without_intro': len(books) - with_intro,
                      'prompt_chars': chars, 'file': str(OUT / 'blurb-requests.jsonl')}, ensure_ascii=False, indent=1))
    return 0


def client():
    import anthropic
    key = os.environ.get('ANTHROPIC_API_KEY') or env().get('ANTHROPIC_API_KEY')
    return anthropic.Anthropic(api_key=key) if key else anthropic.Anthropic()


def submit() -> int:
    from anthropic.types.message_create_params import MessageCreateParamsNonStreaming
    from anthropic.types.messages.batch_create_params import Request
    books = [json.loads(line) for line in (OUT / 'blurb-requests.jsonl').read_text(encoding='utf-8').splitlines()]
    requests = [Request(custom_id=str(b['book_id']), params=MessageCreateParamsNonStreaming(
        model=MODEL, max_tokens=2000, output_config={'effort': 'low'}, system=SYSTEM,
        messages=[{'role': 'user', 'content': prompt_of(b)}])) for b in books]
    batch = client().messages.batches.create(requests=requests)
    (OUT / 'blurb-batch.json').write_text(json.dumps({'id': batch.id, 'books': len(books),
                                                      'submitted_at': dt.datetime.now().isoformat(timespec='seconds')}))
    print(f'batch {batch.id}: {len(books)} requests, {batch.processing_status}')
    return 0


def collect() -> int:
    c = client()
    info = json.loads((OUT / 'blurb-batch.json').read_text())
    while True:
        batch = c.messages.batches.retrieve(info['id'])
        if batch.processing_status == 'ended':
            break
        print(f'{dt.datetime.now():%H:%M:%S} {batch.processing_status}: processing {batch.request_counts.processing}',
              flush=True)
        time.sleep(60)
    books = {str(json.loads(line)['book_id']): json.loads(line)
             for line in (OUT / 'blurb-requests.jsonl').read_text(encoding='utf-8').splitlines()}
    kept, dropped, failed = [], {}, {}
    samples: dict[str, list] = {}
    tokens_in = tokens_out = 0
    for result in c.messages.batches.results(info['id']):
        if result.result.type != 'succeeded':
            failed[result.result.type] = failed.get(result.result.type, 0) + 1
            continue
        msg = result.result.message
        tokens_in += msg.usage.input_tokens
        tokens_out += msg.usage.output_tokens
        text = next((b.text for b in msg.content if b.type == 'text'), '').strip()
        book = books[result.custom_id]
        why = check(text, book['intro'])
        if why:
            dropped[why] = dropped.get(why, 0) + 1
            samples.setdefault(why, [])
            if len(samples[why]) < 5:
                samples[why].append({'book_id': book['book_id'], 'book': book['line'], 'text': text})
            continue
        kept.append((book['book_id'], text))
    made_on = dt.date.today().isoformat()
    path = OUT / 'book-texts-for_you_blurb.tsv'
    with path.open('w', encoding='utf-8', newline='\n') as f:
        f.write('kind\tbook_id\tsource\tmade_on\ttext\n')
        for book_id, text in sorted(kept):
            f.write(f'for_you_blurb\t{book_id}\thaiku-batch:{info["id"]}\t{made_on}\t{text}\n')
    report = {'batch': info['id'], 'requests': len(books), 'kept': len(kept), 'dropped': dropped, 'failed': failed,
              'input_tokens': tokens_in, 'output_tokens': tokens_out,
              'cost_usd': round(tokens_in / 1e6 * PRICE_IN + tokens_out / 1e6 * PRICE_OUT, 4),
              'file': str(path), 'dropped_samples': samples}
    (OUT / 'blurbs-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'dropped_samples'}, ensure_ascii=False, indent=1))
    return 0


def main() -> int:
    sys.stdout.reconfigure(encoding='utf-8')
    step = sys.argv[1] if len(sys.argv) > 1 else ''
    steps = {'prepare': prepare, 'submit': submit, 'collect': collect}
    if step not in steps:
        print(__doc__)
        return 2
    return steps[step]()


if __name__ == '__main__':
    sys.exit(main())
