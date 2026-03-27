-- Drop old obsolete columns for ELY information.
-- Guard each table to keep migration idempotent across environments.

DO $$
BEGIN
  IF to_regclass('public.roadway') IS NOT NULL THEN
ALTER TABLE ROADWAY DROP COLUMN IF EXISTS ELY;
END IF;

  IF to_regclass('public.project_link') IS NOT NULL THEN
ALTER TABLE PROJECT_LINK DROP COLUMN IF EXISTS ELY;
END IF;

  IF to_regclass('public.project') IS NOT NULL THEN
ALTER TABLE PROJECT DROP COLUMN IF EXISTS ELYS;
END IF;

  IF to_regclass('public.project_link_history') IS NOT NULL THEN
ALTER TABLE PROJECT_LINK_HISTORY DROP COLUMN IF EXISTS ELY;
END IF;

  IF to_regclass('public.roadway_changes') IS NOT NULL THEN
ALTER TABLE ROADWAY_CHANGES DROP COLUMN IF EXISTS NEW_ELY,
DROP COLUMN IF EXISTS OLD_ELY;
END IF;
END $$;