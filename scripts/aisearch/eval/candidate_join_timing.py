"""Cycle 26 question (plan, 2026-10-28): how much slower is the AI search candidate query when it filters by
catalog.book.library_ids (bigint[], joined on book_id) instead of aisearch.search_book.library_ids (integer[])?

Runs on a copy container (chaekcheck-latency26: catalog.book + aisearch.search_book from the local db, production's
postgres settings, --memory 1g), never on production. For each question's keywords (QuestionWords as the ML first call
makes them) and the 25 most-held libraries as the plan:
  warm: 1 run to fill the cache, then 9 EXPLAIN (ANALYZE, BUFFERS) runs, median and max
  cold: container restarted and the WSL page cache dropped before each single run
  python candidate_join_timing.py <out.json>"""
import json, re, statistics, subprocess, sys, time

C = 'chaekcheck-latency26'
QUESTIONS = ['소설 말고 위로가 되는 책', '자기계발서 말고 마음이 편해지는 에세이', '수식 없이 읽는 물리학 책', '번역서 말고 한국 작가가 쓴 경제 책',
             '공포 말고 따뜻한 판타지 소설', '어린이책 말고 어른이 보는 그림책', '출근길 지하철에서 가볍게 읽을 과학책', '기분 전환이 되는 웃긴 소설',
             '경제를 처음 공부하는 사람을 위한 책', '회사에서 글쓰기 실력을 늘리는 책', '여행 가서 읽을, 너무 무겁지 않은 인문학 책',
             '미래 소비 트렌드와 경제적 자산 관리', '사진', '음악', '바다', '게임', '커피', '로마', '전쟁', '엄마', '자바스크립트', '주식 투자',
             '다이어트', '영어 회화']
SPLIT = re.compile(r"[\s,.?!·~\"'()]+")
ENDING = re.compile(r'(으로|에서|에게|한테|이랑|하고|까지|부터|처럼|같은|이나|에|의|을|를|이|가|은|는|와|과|로|도|만|요)$')
STOP = set('책 도서 관련 사람 처음 위한 대한 읽는 추천 추천해줘 추천해 알려줘 찾아줘 읽을 읽기 좋은 있어 있나요 있을까요 싶어 싶은 거 것 걸 같은 '
           '비슷한 처럼 주세요 해줘 좀 그냥 말고 빼고 없는'.split())


def words(q):
    """QuestionWords.of, then CandidateQuery.usableKeywords' tidy (lower case)."""
    out = []
    for piece in SPLIT.split(q):
        w = ENDING.sub('', piece).lower()
        if len(w) >= 2 and w not in STOP and w not in out:
            out.append(w)
    return out[:8]


def psql(sql):
    r = subprocess.run(['wsl', '-d', 'Ubuntu', '--', 'docker', 'exec', '-i', C, 'psql', '-U', 'chaekcheck', '-d', 'chaekcheck', '-tA',
                        '-v', 'ON_ERROR_STOP=1'], input=sql.encode('utf-8'), capture_output=True)
    if r.returncode:
        raise SystemExit(r.stderr.decode('utf-8', 'replace'))
    return r.stdout.decode('utf-8', 'replace')


def query(kind, ws, plan):
    lit = lambda w: "'%" + w.replace("'", "''") + "%'"
    score = ' + '.join(f'(s.search_text LIKE {lit(w)})::int' for w in ws)
    anyw = ' OR '.join(f's.search_text LIKE {lit(w)}' for w in ws)
    if kind == 'A':
        pc = f"(SELECT count(*) FROM unnest(s.library_ids) AS l(id) WHERE l.id = ANY('{{{plan}}}'::integer[]))::int"
        frm, where = 'aisearch.search_book s', f"s.library_ids && '{{{plan}}}'::integer[]"
    else:
        pc = f"(SELECT count(*) FROM unnest(c.library_ids) AS l(id) WHERE l.id = ANY('{{{plan}}}'::bigint[]))::int"
        frm, where = 'aisearch.search_book s JOIN catalog.book c ON c.id = s.book_id', f"c.library_ids && '{{{plan}}}'::bigint[]"
    return (f"SELECT book_id, score, plan_count FROM (SELECT s.book_id, {score} AS score, {pc} AS plan_count, 0 AS core_score "
            f"FROM {frm} WHERE ({anyw}) AND {where} AND NOT (s.book_id = ANY('{{}}'::bigint[]))) m "
            f"ORDER BY core_score DESC, score DESC, plan_count DESC, book_id LIMIT 50")


def explain(sql):
    plan = json.loads(psql('EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ' + sql + ';'))[0]
    top = plan['Plan']
    return {'ms': plan['Execution Time'], 'hit': top.get('Shared Hit Blocks', 0), 'read': top.get('Shared Read Blocks', 0)}


def cold_start():
    subprocess.run(['wsl', '-d', 'Ubuntu', '--', 'docker', 'restart', C], capture_output=True)
    subprocess.run(['wsl', '-d', 'Ubuntu', '-u', 'root', '--', 'sh', '-c', 'sync; echo 3 > /proc/sys/vm/drop_caches'], capture_output=True)
    for _ in range(30):
        if subprocess.run(['wsl', '-d', 'Ubuntu', '--', 'docker', 'exec', C, 'pg_isready', '-U', 'chaekcheck'], capture_output=True).returncode == 0:
            break
        time.sleep(1)
    psql('select 1;')


def main(out):
    plan = psql("select string_agg(id::text, ',') from (select unnest(library_ids) id, count(*) c from aisearch.search_book "
                "group by 1 order by 2 desc limit 25) x;").strip()
    sizes = psql("select pg_relation_size('catalog.book'), pg_total_relation_size('catalog.book'), "
                 "pg_relation_size('aisearch.search_book'), pg_total_relation_size('aisearch.search_book');").strip().split('|')
    sets = []
    for q in QUESTIONS:
        w = words(q)
        if w and w not in [s['words'] for s in sets]:
            sets.append({'q': q, 'words': w})
    res = {'plan_libraries': plan, 'sizes_bytes': dict(zip(['catalog.book heap', 'catalog.book total', 'search_book heap',
                                                             'search_book total'], map(int, sizes))), 'rows': []}
    for s in sets:
        row = dict(s)
        for kind in 'AB':
            sql = query(kind, s['words'], plan)
            explain(sql)
            runs = [explain(sql) for _ in range(9)]
            row[kind + '_warm'] = {'median_ms': round(statistics.median(r['ms'] for r in runs), 2),
                                   'max_ms': round(max(r['ms'] for r in runs), 2),
                                   'blocks': runs[-1]['hit'] + runs[-1]['read'], 'read': runs[-1]['read']}
            row[kind + '_count'] = int(psql(f'SELECT count(*) FROM ({sql}) x;').strip())
        for kind in 'AB':
            cold_start()
            row[kind + '_cold'] = explain(query(kind, s['words'], plan))
        res['rows'].append(row); print(json.dumps(row, ensure_ascii=False), flush=True)
    # one plan of each, as text, for the record
    res['plan_text'] = {k: psql('EXPLAIN (ANALYZE, BUFFERS) ' + query(k, sets[0]['words'], plan) + ';') for k in 'AB'}
    json.dump(res, open(out, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main(sys.argv[1])
