"""Which intro text makes the better book vector (user 2026-10-29): the book vectors take "제목 · 저자 · 출판사 · 연도" and
the first 300 intro characters, and most intros are longer. Four texts are compared on the same 1,000 books:

    base300  the text of today's vectors (scripts/aisearch/build_100k_vectors.py)
    c500     the same with the first 500 intro characters
    prefix   a Haiku context of 2-3 sentences before today's text (Anthropic's Contextual Retrieval, 2024)
    summary  the title line and a Haiku summary of the whole intro (200-250 characters) in place of the intro

Steps (each saves its files in OUT and skips work already saved, so a stopped run goes on):

  <data>/ml/venv/Scripts/python.exe -X utf8 scripts/aisearch/eval/intro_text_eval.py sample   # 1,000 books, query books
  ... llm       # Haiku prefix + summary per book, Sonnet search words per query book
  ... embed     # KURE-v1 vectors of the four texts and of the queries
  ... lengths   # head line + whole intro cut at 128 ... 1,024 tokens (tok128 ... tok1024)
  ... chunks    # the intro in pieces of 128 / 256 / 512 tokens cut at sentence ends, one vector per piece
                # (chunkN), and with a Haiku context per piece written from the whole intro (ctxN, Anthropic's
                # Contextual Retrieval); a book's score is its best piece
  ... queries   # the query vectors again after new queries (the niche set)
  ... rank      # known-book queries: where the book lands among the 1,000; topic queries: top 10 per text
  ... judge     # Haiku grades every (topic query, book) pair of the top 10s, 0-3
  ... report    # report.json + report.md

Known-book queries are written by Sonnet (not Haiku, so the query and the Haiku texts are not the same model's words):
"whole" from the whole intro, "tail" only from what the intro says after its first 300 characters. Topic queries are the
57 questions of eval_questions.json. The API key is read from the dev .env and never printed.
"""
from __future__ import annotations

import json
import math
import os
import random
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECTS = Path(__file__).resolve().parents[4]
DATA = Path(os.environ.get('CHAEKCHECK_DATA') or PROJECTS / 'chaekcheck-data')
ML = DATA / 'ml'
OUT = Path(os.environ.get('INTRO_TEXT_OUT') or PROJECTS / 'chaekcheck-dev' / 'data' / 'aisearch-intro-text-2026-10-29')
ENV = PROJECTS / 'chaekcheck-dev' / '.env'
os.environ.setdefault('HF_HOME', str(ML / 'hf'))

MODEL = 'nlpai-lab/KURE-v1'
HAIKU = 'claude-haiku-5-5'
SONNET = 'claude-sonnet-5-5'
SEED = 20261029
BOOKS = 1000
WHOLE_QUERIES = 100
TAIL_QUERIES = 100
NICHE_QUERIES = 100     # specialist questions: a concept, term or name from the intro (other books than whole/tail)
BASE_KINDS = ('whole', 'tail', 'niche')
# other wordings of the niche queries (variants step): a typo, another name, keywords only, explained without the
# term, and a reader condition added
KNOWN = BASE_KINDS + ('typo', 'alias', 'short', 'para', 'level')
TAIL_MIN = 700         # a tail query needs at least 400 characters after the first 300
INTRO_MIN = 50
LLM_INTRO_MAX = 4000    # the intro the LLM reads
JUDGE_INTRO_MAX = 1500  # the intro the judge reads
TEXTS = ('base300', 'c500', 'prefix', 'summary')
LENGTHS = (128, 256, 384, 512, 768, 1024)  # token caps for "head line + whole intro" (user: 512 is said to be best)
LENGTH_INTRO_MAX = 6000                    # characters given to the tokenizer before the cap
CHUNK = 300                                # characters per intro piece: piece 1 is today's text
CHUNK_INTRO_MAX = 4000                     # the intro the pieces cover (the same 4,000 the LLM reads)
BANDS = (200, 300, 400, 500, 700, 1000)


def read_tsv(path: Path) -> list[dict]:
    lines = path.read_text(encoding='utf-8').split('\n')
    head = lines[0].split('\t')
    return [dict(zip(head, line.split('\t'))) for line in lines[1:] if line]


def write_json(name: str, value) -> None:
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=1), encoding='utf-8')


def read_json(name: str):
    return json.loads((OUT / name).read_text(encoding='utf-8'))


def band(n: int) -> str:
    for b in BANDS:
        if n <= b:
            return f'<={b}'
    return f'>{BANDS[-1]}'


# ---------------------------------------------------------------- sample

