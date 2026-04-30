-- Drop old obsolete columns for ELY information.
-- Guard each table to keep migration idempotent across environments.

DO $$
BEGIN
  IF to_regclass(format('%I.%I', current_schema(), 'roadway')) IS NOT NULL THEN
ALTER TABLE ROADWAY DROP COLUMN IF EXISTS ELY;
END IF;

  IF to_regclass(format('%I.%I', current_schema(), 'project_link')) IS NOT NULL THEN
ALTER TABLE PROJECT_LINK DROP COLUMN IF EXISTS ELY;
END IF;

  IF to_regclass(format('%I.%I', current_schema(), 'project')) IS NOT NULL THEN
ALTER TABLE PROJECT DROP COLUMN IF EXISTS ELYS;
END IF;

  IF to_regclass(format('%I.%I', current_schema(), 'project_link_history')) IS NOT NULL THEN
ALTER TABLE PROJECT_LINK_HISTORY DROP COLUMN IF EXISTS ELY;
END IF;

  IF to_regclass(format('%I.%I', current_schema(), 'roadway_changes')) IS NOT NULL THEN
ALTER TABLE ROADWAY_CHANGES DROP COLUMN IF EXISTS NEW_ELY,
DROP COLUMN IF EXISTS OLD_ELY;
END IF;
END $$;