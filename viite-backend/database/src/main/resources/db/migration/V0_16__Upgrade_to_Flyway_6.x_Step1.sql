-- Flyway versions 6.0.0 and up require "flyway_schema_history" as the name of the migration data table
-- instead of old "schema_version".
-- In step 1 we
-- - create the new flyway_schema_history table, and
-- - make sure it has the same contents than the old one, including data about this migration.
-- (Just doing an ALTER NAME is not applicable (tried: ended in an error because the old named table does not exist
-- anymore after renaming, but Flyway tries to write there), so make a copy instead, and remove the old table at later time in another migration).
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables WHERE table_name = 'schema_version'
  )
  THEN
    CREATE TABLE IF NOT EXISTS flyway_schema_history AS SELECT * FROM schema_version;
  END IF;
END $$;


CREATE OR REPLACE FUNCTION flyway_schema_history_table_copy_inserted_row()
RETURNS TRIGGER AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM flyway_schema_history
    WHERE version = NEW.version
  ) THEN
  INSERT INTO flyway_schema_history(installed_rank,"version",description,"type",script,checksum,installed_by,installed_on,execution_time,success)
  VALUES (NEW.installed_rank,NEW.version,NEW.description,NEW.type,NEW.script,NEW.checksum,NEW.installed_by,NEW.installed_on,NEW.execution_time,NEW.success);
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE 'plpgsql';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'flyway_schema_table_sync')
    THEN CREATE TRIGGER                FLYWAY_SCHEMA_TABLE_SYNC
         AFTER INSERT                  ON schema_version
         FOR EACH ROW EXECUTE FUNCTION flyway_schema_history_table_copy_inserted_row();
    END IF;
END $$;
