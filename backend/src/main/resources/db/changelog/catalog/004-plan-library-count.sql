--liquibase formatted sql

-- 2026-10-29 (cycle 26, after v1.20.1): how many of a book's libraries are among the user's plan libraries, for the AI
-- search keyword query (catalog.internal.texts.JdbcAiSearchIndex). The count is intarray's sorted-array intersection
-- (catalog/003) instead of unnest(library_ids) + = ANY(plan) per element. PL/pgSQL with COST 1000 on purpose: a
-- cheap-looking expression makes the planner count every matched row in one serial scan; an expensive one keeps the
-- parallel plan that sorts by score first and counts only the rows the LIMIT needs (measured in
-- docs/cycle26-book-descriptions/4-keyword-query-speed.md, form V7).

--changeset chaekcheck:catalog-004-plan-library-count splitStatements:false runOnChange:true
CREATE OR REPLACE FUNCTION catalog.plan_library_count(library_ids integer[], plan integer[]) RETURNS integer
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE COST 1000 AS $$
BEGIN
  RETURN intarray.icount(intarray._int_inter(library_ids, plan));
END
$$;