def sample() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    intros = {int(r['book_id']): r['intro'] for r in read_tsv(ML / 'intro-books-100k.tsv')}
    pool = []
    for t in read_tsv(DATA / 'aisearch' / '100k' / 'targets.tsv'):
        book_id = int(t['book_id'])
        intro = intros.get(book_id, '')
        if len(intro) < INTRO_MIN:
            continue
        pool.append({'book_id': book_id, 'title': t['title'], 'author': t['author'], 'publisher': t['publisher'],
                     'year': t['year'], 'intro': intro})
    rng = random.Random(SEED)
    books = rng.sample(pool, BOOKS)
    tail_ok = [b['book_id'] for b in books if len(b['intro']) >= TAIL_MIN]
    tail = rng.sample(tail_ok, TAIL_QUERIES)
    whole = rng.sample([b['book_id'] for b in books if b['book_id'] not in set(tail)], WHOLE_QUERIES)
    write_json('books.json', books)
    write_json('query-books.json', {'whole': whole, 'tail': tail})
    dist = {}
    for b in books:
        dist[band(len(b['intro']))] = dist.get(band(len(b['intro'])), 0) + 1
    pop = {}
    for b in pool:
        pop[band(len(b['intro']))] = pop.get(band(len(b['intro'])), 0) + 1
    lengths = sorted(len(b['intro']) for b in pool)
    print(json.dumps({'pool': len(pool), 'pool_median': lengths[len(lengths) // 2],
                      'pool_mean': round(sum(lengths) / len(lengths)),
                      'pool_over_300': round(sum(n > 300 for n in lengths) / len(lengths), 3),
                      'pool_over_500': round(sum(n > 500 for n in lengths) / len(lengths), 3),
                      'sample_bands': dist, 'pool_bands': pop, 'tail_candidates': len(tail_ok)}, ensure_ascii=False))


# ---------------------------------------------------------------- llm

def api_key() -> str:
    for line in ENV.read_text(encoding='utf-8').splitlines():
        if line.startswith('CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY='):
            return line.split('=', 1)[1].strip().strip('"').strip("'")
    raise SystemExit('CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY is not in ' + str(ENV))


_client = None
_usage = {}


def ask(model: str, prompt: str, max_tokens: int) -> str:
    global _client
    import anthropic
    if _client is None:
        _client = anthropic.Anthropic(api_key=api_key(), max_retries=6)
    for attempt in range(4):
        try:
            # Haiku 5.5 takes thinking "disabled"; Sonnet 5.5 refuses it and wants "between_tools" (no thinking
            # before the answer)
            off = {'type': 'disabled'} if model == HAIKU else {'type': 'between_tools'}
            msg = _client.messages.create(model=model, max_tokens=max_tokens, thinking=off,
                                          messages=[{'role': 'user', 'content': prompt}])
            u = _usage.setdefault(model, [0, 0, 0])
            u[0] += msg.usage.input_tokens
            u[1] += msg.usage.output_tokens
            u[2] += 1
            return ''.join(b.text for b in msg.content if b.type == 'text').strip()
        except Exception as e:  # noqa: BLE001 - the SDK retried already; one more round for overloads
            if attempt == 3:
                raise
            print('retry', type(e).__name__, flush=True)
            time.sleep(5 * (attempt + 1))
    return ''


def head(b: dict) -> str:
    return ' · '.join(p for p in (b['title'], b['author'], b['publisher'], b['year']) if p)


PREFIX_PROMPT = """<book>
{head}
{intro}
</book>
위는 책 한 권의 정보와 소개글입니다. 이 책이 검색에서 잘 찾히도록, 책이 무엇에 관한 것인지 알려 주는 짧은 맥락을 2~3문장(한국어 80~150자)으로 쓰세요.
- 주제·분야, 형식(소설, 에세이, 실용서, 그림책 등), 대상 독자, 핵심 내용을 담습니다.
- 소개글에 없는 내용은 지어내지 않습니다.
- 맥락 문장만 답합니다."""

SUMMARY_PROMPT = """<book>
{head}
{intro}
</book>
위 책 소개글을 한국어 200~250자로 요약하세요.
- 책의 주제, 분야, 형식, 대상 독자, 핵심 내용을 남깁니다.
- 검색에 쓰일 주제어와 개념어는 소개글의 표현을 그대로 살립니다.
- 출판사 홍보 문구, 수상·판매 실적, 추천사, 반복되는 말은 뺍니다.
- 소개글에 없는 내용은 지어내지 않습니다.
- 요약문만 답합니다."""

QUERY_RULES = """- 제목, 저자, 출판사, 시리즈 이름, 등장인물 이름 같은 고유명사를 쓰지 않습니다.
- 소개글 문장을 베끼지 말고, 사람이 검색창에 말하듯 바꿔 씁니다(예: "회사 그만두고 시골로 내려간 사람 이야기").
- 15~40자 한 줄.
- 검색어만 답합니다."""

WHOLE_PROMPT = """<book>
{head}
{intro}
</book>
이 책을 찾고 있는 사람이 AI 책 검색에 입력할 만한 검색어를 하나 쓰세요. 이 책이 무엇에 관한 책인지가 드러나야 합니다.
""" + QUERY_RULES

NICHE_PROMPT = """<book>
{head}
{intro}
</book>
이 책 소개글에 나오는 구체적이고 전문적인 내용 하나(특정 개념, 이론, 기법, 사건, 시대, 지명, 실존 인물, 전문 용어 등)를 고르고, 그 내용을 다룬 책을 찾는 사람이 AI 책 검색에 입력할 만한 검색어를 하나 쓰세요.
- 고른 전문 용어나 이름은 그대로 써도 됩니다. 그 밖의 말은 소개글을 베끼지 말고 사람이 말하듯 씁니다.
- 이 책의 제목, 저자, 출판사, 시리즈 이름, 소설 속 등장인물 이름은 쓰지 않습니다.
- 15~40자 한 줄. 검색어만 답합니다."""

TAIL_PROMPT = """<book>
{head}
</book>
<front>
{front}
</front>
<back>
{back}
</back>
이 책 소개글을 앞부분(front)과 뒷부분(back)으로 나눴습니다. 이 책을 찾고 있는 사람이 AI 책 검색에 입력할 만한 검색어를 하나 쓰세요.
- 뒷부분(back)에만 나오는 내용(주제, 대상 독자, 다루는 내용, 특징)을 근거로 씁니다. 앞부분(front)에 나오는 내용과 낱말은 쓰지 않습니다.
""" + QUERY_RULES


def run_jobs(name: str, jobs: list[tuple[str, str, str, int]]) -> dict:
    """jobs: (key, model, prompt, max_tokens); answers saved in OUT/name as they come."""
    path = OUT / name
    done = {}
    if path.exists():
        for line in path.read_text(encoding='utf-8').splitlines():
            if line:
                row = json.loads(line)
                done[row['key']] = row['text']
    todo = [j for j in jobs if j[0] not in done]
    print(f'{name}: {len(done)} saved, {len(todo)} to ask', flush=True)
    with open(path, 'a', encoding='utf-8') as f, ThreadPoolExecutor(16) as pool:
        for (key, *_), text in zip(todo, pool.map(lambda j: ask(j[1], j[2], j[3]), todo)):
            done[key] = text
            f.write(json.dumps({'key': key, 'text': text}, ensure_ascii=False) + '\n')
            f.flush()
    return done


def llm() -> None:
    books = read_json('books.json')
    by_id = {b['book_id']: b for b in books}
    qb = read_json('query-books.json')
    t = time.monotonic()
    run_jobs('prefix.jsonl', [(str(b['book_id']), HAIKU, PREFIX_PROMPT.format(head=head(b), intro=b['intro'][:LLM_INTRO_MAX]), 300)
                              for b in books])
    run_jobs('summary.jsonl', [(str(b['book_id']), HAIKU, SUMMARY_PROMPT.format(head=head(b), intro=b['intro'][:LLM_INTRO_MAX]), 500)
                               for b in books])
    jobs = [(f'whole-{i}', SONNET, WHOLE_PROMPT.format(head=head(by_id[i]), intro=by_id[i]['intro'][:LLM_INTRO_MAX]), 100)
            for i in qb['whole']]
    jobs += [(f'tail-{i}', SONNET, TAIL_PROMPT.format(head=head(by_id[i]), front=by_id[i]['intro'][:300],
                                                      back=by_id[i]['intro'][300:LLM_INTRO_MAX]), 100)
             for i in qb['tail']]
    if 'niche' not in qb:  # added after the first run (user 2026-10-29: specialist questions find nothing)
        taken = set(qb['whole']) | set(qb['tail'])
        qb['niche'] = random.Random(SEED + 1).sample([b['book_id'] for b in books if b['book_id'] not in taken],
                                                     NICHE_QUERIES)
        write_json('query-books.json', qb)
    jobs += [(f'niche-{i}', SONNET, NICHE_PROMPT.format(head=head(by_id[i]), intro=by_id[i]['intro'][:LLM_INTRO_MAX]), 100)
             for i in qb['niche']]
    run_jobs('queries.jsonl', jobs)
    add_usage('llm', time.monotonic() - t)


def add_usage(step: str, seconds: float) -> None:
    path = OUT / 'usage.json'
    usage = json.loads(path.read_text()) if path.exists() else {}
    usage[step] = {'seconds': round(seconds), 'models': {m: {'in': u[0], 'out': u[1], 'calls': u[2]} for m, u in _usage.items()}}
    path.write_text(json.dumps(usage, indent=1))


def jsonl(name: str) -> dict:
    return {r['key']: r['text'] for r in map(json.loads, (OUT / name).read_text(encoding='utf-8').splitlines()) if r}


# ---------------------------------------------------------------- embed

def texts() -> dict[str, list[str]]:
    books = read_json('books.json')
    prefix, summary = jsonl('prefix.jsonl'), jsonl('summary.jsonl')
    out = {k: [] for k in TEXTS}
    for b in books:
        h = head(b)
        out['base300'].append(f"{h}\n{b['intro'][:300]}")
        out['c500'].append(f"{h}\n{b['intro'][:500]}")
        out['prefix'].append(f"{prefix[str(b['book_id'])]}\n{h}\n{b['intro'][:300]}")
        out['summary'].append(f"{h}\n{summary[str(b['book_id'])]}")
    return out


VARIANT_PROMPT = """<book>
{head}
{intro}
</book>
<query>{q}</query>
위 검색어는 이 책 소개글에 나오는 전문 내용 하나를 찾는 검색어입니다. 같은 책을 찾는 사람이 실제로 쓸 법한 다른 꼴의 검색어를 아래 다섯 가지로 쓰세요.
- typo: 핵심 용어나 이름 하나를 사람들이 흔히 틀리는 대로 한두 글자 틀리게 씁니다(예: 플로이드 → 플루이드, 띄어쓰기 바뀜).
- alias: 핵심 용어나 이름을 소개글과 다른 이름으로 씁니다(영어 원어, 줄임말, 다른 한글 표기, 흔히 부르는 별명).
- short: 핵심 낱말 1~3개만 씁니다(예: 「불확정성 원리」).
- para: 핵심 용어나 이름을 쓰지 않고 그 내용을 풀어서 설명합니다.
- level: 원래 검색어에 이 책에 맞는 독자 조건(입문자, 초등학생, 전공자, 실무자 등) 하나를 더합니다.
- 이 책의 제목, 저자, 출판사, 시리즈 이름, 소설 속 등장인물 이름은 쓰지 않습니다.
JSON 한 줄로만 답하세요: {{"typo": "...", "alias": "...", "short": "...", "para": "...", "level": "..."}}"""
VARIANTS = ('typo', 'alias', 'short', 'para', 'level')


def variants() -> None:
    """Five other wordings of each niche query (user 2026-10-29: "다양한 상황에 대해서 대응가능하도록 테스트케이스 추가"),
    written by Sonnet as the queries are; each one asks for the same book."""
    by_id = {b['book_id']: b for b in read_json('books.json')}
    q = jsonl('queries.jsonl')
    t = time.monotonic()
    run_jobs('variants.jsonl', [(f'niche-{i}', SONNET, VARIANT_PROMPT.format(head=head(by_id[i]), intro=by_id[i]['intro'][:LLM_INTRO_MAX],
                                                                             q=q[f'niche-{i}']), 400)
                                for i in read_json('query-books.json')['niche']])
    add_usage('variants', time.monotonic() - t)


def queries() -> list[dict]:
    qb = read_json('query-books.json')
    q = jsonl('queries.jsonl')
    known = [{'id': f'{kind}-{i}', 'kind': kind, 'book_id': i, 'q': q[f'{kind}-{i}']} for kind in BASE_KINDS
             for i in qb[kind]]
    if (OUT / 'variants.jsonl').exists():
        for key, text in jsonl('variants.jsonl').items():
            m = re.search(r'\{.*\}', text, re.S)
            got = json.loads(m.group(0)) if m else {}
            i = int(key.split('-', 1)[1])
            known += [{'id': f'{kind}-{i}', 'kind': kind, 'book_id': i, 'q': got[kind].strip()}
                      for kind in VARIANTS if (got.get(kind) or '').strip()]
    topic = [{'id': f"topic-{t['id']}", 'kind': 'topic', 'q': t['q'], 'type': t.get('type')}
             for t in json.loads((HERE / 'eval_questions.json').read_text(encoding='utf-8'))]
    return known + topic


def embed() -> None:
    import numpy as np
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(MODEL, device='cpu')
    tok = model.tokenizer
    stats = {}
    for name, rows in texts().items():
        path = OUT / f'vec-{name}.npy'
        lens = sorted(len(tok(r)['input_ids']) for r in rows)
        chars = sorted(len(r) for r in rows)
        stats[name] = {'tokens_median': lens[len(lens) // 2], 'tokens_p90': lens[int(len(lens) * .9)],
                       'tokens_max': lens[-1], 'chars_median': chars[len(chars) // 2]}
        if path.exists():
            continue
        t = time.monotonic()
        v = model.encode(rows, batch_size=16, normalize_embeddings=True, convert_to_numpy=True).astype('float32')
        np.save(path, v)
        stats[name]['seconds'] = round(time.monotonic() - t)
        print(name, stats[name], flush=True)
    qs = queries()
    np.save(OUT / 'vec-queries.npy', model.encode([q['q'] for q in qs], batch_size=32, normalize_embeddings=True,
                                                  convert_to_numpy=True).astype('float32'))
    write_json('queries.json', qs)
    old = json.loads((ML / 'pool-100000-intro.ids.json').read_text())
    row = {b: i for i, b in enumerate(old)}
    pool = np.load(ML / 'pool-100000-intro.npy', mmap_mode='r')
    books = read_json('books.json')
    base = np.load(OUT / 'vec-base300.npy')
    have = [(k, row[b['book_id']]) for k, b in enumerate(books) if b['book_id'] in row]
    cos = [float(base[k] @ (pool[r] / np.linalg.norm(pool[r]))) for k, r in have]
    stats['base300_vs_production'] = {'books': len(have), 'min_cosine': round(min(cos), 4),
                                      'mean_cosine': round(sum(cos) / len(cos), 5)}
    write_json('embed-stats.json', stats)
    print(json.dumps(stats['base300_vs_production']))


# ---------------------------------------------------------------- lengths

def lengths() -> None:
    import numpy as np
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(MODEL, device='cpu')
    books = read_json('books.json')
    rows = [f"{head(b)}\n{b['intro'][:LENGTH_INTRO_MAX]}" for b in books]
    full = [len(model.tokenizer(r)['input_ids']) for r in rows]
    path_stats = OUT / 'lengths-stats.json'
    stats = json.loads(path_stats.read_text()) if path_stats.exists() else {}
    stats['full_tokens'] = {'median': sorted(full)[len(full) // 2], 'p90': sorted(full)[int(len(full) * .9)],
                            'max': max(full)}
    for n in LENGTHS:
        path = OUT / f'vec-tok{n}.npy'
        if path.exists():
            continue
        model.max_seq_length = n
        t = time.monotonic()
        v = model.encode(rows, batch_size=8, normalize_embeddings=True, convert_to_numpy=True).astype('float32')
        np.save(path, v)
        stats[f'tok{n}'] = {'seconds': round(time.monotonic() - t), 'cut_books': sum(x > n for x in full),
                            'tokens_median': sorted(min(x, n) for x in full)[len(full) // 2]}
        path_stats.write_text(json.dumps(stats, indent=1))
        print(n, stats[f'tok{n}'], flush=True)


# ---------------------------------------------------------------- chunks

CHUNK_SIZES = (128, 256, 512)  # tokens per piece, head line included (parameterfreak.com 2026-09: KURE-v1 best at 256, 512)
CONTEXT_ROOM = 160             # extra tokens the model reads for the context sentence before the piece
SENTENCE_END = re.compile(r'(?<=[.!?。…])\s+|(?<=[다요죠음함임])\.?\s+(?=\S)|\n+')


def sentences(text: str) -> list[str]:
    return [s.strip() for s in SENTENCE_END.split(text) if s and s.strip()]


def pack(intro: str, room: int, count) -> list[str]:
    """The intro in pieces of at most `room` tokens that end at a sentence end: whole sentences are packed in order; a
    sentence longer than `room` alone is cut between words (never inside a word)."""
    out, cur, used = [], [], 0
    for s in sentences(intro):
        n = count(s)
        if n > room:
            if cur:
                out.append(' '.join(cur))
                cur, used = [], 0
            part, pn = [], 0
            for w in s.split(' '):
                wn = count(w)
                if part and pn + wn > room:
                    out.append(' '.join(part))
                    part, pn = [], 0
                part.append(w)
                pn += wn
            if part:
                out.append(' '.join(part))
            continue
        if cur and used + n > room:
            out.append(' '.join(cur))
            cur, used = [], 0
        cur.append(s)
        used += n
    if cur:
        out.append(' '.join(cur))
    return out


CONTEXT_PROMPT = """<document>
{head}
{intro}
</document>
위 문서(책 한 권의 정보와 소개글) 안의 한 조각입니다.
<chunk>
{chunk}
</chunk>
이 조각이 검색에서 잘 찾히도록, 문서 전체 안에서 이 조각의 맥락(어떤 책의 어떤 내용인지)을 짧고 간결한 한국어 1~2문장으로 쓰세요. 맥락만 답합니다."""


def chunks() -> None:
    """Per size N: chunk{N} = one vector per piece ("head line" + piece), ctx{N} = the same with a Haiku context sentence
    written from the whole intro before it (Anthropic's Contextual Retrieval). A book's score is its best piece."""
    import numpy as np
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(MODEL, device='cpu')
    tok = model.tokenizer

    def count(s: str) -> int:
        return len(tok(s, add_special_tokens=False)['input_ids'])

    books = read_json('books.json')
    path_stats = OUT / 'chunks-stats.json'
    stats = json.loads(path_stats.read_text()) if path_stats.exists() else {}
    for size in CHUNK_SIZES:
        plain, owner, keys, prompts, cut_words = [], [], [], [], 0
        for k, b in enumerate(books):
            h = head(b)
            intro = b['intro'][:LENGTH_INTRO_MAX]
            for n, p in enumerate(pack(intro, size - count(h) - 3, count)):
                plain.append(f'{h}\n{p}')
                owner.append(k)
                keys.append(f"{size}-{b['book_id']}-{n}")
                prompts.append((keys[-1], HAIKU, CONTEXT_PROMPT.format(head=h, intro=intro[:LLM_INTRO_MAX], chunk=p), 200))
                cut_words += p[-1] not in '.!?。…다요죠음함임")\'」』'
        for name in (f'chunk{size}', f'ctx{size}'):
            np.save(OUT / f'owner-{name}.npy', np.asarray(owner))
        per_book = np.bincount(owner)
        st = stats.setdefault(f'size{size}', {})
        st.update({'pieces': len(plain), 'per_book_mean': round(float(per_book.mean()), 2),
                   'per_book_max': int(per_book.max()), 'pieces_not_ending_a_sentence': cut_words})
        (OUT / f'pieces-{size}.json').write_text(json.dumps(dict(zip(keys, plain)), ensure_ascii=False, indent=0),
                                                 encoding='utf-8')
        path = OUT / f'vec-chunk{size}.npy'
        if not path.exists():
            model.max_seq_length = size + 8
            t = time.monotonic()
            np.save(path, model.encode(plain, batch_size=16, normalize_embeddings=True, convert_to_numpy=True).astype('float32'))
            st['plain_seconds'] = round(time.monotonic() - t)
            path_stats.write_text(json.dumps(stats, indent=1))
            print(f'chunk{size}', st, flush=True)
        t = time.monotonic()
        ctx = run_jobs(f'context-{size}.jsonl', prompts)
        add_usage(f'context-{size}', time.monotonic() - t)
        path = OUT / f'vec-ctx{size}.npy'
        if not path.exists():
            rows = [f'{ctx[key]}\n{text}' for key, text in zip(keys, plain)]
            model.max_seq_length = size + CONTEXT_ROOM
            t = time.monotonic()
            np.save(path, model.encode(rows, batch_size=16, normalize_embeddings=True, convert_to_numpy=True).astype('float32'))
            st['ctx_seconds'] = round(time.monotonic() - t)
            st['ctx_tokens_max'] = max(count(r) for r in rows)
        path_stats.write_text(json.dumps(stats, indent=1))
        print(f'ctx{size}', st, flush=True)


def methods() -> list[str]:
    names = list(TEXTS) + [f'tok{n}' for n in LENGTHS] + [f'{kind}{n}' for n in CHUNK_SIZES for kind in ('chunk', 'ctx')]
    return [n for n in names if (OUT / f'vec-{n}.npy').exists()]


def book_scores(name: str, qv):
    """queries x books: the dot product, or for piece vectors the best piece of each book."""
    import numpy as np
    bv = np.load(OUT / f'vec-{name}.npy')
    scores = qv @ bv.T
    owner_path = OUT / f'owner-{name}.npy'
    if not owner_path.exists():
        return scores
    owner = np.load(owner_path)
    out = np.full((scores.shape[0], owner.max() + 1), -np.inf, dtype=scores.dtype)
    for col, k in enumerate(owner):
        np.maximum(out[:, k], scores[:, col], out=out[:, k])
    return out


# ---------------------------------------------------------------- keyword search (as production, 1-how-it-works 4-7)

KW_ENDING = re.compile(r'(으로|에서|에게|한테|이랑|하고|까지|부터|처럼|같은|이나|에|의|을|를|이|가|은|는|와|과|로|도|만|요)$')
KW_SPLIT = re.compile(r'[\s,.?!·~"\'()]+')
KW_STOP = {'책', '도서', '관련', '사람', '처음', '위한', '대한', '읽는', '추천', '추천해줘', '추천해', '알려줘', '찾아줘', '읽을', '읽기',
           '좋은', '있어', '있나요', '있을까요', '싶어', '싶은', '거', '것', '걸', '같은', '비슷한', '처럼', '주세요', '해줘', '좀', '그냥',
           '말고', '빼고', '없는', '그거', '이거', '저거', '그런', '그게', '아니', '아니아니', '다른', '아까', '다시', '그거말고',
           '이거말고', '얘기', '이야기'}
KW_DEPTH = 50  # keyword candidates mixed with the vector ones
VEC_DEPTH = 60
RRF_K = 60


def question_words(q: str) -> list[str]:
    """QuestionWords.of: the ML first call's keywords."""
    out = []
    for piece in KW_SPLIT.split(q):
        w = KW_ENDING.sub('', piece, count=1)
        if len(w) >= 2 and w not in KW_STOP and w not in out:
            out.append(w)
    return out[:8]


def tidy(s: str) -> str:
    import unicodedata
    return re.sub(r'\s+', '', unicodedata.normalize('NFKC', s).lower())


TERMS_PROMPT = """<book>
{head}
{intro}
</book>
이 책을 낱말 검색으로 찾을 수 있게, 사람들이 검색창에 칠 만한 낱말을 30~50개 쓰세요.
- 소개글에 나오는 주제어, 개념, 이론, 기법, 실존 인물, 사건, 시대, 지명, 전문 용어를 빠짐없이 원문 표기 그대로.
- 분야, 형식(소설, 에세이, 그림책 등), 대상 독자, 그리고 그 낱말들을 사람들이 흔히 부르는 다른 이름(동의어, 줄임말, 영어 이름).
- 소개글에 없는 내용을 지어내지 않습니다. 제목·저자는 쓰지 않습니다.
- 낱말만 쉼표로 이어서 한 줄로 답합니다."""


def lines_by_book() -> dict[int, str]:
    return {int(r['book_id']): r['text'] for r in read_tsv(DATA / 'aisearch' / '100k' / 'search-lines.tsv')}


def terms() -> None:
    """Haiku search words per book (for the keyword text kw_terms)."""
    books = read_json('books.json')
    t = time.monotonic()
    run_jobs('terms.jsonl', [(str(b['book_id']), HAIKU, TERMS_PROMPT.format(head=head(b), intro=b['intro'][:LLM_INTRO_MAX]), 600)
                             for b in books])
    add_usage('terms', time.monotonic() - t)


def keyword_texts() -> dict[str, list[str]]:
    """The text the keyword search matches in, after title + author:
    kw_line    the Haiku search line (production search_text, about 100 characters)
    kw_intro   the line + the whole intro
    kw_summary the line + the Haiku summary (about 250 characters)
    kw_terms   the line + 30-50 Haiku search words (terms step)"""
    lines = lines_by_book()
    summary = jsonl('summary.jsonl')
    words = jsonl('terms.jsonl') if (OUT / 'terms.jsonl').exists() else {}
    books = read_json('books.json')
    out = {'kw_line': [], 'kw_intro': [], 'kw_summary': []} | ({'kw_terms': []} if words else {})
    for b in books:
        base = tidy(b['title']) + tidy(b['author']) + tidy(lines.get(b['book_id'], ''))
        out['kw_line'].append(base)
        out['kw_intro'].append(base + tidy(b['intro']))
        out['kw_summary'].append(base + tidy(summary[str(b['book_id'])]))
        if words:
            out['kw_terms'].append(base + '|' + '|'.join(tidy(w) for w in words[str(b['book_id'])].split(',')))
    write_json('keyword-stats.json', {'books_with_line': sum(b['book_id'] in lines for b in books),
                                      'line_chars_median': sorted(len(lines.get(b['book_id'], '')) for b in books)[500]})
    return out


def keyword_order(words: list[str], texts: list[str]) -> list[int]:
    """Book indexes that hold at least one word, most words first (ties: sample order, production uses plan libraries)."""
    words = [tidy(w) for w in words]
    scored = [(-sum(w in t for w in words), i) for i, t in enumerate(texts)]
    return [i for s, i in sorted(scored) if s < 0]


BM25_K1, BM25_B = 1.2, 0.75


def bm25_orders(texts: list[str], qs: list[dict], unit: str) -> list[list[int]]:
    """BM25 instead of the count of matched words (user 2026-10-29). unit 'word' = the question words found as
    substrings (as the LIKE search, but weighted by how rare a word is and how often it stands in the text, the text
    length evened out); unit '2gram' = two-letter pieces of the question words against those of the text, as pg_bigm
    cuts them (no Korean word analyser on the server). Document frequencies come from the 1,000 sample books."""
    from collections import Counter
    import math as m
    n = len(texts)
    avg = sum(len(t) for t in texts) / n
    grams = [Counter(t[i:i + 2] for i in range(len(t) - 1)) for t in texts] if unit == '2gram' else None
    df_cache = {}

    def tf(term, d):
        return grams[d].get(term, 0) if grams else texts[d].count(term)

    def df(term):
        if term not in df_cache:
            df_cache[term] = sum(tf(term, d) > 0 for d in range(n))
        return df_cache[term]

    out = []
    for q in qs:
        words = [tidy(w) for w in question_words(q['q'])]
        terms = sorted({w[i:i + 2] for w in words for i in range(len(w) - 1)}) if grams else words
        score = [0.0] * n
        for term in terms:
            k = df(term)
            if not k:
                continue
            idf = m.log(1 + (n - k + 0.5) / (k + 0.5))
            for d in range(n):
                f = tf(term, d)
                if f:
                    score[d] += idf * f * (BM25_K1 + 1) / (f + BM25_K1 * (1 - BM25_B + BM25_B * len(texts[d]) / avg))
        out.append(sorted((d for d in range(n) if score[d] > 0), key=lambda d: (-score[d], d)))
    return out


# A local test container only (not a project database): the local db image + pgvector + ParadeDB pg_search 0.26.3,
# built from data/aisearch-intro-text-2026-10-29/pgsearch-image/Dockerfile, run as chaekcheck-pgsearch-test on 5530.
PGSEARCH_DSN = 'host=localhost port=5530 dbname=t user=postgres password=test'
PGSEARCH_TOKENIZERS = {'ko': 'pdb.lindera(korean)', '2g': 'pdb.ngram(2,2)'}


def pgsearch() -> None:
    """BM25 in PostgreSQL (user 2026-10-29: "db확장이 나아보여"): per keyword text one table + one paradedb index, by
    the Korean word analyser (Lindera, KoDic) and by two-letter pieces; the question words searched with ||| (any
    word), top KW_DEPTH by pdb.score. The texts keep their spaces (the analyser needs them), title and author first."""
    import psycopg
    books = read_json('books.json')
    qs = read_json('queries.json')
    lines = lines_by_book()
    summary = jsonl('summary.jsonl')
    words = jsonl('terms.jsonl')
    texts = {}
    for b in books:
        base = f"{b['title']} {b['author']} {lines.get(b['book_id'], '')}"
        texts.setdefault('line', []).append(base)
        texts.setdefault('intro', []).append(base + ' ' + b['intro'])
        # without the search line (user 2026-10-29): title + author + the whole intro
        texts.setdefault('introonly', []).append(f"{b['title']} {b['author']} {b['intro']}")
        texts.setdefault('summary', []).append(base + ' ' + summary[str(b['book_id'])])
        texts.setdefault('terms', []).append(base + ' ' + words[str(b['book_id'])])
    where = {b['book_id']: i for i, b in enumerate(books)}
    out, stats = {}, {}
    with psycopg.connect(PGSEARCH_DSN, autocommit=True) as db:
        for text, rows in texts.items():
            for tok, cast in PGSEARCH_TOKENIZERS.items():
                table = f'kw_{text}_{tok}'
                db.execute(f'DROP TABLE IF EXISTS {table}')
                db.execute(f'CREATE TABLE {table} (id bigint PRIMARY KEY, body text NOT NULL)')
                with db.cursor().copy(f'COPY {table} (id, body) FROM STDIN') as cp:
                    for b, body in zip(books, rows):
                        cp.write_row((b['book_id'], body))
                t = time.monotonic()
                db.execute(f'CREATE INDEX {table}_idx ON {table} USING paradedb (id, (body::{cast}))')
                built = time.monotonic() - t
                t = time.monotonic()
                orders = []
                for q in qs:
                    got = db.execute(f'SELECT id FROM {table} WHERE body ||| %s ORDER BY pdb.score(id) DESC, id '
                                     f'LIMIT {KW_DEPTH}', (' '.join(question_words(q['q'])),)).fetchall()
                    orders.append([where[r[0]] for r in got])
                name = f'pg{tok}_{text}'
                out[name] = orders
                stats[name] = {'index_seconds': round(built, 2), 'query_ms_mean': round((time.monotonic() - t) * 1000 / len(qs), 1),
                               'empty_queries': sum(not o for o in orders)}
                print(name, stats[name])
    write_json('pgsearch-orders.json', out)
    write_json('pgsearch-stats.json', stats)


def rrf(*lists: list[int]) -> list[int]:
    score = {}
    for lst in lists:
        for r, i in enumerate(lst):
            score[i] = score.get(i, 0) + 1 / (RRF_K + r + 1)
    return sorted(score, key=lambda i: -score[i])


# ---------------------------------------------------------------- rank

def rank() -> None:
    """Per method and query: the rank of the asked book (known-book queries, beyond the list = 1001) and the top 10.
    Methods: every vector text, the two keyword texts, and each vector text mixed with each keyword text by RRF as the
    candidate step does (keyword top 50 + vector top 60)."""
    import numpy as np
    books = read_json('books.json')
    ids = [b['book_id'] for b in books]
    where = {b: i for i, b in enumerate(ids)}
    qs = read_json('queries.json')
    qv = np.load(OUT / 'vec-queries.npy')
    orders = {}
    scores = {}
    for name in methods():
        scores[name] = book_scores(name, qv)
        orders[name] = [list(row) for row in np.argsort(-scores[name], axis=1)]
    # two layers (user 2026-10-29): one summary vector per book + detail pieces, joined by the better score (max) or by
    # RRF of the two lists
    vectors = methods()
    for detail in [n for n in vectors if n.startswith(('chunk', 'ctx'))]:
        both = np.maximum(scores['summary'], scores[detail])
        orders[f'two-max-{detail}'] = [list(row) for row in np.argsort(-both, axis=1)]
        orders[f'two-rrf-{detail}'] = [rrf(orders['summary'][i][:VEC_DEPTH], orders[detail][i][:VEC_DEPTH])
                                       for i in range(len(qs))]
        vectors += [f'two-max-{detail}', f'two-rrf-{detail}']
    kw = keyword_texts()
    kw_names = []
    for name, texts in kw.items():
        orders[name] = [keyword_order(question_words(q['q']), texts) for q in qs]
        for unit in ('word', '2gram'):
            orders[f'bm25{unit}_{name[3:]}'] = bm25_orders(texts, qs, unit)
        kw_names += [name, f'bm25word_{name[3:]}', f'bm252gram_{name[3:]}']
    if (OUT / 'pgsearch-orders.json').exists():
        for name, per_q in read_json('pgsearch-orders.json').items():
            orders[name] = per_q
            kw_names.append(name)
    for v in vectors:
        for k in kw_names:
            orders[f'{v}+{k}'] = [rrf(orders[k][i][:KW_DEPTH], orders[v][i][:VEC_DEPTH]) for i in range(len(qs))]
    out = {}
    for name, per_q in orders.items():
        rows = {}
        for q, order in zip(qs, per_q):
            row = {'top10': [ids[j] for j in order[:10]]}
            if 'book_id' in q:
                if name == PICK_FROM:
                    row['top30'] = [ids[j] for j in order[:PICK_CANDIDATES]]
                pos = order.index(where[q['book_id']]) if where[q['book_id']] in order else None
                row['rank'] = pos + 1 if pos is not None else len(ids) + 1
            rows[q['id']] = row
        out[name] = rows
    write_json('ranks.json', out)
    write_json('question-words.json', {q['id']: question_words(q['q']) for q in qs})


# ---------------------------------------------------------------- pick (the text Haiku reads in the 2nd call)

PICK_PROMPT = """질문: {q}

아래 후보 책 가운데 이 질문에 맞는 책을 맞는 순서로 0~8권 고르세요. 책 정보만으로 맞는다고 확인되는 책만 고르고, 맞는 책이 없으면 고르지 않습니다.
fit: 3 딱 맞음, 2 대체로 맞음, 1 일부만 맞음.

{books}

JSON 한 줄로만 답하세요: {{"picks": [{{"no": 번호, "fit": 1~3}}, ...]}}"""

PICK_CANDIDATES = 30
PICK_FROM = 'base300+kw_line'  # today's candidates (vector + keyword, RRF)


def reading_texts() -> dict[str, dict[int, str]]:
    """What Haiku reads per book after "제목 / 저자 / 출간": line = today's 「한 줄」 (about 100 characters), summary = the
    Haiku summary (about 250-300), intro500 = the first 500 intro characters."""
    lines = lines_by_book()
    summary = jsonl('summary.jsonl')
    out = {'line': {}, 'summary': {}, 'intro500': {}}
    for b in read_json('books.json'):
        out['line'][b['book_id']] = lines.get(b['book_id'], '')
        out['summary'][b['book_id']] = summary[str(b['book_id'])]
        out['intro500'][b['book_id']] = b['intro'][:500]
    return out


SNIPPET_CHARS = 160


def snippet(intro: str, words: list[str]) -> str:
    """The intro part around the first question word found in it (longest word first), about SNIPPET_CHARS long, as
    pg_search's pdb.snippet would hand it; '' when no word stands in the intro (user 2026-10-29: Haiku said a book had
    no 플로이드-워셜 although its intro has it)."""
    for w in sorted(words, key=len, reverse=True):
        at = intro.find(w)
        if at >= 0:
            start = max(0, at - SNIPPET_CHARS // 2)
            return ('…' if start else '') + intro[start:start + SNIPPET_CHARS] + '…'
    return ''


def pick() -> None:
    """For each known-book query: today's 30 candidates with the asked book always among them (put at a random place when
    the candidate step missed it), shown to Haiku with each reading text; did Haiku pick the asked book, and how many."""
    books = {b['book_id']: b for b in read_json('books.json')}
    ranks = read_json('ranks.json')
    qs = [q for q in read_json('queries.json') if q['kind'] in KNOWN]
    reading = reading_texts()
    rng = random.Random(SEED)
    cands = {}
    jobs = []
    full = read_json('pick-candidates.json') if (OUT / 'pick-candidates.json').exists() else {}
    for q in qs:
        if q['id'] in full:
            c = full[q['id']]
        else:
            c = list(rank_list(ranks, PICK_FROM, q['id']))[:PICK_CANDIDATES]
            if q['book_id'] not in c:
                c = c[:PICK_CANDIDATES - 1]
                c.insert(rng.randrange(len(c) + 1), q['book_id'])
            full[q['id']] = c
        cands[q['id']] = c
        words = question_words(q['q'])
        snips = {b: snippet(books[b]['intro'], words) for b in c}
        per_q = dict(reading)
        for base in ('line', 'summary'):
            per_q[f'{base}_snip'] = {b: reading[base][b] + (f' / 소개 일부: {snips[b]}' if snips[b] else '') for b in c}
        for name, text in per_q.items():
            listing = '\n'.join(f"{n + 1}. 제목: {books[b]['title']} / 저자: {books[b]['author'] or '(모름)'}"
                                f"{' / 출간: ' + books[b]['year'] + '년' if books[b]['year'] else ''}"
                                f"{' / 한 줄: ' + text[b] if text[b] else ''}" for n, b in enumerate(c))
            jobs.append((f"{name}|{q['id']}", HAIKU, PICK_PROMPT.format(q=q['q'], books=listing), 400))
    write_json('pick-candidates.json', full)
    t = time.monotonic()
    run_jobs('pick.jsonl', jobs)
    add_usage('pick', time.monotonic() - t)


def rank_list(ranks: dict, name: str, qid: str) -> list[int]:
    return ranks[name][qid].get('top30') or ranks[name][qid]['top10']


def pick_report() -> dict:
    if not (OUT / 'pick.jsonl').exists():
        return {}
    qs = {q['id']: q for q in read_json('queries.json')}
    cands = read_json('pick-candidates.json')
    out = {}
    for key, text in jsonl('pick.jsonl').items():
        name, qid = key.split('|', 1)
        m = re.search(r'\{.*\}', text, re.S)
        try:
            picks = json.loads(m.group(0))['picks'] if m else []
        except json.JSONDecodeError:
            picks = []
        c = cands[qid]
        got = [c[p['no'] - 1] for p in picks if isinstance(p.get('no'), int) and 0 < p['no'] <= len(c)]
        r = out.setdefault(name, {}).setdefault(qs[qid]['kind'], {'n': 0, 'picked': 0, 'first': 0, 'picks': 0})
        r['n'] += 1
        r['picked'] += qs[qid]['book_id'] in got
        r['first'] += bool(got) and got[0] == qs[qid]['book_id']
        r['picks'] += len(got)
    for per in out.values():
        for r in per.values():
            r['picked_rate'] = round(r['picked'] / r['n'], 3)
            r['first_rate'] = round(r['first'] / r['n'], 3)
            r['picks_mean'] = round(r['picks'] / r['n'], 2)
    return out


# ---------------------------------------------------------------- judge

JUDGE_PROMPT = """검색어: {q}

아래 책마다 이 검색어로 찾는 사람에게 맞는 책인지 0~3으로 매기세요.
3 딱 맞음, 2 대체로 맞음, 1 주제만 조금 닿음, 0 맞지 않음.
책 정보는 제목·저자·소개글만 보고 판단합니다.

{books}

JSON 한 줄로만 답하세요: {{"<번호>": 점수, ...}}"""


def judged_methods(ranks: dict) -> list[str]:
    """The judge grades the main vector texts alone, the keyword searches (today's count, pg_search Korean BM25) on the
    line and on the line + intro, and the candidate lists that matter for a decision: today's mix and the mixes of the
    proposed vectors with Korean BM25 (every one of the 666 lists would make the pools too big)."""
    vectors = ('base300', 'summary', 'tok1024', 'ctx128', 'two-max-chunk512', 'two-max-ctx128', 'two-max-ctx512')
    keywords = ('kw_line', 'kw_intro', 'pgko_line', 'pgko_intro')
    mixes = ['base300+kw_line'] + [f'{v}+pgko_intro' for v in vectors]
    return [n for n in (*vectors, *keywords, *mixes) if n in ranks]


def judge() -> None:
    books = {b['book_id']: b for b in read_json('books.json')}
    ranks = read_json('ranks.json')
    names = judged_methods(ranks)
    qs = [q for q in read_json('queries.json') if q['kind'] == 'topic']
    rng = random.Random(SEED)
    jobs = []
    (OUT / 'judge-pools').mkdir(exist_ok=True)
    for q in qs:
        pool = sorted({b for name in names for b in ranks[name][q['id']]['top10']})
        rng.shuffle(pool)
        old = OUT / 'judge-pools' / f"{q['id']}.json"
        if old.exists() and set(json.loads(old.read_text())) != set(pool):
            raise SystemExit(f'{old} holds another pool: move judge.jsonl and judge-pools away first')
        listing = '\n\n'.join(f"[{n + 1}] {head(books[b])}\n{books[b]['intro'][:JUDGE_INTRO_MAX]}" for n, b in enumerate(pool))
        jobs.append((q['id'], HAIKU, JUDGE_PROMPT.format(q=q['q'], books=listing), 1500))
        if not old.exists():
            old.write_text(json.dumps(pool))
    t = time.monotonic()
    run_jobs('judge.jsonl', jobs)
    add_usage('judge', time.monotonic() - t)


def grades() -> dict[str, dict[int, int]]:
    out = {}
    for key, text in jsonl('judge.jsonl').items():
        pool = json.loads((OUT / 'judge-pools' / f'{key}.json').read_text())
        m = re.search(r'\{.*\}', text, re.S)
        got = json.loads(m.group(0)) if m else {}
        out[key] = {b: int(got[str(n + 1)]) for n, b in enumerate(pool) if str(n + 1) in got}
    return out


# ---------------------------------------------------------------- report

def report() -> None:
    ranks = read_json('ranks.json')
    qs = read_json('queries.json')
    g = grades() if (OUT / 'judge.jsonl').exists() else {}
    judged = set(judged_methods(ranks))
    res = {}
    for name in ranks:
        r = {}
        for kind in KNOWN + ('all',):
            rk = [ranks[name][q['id']]['rank'] for q in qs if q['kind'] == kind or (kind == 'all' and q['kind'] in KNOWN)]
            r[kind] = {'n': len(rk), 'recall@1': round(sum(x <= 1 for x in rk) / len(rk), 3),
                       'recall@10': round(sum(x <= 10 for x in rk) / len(rk), 3),
                       'recall@30': round(sum(x <= 30 for x in rk) / len(rk), 3),
                       'mrr': round(sum(1 / x for x in rk) / len(rk), 3),
                       'median_rank': sorted(rk)[len(rk) // 2]}
        if g and name in judged:
            p10, mean_g, ndcg, missing = [], [], [], 0
            for q in qs:
                if q['kind'] != 'topic' or q['id'] not in g:
                    continue
                gq = g[q['id']]
                got = [gq.get(b) for b in ranks[name][q['id']]['top10']]
                missing += sum(x is None for x in got)
                got = [x or 0 for x in got] + [0] * (10 - len(got))
                p10.append(sum(x >= 2 for x in got) / 10)
                mean_g.append(sum(got) / 10)
                ideal = sorted(gq.values(), reverse=True)[:10]
                dcg = sum((2 ** x - 1) / math.log2(i + 2) for i, x in enumerate(got))
                idcg = sum((2 ** x - 1) / math.log2(i + 2) for i, x in enumerate(ideal))
                ndcg.append(dcg / idcg if idcg else 0)
            r['topic'] = {'n': len(p10), 'p@10(>=2)': round(sum(p10) / len(p10), 3),
                          'mean_grade': round(sum(mean_g) / len(mean_g), 3),
                          'ndcg@10': round(sum(ndcg) / len(ndcg), 3), 'unjudged': missing}
        res[name] = r
    # paired with today's way (vector base300 mixed with the production keyword text): per known-book query, did the
    # asked book move into / out of the top 10 and the top 30 (the 30 candidates Haiku sees), and a bootstrap of the mrr
    # gap. The plain vector base300 is the base of the vector texts alone.
    rng = random.Random(SEED)
    paired = {}
    known = [q for q in qs if q['kind'] in KNOWN]
    for base in ('base300', 'base300+kw_line'):
        for name in ranks:
            if name == base or ('+' in name) != ('+' in base):
                continue
            p = {}
            for kind in KNOWN + ('all',):
                sel = [q for q in known if kind == 'all' or q['kind'] == kind]
                a = [ranks[base][q['id']]['rank'] for q in sel]
                b = [ranks[name][q['id']]['rank'] for q in sel]
                gaps = [1 / y - 1 / x for x, y in zip(a, b)]
                boots = sorted(sum(rng.choice(gaps) for _ in gaps) / len(gaps) for _ in range(2000))
                p[kind] = {'r10_win': sum(y <= 10 < x for x, y in zip(a, b)),
                           'r10_loss': sum(x <= 10 < y for x, y in zip(a, b)),
                           'r30_win': sum(y <= 30 < x for x, y in zip(a, b)),
                           'r30_loss': sum(x <= 30 < y for x, y in zip(a, b)),
                           'worse_rank': sum(y > x for x, y in zip(a, b)),
                           'mrr_gap': round(sum(gaps) / len(gaps), 3),
                           'mrr_gap_95': [round(boots[50], 3), round(boots[1949], 3)]}
            paired[f'{name} vs {base}'] = p
    extra = {n: json.loads((OUT / f'{n}-stats.json').read_text()) for n in ('lengths', 'chunks', 'keyword')
             if (OUT / f'{n}-stats.json').exists()}
    out = {'results': res, 'paired': paired, 'pick': pick_report(), 'embed': read_json('embed-stats.json'), **extra,
           'usage': json.loads((OUT / 'usage.json').read_text())}
    write_json('report.json', out)
    print('report.json written:', len(res), 'methods')


def queries_step() -> None:
    """Encode the query set again (after new queries were written)."""
    import numpy as np
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(MODEL, device='cpu')
    qs = queries()
    np.save(OUT / 'vec-queries.npy', model.encode([q['q'] for q in qs], batch_size=32, normalize_embeddings=True,
                                                  convert_to_numpy=True).astype('float32'))
    write_json('queries.json', qs)
    print(len(qs), 'queries')



if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    step = sys.argv[1] if len(sys.argv) > 1 else ''
    steps = {'sample': sample, 'llm': llm, 'embed': embed, 'queries': queries_step, 'lengths': lengths, 'chunks': chunks,
             'terms': terms, 'variants': variants, 'pgsearch': pgsearch, 'rank': rank, 'pick': pick, 'judge': judge, 'report': report}
    if step not in steps:
        raise SystemExit('step: ' + ' | '.join(steps))
    steps[step]()
