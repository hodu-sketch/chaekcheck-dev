"""Cycle 26 (2026-10-29): the AI search keyword query (catalog.AiSearchIndex.find) in four forms, timed on many cases.

Forms (all with the custom plan the server forces since v1.20.1):
  V0  now: score by LIKE again in SELECT, plan_count by a SubPlan unnest(library_ids) + = ANY(plan)
  V1  plan_count by intarray: intarray.icount(intarray._int_inter(library_ids, plan))
  V2  V1 + score by strpos(search_text, word) > 0
  V3  score by strpos only (no extension, plan_count as V0)
  V4  plan_count by intarray inside a scalar subquery (SELECT intarray.icount(...)): a SubPlan again, so the planner
      still counts only the rows the LIMIT needs after sorting by score (V1/V2 count every matched row)
  V5  V4 + score by strpos
  V6  plan_count by catalog.plan_library_count_sql(library_ids, plan): a SQL function around the intarray call with
      COST 1000 and a SET clause (so it is not inlined); score by strpos. Measuring only: create the function by hand
  V7  plan_count by catalog.plan_library_count (catalog/004, in production since v1.20.3): the same call in PL/pgSQL
      with COST 1000: the planner sees an expensive call, keeps the parallel plan and counts only the rows the LIMIT
      needs (lazy, as V0), each count cheap (as V1); score by strpos
  V8  the server since v1.20.4: V7 for one word or at most 500 plan libraries, else V3
Cases: words (one common, one rare, two, three, five, a core word), plan libraries (all, 1000, 200, 30). Each form runs RUNS
times per case after one warm-up; the median wall time (psql \\timing) and an md5 of the ordered result
(book_id:score:plan_count) are printed, so a form that changes the answer shows a different md5.

Usage: python -I scripts/aisearch/eval/bench_keyword_query.py [--no-intarray | --forms=V0,V7] <psql command ...>
  e.g.  wsl -d Ubuntu --exec docker compose -p chaekcheck-c26 exec -T db psql -U chaekcheck -d chaekcheck
The database needs schema intarray with the intarray extension for V1/V2 (catalog changeset 003); without it they
are skipped (--no-intarray).
"""
import re
import statistics
import subprocess
import sys

RUNS = 5
LIMIT = 30

CASES = [
    # (name, words, core words)
    ("one common", ["소설"], []),
    ("one rare", ["커피"], []),
    ("two", ["소설", "위로"], []),
    ("three", ["경제", "투자", "초보"], []),
    ("five", ["다이어트", "식단", "자세히", "다룬", "건강"], []),
    ("two + core", ["에세이", "여행"], ["여행"]),
]

PLANS = {
    "all": "SELECT array_agg(DISTINCT l ORDER BY l)::integer[] AS plan FROM catalog.book_search, unnest(library_ids) l",
    "1000": "SELECT (array_agg(DISTINCT l ORDER BY l))[1:1000]::integer[] AS plan FROM catalog.book_search, unnest(library_ids) l",
    "200": "SELECT (array_agg(DISTINCT l ORDER BY l))[1:200]::integer[] AS plan FROM catalog.book_search, unnest(library_ids) l",
    "30": "SELECT (array_agg(DISTINCT l ORDER BY l))[1:30]::integer[] AS plan FROM catalog.book_search, unnest(library_ids) l",
}


def lit(word):
    return "'" + word.replace("'", "''") + "'"


PLAN_SIZES = {"all": 10 ** 9, "1000": 1000, "200": 200, "30": 30}


def query(form, words, core, plan_name="all"):
    if form == "V8":
        form = "V7" if len(words) == 1 or PLAN_SIZES[plan_name] <= 500 else "V3"
    def has(w):
        if form in ("V2", "V3", "V5", "V6", "V7"):
            return f"(strpos(s.search_text, {lit(w)}) > 0)"
        return f"(s.search_text LIKE {lit('%' + w + '%')})"

    score = " + ".join(f"{has(w)}::int" for w in words)
    core_score = " + ".join(f"{has(w)}::int" for w in words if w in core) or "0"
    where = " OR ".join(f"s.search_text LIKE {lit('%' + w + '%')}" for w in words)
    if form in ("V1", "V2"):
        plan_count = "intarray.icount(intarray._int_inter(s.library_ids, :'plan'::integer[]))"
    elif form == "V6":
        plan_count = "catalog.plan_library_count_sql(s.library_ids, :'plan'::integer[])"
    elif form == "V7":
        plan_count = "catalog.plan_library_count(s.library_ids, :'plan'::integer[])"
    elif form in ("V4", "V5"):
        plan_count = "(SELECT intarray.icount(intarray._int_inter(s.library_ids, :'plan'::integer[])))"
    else:
        plan_count = "(SELECT count(*) FROM unnest(s.library_ids) AS l(id) WHERE l.id = ANY(:'plan'::integer[]))::int"
    inner = f"""SELECT book_id, score, plan_count FROM (
  SELECT s.book_id, {score} AS score, {plan_count} AS plan_count, {core_score} AS core_score
  FROM catalog.book_search s
  WHERE ({where}) AND s.library_ids && :'plan'::integer[] AND NOT (s.book_id = ANY('{{}}'::bigint[]))
) m ORDER BY core_score DESC, score DESC, plan_count DESC, book_id LIMIT {LIMIT}"""
    return f"SELECT md5(string_agg(book_id || ':' || score || ':' || plan_count, ',')) FROM ({inner}) r;"


def main(psql, forms):
    lines = ["\\pset tuples_only on", "\\pset format unaligned", "SET plan_cache_mode = force_custom_plan;"]
    keys = []
    for plan_name, plan_sql in PLANS.items():
        lines.append(f"{plan_sql} \\gset")
        for name, words, core in CASES:
            for form in forms:
                sql = query(form, words, core, plan_name)
                lines.append(sql)  # warm-up
                lines.append("\\timing on")
                for _ in range(RUNS):
                    lines.append(sql)
                lines.append("\\timing off")
                keys.append((plan_name, name, form))
    out = subprocess.run(psql, input="\n".join(lines) + "\n", capture_output=True, text=True, encoding="utf-8").stdout
    # per query: warm-up md5, then RUNS x (md5, Time)
    md5s = re.findall(r"^([0-9a-f]{32})$", out, re.M)
    times = [float(t) for t in re.findall(r"^Time: ([0-9.]+) ms", out, re.M)]
    per = RUNS + 1
    print("plan\tcase\tform\tmedian_ms\tmd5")
    base = {}
    for i, (plan_name, name, form) in enumerate(keys):
        t = statistics.median(times[i * RUNS:(i + 1) * RUNS])
        m = md5s[i * per]
        base.setdefault((plan_name, name), m)
        flag = "" if m == base[(plan_name, name)] else "  DIFFERENT"
        print(f"{plan_name}\t{name}\t{form}\t{t:.1f}\t{m[:8]}{flag}")


if __name__ == "__main__":
    args = sys.argv[1:]
    forms = ["V0", "V1", "V2", "V3", "V4", "V5", "V6", "V7"]
    if args and args[0] == "--no-intarray":
        forms = ["V0", "V3"]
        args = args[1:]
    if args and args[0].startswith("--forms="):
        forms = args[0][len("--forms="):].split(",")
        args = args[1:]
    if not args:
        sys.exit(__doc__)
    main(args, forms)
