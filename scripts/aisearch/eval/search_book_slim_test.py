"""search_book before / after changeset aisearch-004 (integer library ids, "C" collation for line and search_text),
on copies only: size, the four query times, an import's delete-and-insert, then VACUUM (FULL, ANALYZE) and ANALYZE."""
import json, statistics, subprocess, time

import os
_REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
_PROJECTS = os.path.dirname(_REPO)  # the folder that holds this repo and chaekcheck-data
OUT = os.path.join(_REPO, 'data', 'scratch', r'ev3\dbtest2.json')


def psql(sql):
    r = subprocess.run(['wsl', '-d', 'Ubuntu', '--', 'docker', 'exec', '-i', 'chaekcheck-db-1', 'psql', '-U', 'chaekcheck', '-d',
                        'chaekcheck', '-tA', '-v', 'ON_ERROR_STOP=1'], input=sql.encode('utf-8'), capture_output=True)
    if r.returncode:
        raise SystemExit(r.stderr.decode('utf-8', 'replace'))
    return r.stdout.decode('utf-8', 'replace')


def timed(sql):
    t = time.time(); psql('select 1;'); over = time.time() - t
    t = time.time(); psql(sql)
    return round((time.time() - t - over) * 1000)


plan = psql("select string_agg(id::text, ',') from (select unnest(library_ids) id, count(*) c from aisearch.search_book "
            "group by 1 order by 2 desc limit 25) x;").strip()
ids30 = psql("select string_agg(book_id::text, ',') from (select book_id from aisearch.search_book order by book_id "
             "limit 30 offset 5000) x;").strip()


def queries(table, typ):
    pc = f"(SELECT count(*) FROM unnest(s.library_ids) AS l(id) WHERE l.id = ANY('{{{plan}}}'::{typ}[]))::int"

    def kw(words):
        score = ' + '.join(f"(s.search_text LIKE '%{w}%')::int" for w in words)
        anyw = ' OR '.join(f"s.search_text LIKE '%{w}%'" for w in words)
        return (f"SELECT book_id FROM (SELECT s.book_id, {score} AS score, {pc} AS plan_count FROM {table} s "
                f"WHERE ({anyw}) AND s.library_ids && '{{{plan}}}'::{typ}[]) m ORDER BY score DESC, plan_count DESC, book_id LIMIT 50")
    return {'common 소설': kw(['소설']), 'two 기후 위기': kw(['기후', '위기']), 'four 다이어트 식단 레시피 건강': kw(['다이어트', '식단', '레시피', '건강']),
            'counts30': f"SELECT s.book_id, {pc} FROM {table} s WHERE s.book_id = ANY('{{{ids30}}}'::bigint[]) "
                        f"AND s.library_ids && '{{{plan}}}'::{typ}[]"}


def time_query(q, n=9):
    ts = [json.loads(psql('EXPLAIN (ANALYZE, FORMAT JSON) ' + q + ';'))[0]['Execution Time'] for _ in range(n)]
    return round(statistics.median(ts[1:]), 1)


def size(t):
    heap, idx, total = psql(f"select pg_relation_size('{t}'), pg_indexes_size('{t}'), pg_total_relation_size('{t}');").strip().split('|')
    return {'heap': round(int(heap) / 1048576, 1), 'index': round(int(idx) / 1048576, 1), 'total': round(int(total) / 1048576, 1)}


def copy(name):
    psql(f"""DROP TABLE IF EXISTS {name}; CREATE TABLE {name} AS SELECT * FROM aisearch.search_book;
ALTER TABLE {name} ADD PRIMARY KEY (book_id); CREATE INDEX ON {name} USING gin (search_text gin_bigm_ops);""")


res = {'plan_libraries': 25}
res['now (real table, after the 2026-10-26 import)'] = {'size': size('aisearch.search_book'),
                                                         'q': {k: time_query(v) for k, v in queries('aisearch.search_book', 'bigint').items()}}
copy('aisearch.sb_after')
res['changeset_ms'] = timed("""ALTER TABLE aisearch.sb_after ALTER COLUMN library_ids DROP DEFAULT,
  ALTER COLUMN library_ids TYPE integer[] USING library_ids::integer[], ALTER COLUMN library_ids SET DEFAULT '{}'::integer[],
  ALTER COLUMN line TYPE text COLLATE "C", ALTER COLUMN search_text TYPE text COLLATE "C";""")
res['analyze_after_ms'] = timed('ANALYZE aisearch.sb_after;')
res['after changeset'] = {'size': size('aisearch.sb_after'), 'q': {k: time_query(v) for k, v in queries('aisearch.sb_after', 'integer').items()}}
res['import_replace_ms'] = timed('BEGIN; CREATE TEMP TABLE keep AS SELECT * FROM aisearch.sb_after; DELETE FROM aisearch.sb_after; '
                                 'INSERT INTO aisearch.sb_after SELECT * FROM keep; COMMIT;')
res['after import (no compaction)'] = {'size': size('aisearch.sb_after')}
res['vacuum_full_analyze_ms'] = timed('VACUUM (FULL, ANALYZE) aisearch.sb_after;')
res['after import + compact'] = {'size': size('aisearch.sb_after'), 'q': {k: time_query(v) for k, v in queries('aisearch.sb_after', 'integer').items()}}
psql('DROP TABLE aisearch.sb_after;')
json.dump(res, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print(json.dumps(res, ensure_ascii=False, indent=1))
