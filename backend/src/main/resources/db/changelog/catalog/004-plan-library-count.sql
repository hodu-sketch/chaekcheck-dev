--liquibase formatted sql

-- 2026-10-29 (cycle 26, after v1.20.1): how many of a book's libraries are among the user's plan libraries, for the AI
-- search keyword query (catalog.internal.texts.JdbcAiSearchIndex). The count is intarray's sorted-array intersection
-- (catalog/003) instead of unnest(library_ids) + = ANY(plan) per element. PL/pgSQL with COST 1000: with intarray's
-- own cost (1) the planner counted every matched row in one serial scan; with COST 1000 it counts them in the parallel
-- workers (1,000, 3,000 and 10,000 measured, 1000 best). The query takes this function for one word or at most 500
-- plan libraries and the unnest SubPlan otherwise (docs/cycle26-book-descriptions/4-keyword-query-speed.md, form V8).

--changeset chaekcheck:catalog-004-plan-library-count splitStatements:false runOnChange:true
CREATE OR REPLACE FUNCTION catalog.plan_library_count(library_ids integer[], plan integer[]) RETURNS integer
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE COST 1000 AS $$
BEGIN
  RETURN intarray.icount(intarray._int_inter(library_ids, plan));
END
$$;
