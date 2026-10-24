--liquibase formatted sql

-- cycle 24 (plan ADR-0068): daily_usage.updated_at is written by the database on every UPDATE, never by application
-- code. The function lives in the module's own schema. The trigger is dropped and created again so this file can be
-- edited and applied again (runOnChange).

--changeset chaekcheck:aisearch-002-set-updated-at-function splitStatements:false runOnChange:true
CREATE OR REPLACE FUNCTION aisearch.set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END
$$;

--changeset chaekcheck:aisearch-002-set-updated-at-trigger runOnChange:true
DROP TRIGGER IF EXISTS daily_usage_set_updated_at ON aisearch.daily_usage;
CREATE TRIGGER daily_usage_set_updated_at BEFORE UPDATE ON aisearch.daily_usage FOR EACH ROW EXECUTE FUNCTION aisearch.set_updated_at();
