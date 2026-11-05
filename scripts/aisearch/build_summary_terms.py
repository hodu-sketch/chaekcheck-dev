"""The AI search's two-layer texts (user 2026-10-30 "두 층 (조각 512) + BM25" and "한 줄 + Haiku 검색 낱말 (원문 불필요)로
가자"): one Haiku call per book writes its summary (200-250 characters, the summary vector and what Haiku reads) and its
search terms (30-50, the BM25 text with title, author and line). The prompt is the one measured in
docs/features/aisearch/reports/2026-10-29-intro-text.md 11 (terms_onecall_eval.ONECALL_PROMPT, MRR 0.875 on 800 queries). The
intros stay on this PC; only the two written texts go to the server (cycle 23 [확정]).

  py scripts/aisearch/build_summary_terms.py prepare   # the books (the same as the piece vectors)
  py scripts/aisearch/build_summary_terms.py submit    # Message Batches of CHUNK requests (half price)
  py scripts/aisearch/build_summary_terms.py collect   # wait, check, write summary-terms.tsv; failures listed
  py scripts/aisearch/build_summary_terms.py retry     # ask again for the failed books, then collect again
  py scripts/aisearch/build_summary_terms.py book-texts BASE OUT   # the import file: BASE + the written rows

py = <data>/ml/venv/Scripts/python.exe -X utf8. The key is CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY (environment or the
dev .env), never printed. Output in <data>/aisearch/two-layer/.
"""
from __future__ import annotations

import datetime as dt
import json
import os
import re
import sys
import time
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'scripts' / 'aisearch'))
sys.path.insert(0, str(REPO / 'scripts' / 'aisearch' / 'eval'))
import build_chunk_vectors as chunks  # noqa: E402  the same books as the piece vectors
import intro_text_eval as e  # noqa: E402
from terms_onecall_eval import ONECALL_PROMPT  # noqa: E402

OUT = chunks.DATA / 'aisearch' / 'two-layer'
MODEL = e.HAIKU
MAX_TOKENS = 1200          # as measured (terms_onecall_eval.llm)
CHUNK = 10_000             # requests per batch (a batch takes at most 100,000 requests or 256 MB)
SHARED_INTRO_BOOKS = 3     # only counted: 3,138 books share an intro word for word, nearly all series (삼국지 1-120, a
                           # publisher's picture-book series), so they are asked like any other book (2026-10-30)
SUMMARY_MAX = 500          # characters; the measured one-call summaries ran 139-408 (median 270, 21.5% over 300)
TERMS_MIN = 5              # fewer terms than this is a failed answer


def client():
    import anthropic
    key = os.environ.get('CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY') or e.api_key()
    if not key:
        raise SystemExit('CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY is not in the environment or .env')
    return anthropic.Anthropic(api_key=key)


def prepare() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    books, stats = chunks.books()
    shared = Counter(intro for _, _, intro in books)
    with (OUT / 'requests.jsonl').open('w', encoding='utf-8') as f:
        for book_id, head, intro in books:
            f.write(json.dumps({'book_id': book_id, 'user': ONECALL_PROMPT.format(head=head, intro=intro[:e.LLM_INTRO_MAX])},
                               ensure_ascii=False) + '\n')
    stats.update({'requests': len(books),
                  'books_sharing_an_intro': sum(1 for *_, intro in books if shared[intro] >= SHARED_INTRO_BOOKS),
                  'shared_intro_groups': sum(1 for n in shared.values() if n >= SHARED_INTRO_BOOKS)})
    (OUT / 'prepare.json').write_text(json.dumps(stats, indent=1))
    print(json.dumps(stats))
    return 0


def params(user: str) -> dict:
    # Haiku 5.5 takes thinking "disabled", as the measured run did (intro_text_eval.ask)
    return {'model': MODEL, 'max_tokens': MAX_TOKENS, 'thinking': {'type': 'disabled'},
            'messages': [{'role': 'user', 'content': user}]}


def send(rows: list[dict], label: str, record: dict) -> None:
    from anthropic.types.message_create_params import MessageCreateParamsNonStreaming
    from anthropic.types.messages.batch_create_params import Request
    c = client()
    for start in range(0, len(rows), CHUNK):
        part = rows[start:start + CHUNK]
        batch = c.messages.batches.create(requests=[
            Request(custom_id=f"book-{r['book_id']}", params=MessageCreateParamsNonStreaming(**params(r['user'])))
            for r in part])
        record['batches'].append({'label': label, 'id': batch.id, 'requests': len(part),
                                  'submitted_at': dt.datetime.now().isoformat(timespec='seconds')})
        (OUT / 'batches.json').write_text(json.dumps(record, indent=1))
        print(f'{label}: batch {batch.id}, {len(part)} requests', flush=True)


def submit() -> int:
    path = OUT / 'batches.json'
    record = json.loads(path.read_text()) if path.exists() else {'batches': []}
    if any(b['label'] == 'first' for b in record['batches']):
        raise SystemExit('already submitted (batches.json); use collect or retry')
    rows = [json.loads(line) for line in (OUT / 'requests.jsonl').read_text(encoding='utf-8').splitlines()]
    send(rows, 'first', record)
    return 0


