--liquibase formatted sql

-- cycle 08 (plan ADR-0068): updated_at is written by the database on every UPDATE, never by application code.
-- The function lives in the module's own schema. The trigger is dropped and created again so this file can be
-- edited and applied again (runOnChange).

--changeset chaekcheck:library-011-set-updated-at-function splitStatements:false runOnChange:true
CREATE OR REPLACE FUNCTION library.set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END
$$;

--changeset chaekcheck:library-011-set-updated-at-trigger runOnChange:true
DROP TRIGGER IF EXISTS library_set_updated_at ON library.library;
CREATE TRIGGER library_set_updated_at BEFORE UPDATE ON library.library FOR EACH ROW EXECUTE FUNCTION library.set_updated_at();
