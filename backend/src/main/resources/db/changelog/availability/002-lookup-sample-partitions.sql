--liquibase formatted sql

-- Partition management for availability.lookup_sample (user decision 2026-10-19). One partition per month, bounds at
-- Asia/Seoul month starts (Korea has no daylight saving time, so every bound is 15:00 UTC of the previous day):
-- lookup_sample_y2026m10 holds 2026-10-01 00:00+09 <= sampled_at < 2026-11-01 00:00+09. Called by OpsSampler at
-- startup and once a day; also callable by hand. Rows that landed in the DEFAULT partition because their month was
-- missing are moved into the new month partition when it is made.

--changeset chaekcheck:availability-002-lookup-sample-ensure-partitions splitStatements:false runOnChange:true
CREATE OR REPLACE FUNCTION availability.lookup_sample_ensure_partitions(months_ahead integer) RETURNS integer
LANGUAGE plpgsql AS $$
DECLARE
  this_month date := date_trunc('month', now() AT TIME ZONE 'Asia/Seoul')::date;
  month_start date;
  lower_bound timestamptz;
  upper_bound timestamptz;
  part text;
  created integer := 0;
BEGIN
  IF months_ahead IS NULL OR months_ahead < 0 OR months_ahead > 24 THEN
    RAISE EXCEPTION 'months_ahead must be 0..24: %', months_ahead;
  END IF;
  FOR i IN 0..months_ahead LOOP
    month_start := (this_month + make_interval(months => i))::date;
    part := 'lookup_sample_y' || to_char(month_start, 'YYYY') || 'm' || to_char(month_start, 'MM');
    CONTINUE WHEN to_regclass('availability.' || part) IS NOT NULL;
    lower_bound := month_start::timestamp AT TIME ZONE 'Asia/Seoul';
    upper_bound := (month_start + interval '1 month')::timestamp AT TIME ZONE 'Asia/Seoul';
    -- made apart and attached, so rows of that month already in the DEFAULT partition can be moved in first
    EXECUTE format('CREATE TABLE availability.%I (LIKE availability.lookup_sample INCLUDING DEFAULTS)', part);
    EXECUTE format('WITH moved AS (DELETE FROM availability.lookup_sample_default'
        || ' WHERE sampled_at >= %L AND sampled_at < %L RETURNING *) INSERT INTO availability.%I SELECT * FROM moved',
        lower_bound, upper_bound, part);
    EXECUTE format('ALTER TABLE availability.lookup_sample ATTACH PARTITION availability.%I FOR VALUES FROM (%L) TO (%L)',
        part, lower_bound, upper_bound);
    created := created + 1;
  END LOOP;
  RETURN created;
END
$$;

--changeset chaekcheck:availability-002-lookup-sample-drop-before splitStatements:false runOnChange:true
-- Drops every month partition that ends at or before cutoff (whole partitions only: a month that is partly newer
-- than cutoff stays), and deletes DEFAULT partition rows older than cutoff. Returns the partitions dropped.
CREATE OR REPLACE FUNCTION availability.lookup_sample_drop_before(cutoff timestamptz) RETURNS integer
LANGUAGE plpgsql AS $$
DECLARE
  part record;
  upper_bound timestamptz;
  dropped integer := 0;
BEGIN
  IF cutoff IS NULL THEN
    RAISE EXCEPTION 'cutoff is required';
  END IF;
  FOR part IN
    SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
    WHERE i.inhparent = 'availability.lookup_sample'::regclass AND c.relname ~ '^lookup_sample_y[0-9]{4}m[0-9]{2}$'
    ORDER BY c.relname
  LOOP
    upper_bound := (to_date(substring(part.relname FROM 16 FOR 4) || substring(part.relname FROM 21 FOR 2) || '01',
        'YYYYMMDD') + interval '1 month')::timestamp AT TIME ZONE 'Asia/Seoul';
    IF upper_bound <= cutoff THEN
      EXECUTE format('DROP TABLE availability.%I', part.relname);
      dropped := dropped + 1;
    END IF;
  END LOOP;
  DELETE FROM availability.lookup_sample_default WHERE sampled_at < cutoff;
  RETURN dropped;
END
$$;