def parse(text: str) -> tuple[dict | None, str]:
    m = re.search(r'\{.*\}', text, re.S)
    if not m:
        return None, 'no json'
    try:
        got = json.loads(m.group(0))
    except json.JSONDecodeError:
        return None, 'bad json'
    summary = ' '.join((got.get('summary') or '').split())
    terms = ', '.join(t.strip() for t in (got.get('terms') or '').replace('\n', ',').split(',') if t.strip())
    if not summary:
        return None, 'no summary'
    if len(summary) > SUMMARY_MAX:
        return None, 'summary too long'
    if terms.count(',') + 1 < TERMS_MIN:
        return None, 'too few terms'
    return {'summary': summary, 'terms': terms}, ''


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
    wanted = [json.loads(line)['book_id'] for line in (OUT / 'requests.jsonl').read_text(encoding='utf-8').splitlines()]
    ok, why = {}, {}
    usage = [0, 0]
    for b in record['batches']:   # later batches (retries) only fill books still missing
        for result in c.messages.batches.results(b['id']):
            book_id = int(result.custom_id.split('-', 1)[1])
            if book_id in ok:
                continue
            if result.result.type != 'succeeded':
                why[book_id] = result.result.type
                continue
            msg = result.result.message
            usage[0] += msg.usage.input_tokens
            usage[1] += msg.usage.output_tokens
            got, fail = parse(next((x.text for x in msg.content if x.type == 'text'), ''))
            if got:
                ok[book_id] = got
                why.pop(book_id, None)
            else:
                why[book_id] = fail
    made_on = dt.date.today().isoformat()
    with (OUT / 'summary-terms.tsv').open('w', encoding='utf-8', newline='\n') as f:
        f.write('book_id\tsummary\tterms\tmade_on\n')
        for book_id in wanted:
            if book_id in ok:
                f.write(f"{book_id}\t{ok[book_id]['summary']}\t{ok[book_id]['terms']}\t{made_on}\n")
    missing = {i: why.get(i, 'no answer') for i in wanted if i not in ok}
    (OUT / 'failed.json').write_text(json.dumps(missing))
    report = {'books': len(wanted), 'written': len(ok), 'failed': len(missing),
              'failed_by_reason': dict(Counter(missing.values())), 'input_tokens': usage[0], 'output_tokens': usage[1],
              'summary_chars_mean': round(sum(len(v['summary']) for v in ok.values()) / max(1, len(ok)), 1),
              'terms_mean': round(sum(v['terms'].count(',') + 1 for v in ok.values()) / max(1, len(ok)), 1)}
    (OUT / 'collect.json').write_text(json.dumps(report, indent=1))
    print(json.dumps(report))
    return 0


def retry() -> int:
    record = json.loads((OUT / 'batches.json').read_text())
    failed = {int(k) for k in json.loads((OUT / 'failed.json').read_text())}
    rows = [r for r in (json.loads(line) for line in (OUT / 'requests.jsonl').read_text(encoding='utf-8').splitlines())
            if r['book_id'] in failed]
    if not rows:
        print('nothing to retry')
        return 0
    send(rows, f"retry-{sum(1 for b in record['batches'] if b['label'].startswith('retry')) + 1}", record)
    return 0


TERMS_MAX = 2000           # catalog.book_search.terms CHECK (BookTextsTransfer.SEARCH_TERMS_MAX_CHARS)


def book_texts(base: str, out: str) -> int:
    """The import file: every row of BASE (a book-texts file: export id row, header, search_line, search_exclude, ...)
    except its old search_summary/search_terms rows, then one search_summary and one search_terms row per book of
    summary-terms.tsv. The import replaces catalog.book_search whole, so the base must be the file the server holds."""
    rows = Path(base).read_text(encoding='utf-8').split('\n')
    if not rows[0].startswith('# export_id=') or rows[1] != 'kind\tbook_id\tsource\tmade_on\ttext':
        raise SystemExit(f'{base}: not a book-texts file with an export id row')
    kept = [r for r in rows[2:] if r and not r.startswith(('search_summary\t', 'search_terms\t'))]
    shown = {int(r.split('\t')[1]) for r in kept if r.startswith('search_line\t')}
    written = chunks.read_tsv(OUT / 'summary-terms.tsv')
    problems = [r['book_id'] for r in written if not 0 < len(r['summary']) <= SUMMARY_MAX
                or not 0 < len(r['terms']) <= TERMS_MAX or '\t' in r['summary'] + r['terms']]
    if problems:
        raise SystemExit(f'{len(problems)} books with a bad summary or terms, e.g. {problems[:5]}')
    with open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(rows[:2] + kept) + '\n')
        for r in written:
            f.write(f"search_summary\t{r['book_id']}\thaiku\t{r['made_on']}\t{r['summary']}\n")
            f.write(f"search_terms\t{r['book_id']}\thaiku\t{r['made_on']}\t{r['terms']}\n")
    ids = {int(r['book_id']) for r in written}
    print(json.dumps({'base_rows': len(kept), 'written_books': len(ids), 'written_with_line': len(ids & shown),
                      'written_without_line': len(ids - shown), 'out': out}))
    return 0


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    if sys.argv[1:2] == ['book-texts'] and len(sys.argv) == 4:
        sys.exit(book_texts(sys.argv[2], sys.argv[3]))
    steps = {'prepare': prepare, 'submit': submit, 'collect': collect, 'retry': retry}
    if sys.argv[1:2] not in ([k] for k in steps):
        raise SystemExit('usage: build_summary_terms.py ' + '|'.join(steps) + ' | book-texts BASE OUT')
    sys.exit(steps[sys.argv[1]]())
