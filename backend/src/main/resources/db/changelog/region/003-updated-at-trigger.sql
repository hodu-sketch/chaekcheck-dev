--liquibase formatted sql

-- cycle 08 (plan ADR-0068): updated_at is written by the database on every UPDATE, never by application code.
-- The function lives in the module's own schema. The trigger is dropped and created again so this file can be
-- edited and applied again (runOnChange).

--changeset chaekcheck:region-003-set-updated-at-function splitStatements:false runOnChange:true
CREATE OR REPLACE FUNCTION region.set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END
$$;

--changeset chaekcheck:region-003-set-updated-at-trigger runOnChange:true
DROP TRIGGER IF EXISTS region_set_updated_at ON region.region;
CREATE TRIGGER region_set_updated_at BEFORE UPDATE ON region.region FOR EACH ROW EXECUTE FUNCTION region.set_updated_at();
