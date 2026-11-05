--liquibase formatted sql

-- 2026-10-30 (user "두 층 (조각 512) + BM25 이걸로 개발해"): a book's libraries as words ("l3 l17 l42"), the generated
-- column catalog.book_search.library_words (catalog/006) that the BM25 index holds, so the plan-library filter runs
-- inside pg_search: 7 ms against 125 ms for integer[] && outside the index at 100,000 rows
-- (docs/features/aisearch/reports/2026-10-30-vector-scaling.md 10). pg_search does not take a function in its index
-- expressions ("cache lookup failed for type 0"), and array_to_string alone is not immutable, hence the column and
-- this function. A change here does not redo the stored column: rewrite it with UPDATE ... SET library_ids = library_ids.

--changeset chaekcheck:catalog-005-library-words splitStatements:false runOnChange:true
CREATE OR REPLACE FUNCTION catalog.library_words(library_ids integer[]) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT coalesce(array_to_string(ARRAY(SELECT 'l' || id FROM unnest(library_ids) AS id), ' '), '')
$$;
