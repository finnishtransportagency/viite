-- Recreate previously existing constraints & indexes (idempotent patch)
-- Uses guards against missing relations and already-existing objects.
-- Constraints
DO $$
BEGIN
  -- node_point_pk
  IF to_regclass('node_point') IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='node_point_pk') THEN
    ALTER TABLE ONLY node_point
      ADD CONSTRAINT node_point_pk PRIMARY KEY (id);
  END IF;

  -- roadway_pk
  IF to_regclass('roadway') IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='roadway_pk') THEN
    ALTER TABLE ONLY roadway
      ADD CONSTRAINT roadway_pk PRIMARY KEY (id);
  END IF;

  -- roadway_point_pk
  IF to_regclass('roadway_point') IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='roadway_point_pk') THEN
    ALTER TABLE ONLY roadway_point
      ADD CONSTRAINT roadway_point_pk PRIMARY KEY (id);
  END IF;

  -- roadway_point_uk1
  IF to_regclass('roadway_point') IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='roadway_point_uk1') THEN
    ALTER TABLE ONLY roadway_point
      ADD CONSTRAINT roadway_point_uk1
      UNIQUE (roadway_number, addr_m)
      DEFERRABLE INITIALLY DEFERRED;
  END IF;

  -- cp_roadway_point_fk (create NOT VALID -> validate)
  IF to_regclass('calibration_point') IS NOT NULL
     AND to_regclass('roadway_point') IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='cp_roadway_point_fk') THEN
    ALTER TABLE ONLY calibration_point
      ADD CONSTRAINT cp_roadway_point_fk
      FOREIGN KEY (roadway_point_id) REFERENCES roadway_point(id)
      NOT VALID;
    ALTER TABLE ONLY calibration_point
      VALIDATE CONSTRAINT cp_roadway_point_fk;
  END IF;

  -- fk_jp_roadway_point_id
  IF to_regclass('junction_point') IS NOT NULL
     AND to_regclass('roadway_point') IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_jp_roadway_point_id') THEN
    ALTER TABLE ONLY junction_point
      ADD CONSTRAINT fk_jp_roadway_point_id
      FOREIGN KEY (roadway_point_id) REFERENCES roadway_point(id)
      NOT VALID;
    ALTER TABLE ONLY junction_point
      VALIDATE CONSTRAINT fk_jp_roadway_point_id;
  END IF;

  -- fk_np_roadway_point_id
  IF to_regclass('node_point') IS NOT NULL
     AND to_regclass('roadway_point') IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_np_roadway_point_id') THEN
    ALTER TABLE ONLY node_point
      ADD CONSTRAINT fk_np_roadway_point_id
      FOREIGN KEY (roadway_point_id) REFERENCES roadway_point(id)
      NOT VALID;
    ALTER TABLE ONLY node_point
      VALIDATE CONSTRAINT fk_np_roadway_point_id;
  END IF;

  -- junction_point_fk1
  IF to_regclass('junction_point') IS NOT NULL
     AND to_regclass('junction') IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='junction_point_fk1') THEN
    ALTER TABLE ONLY junction_point
      ADD CONSTRAINT junction_point_fk1
      FOREIGN KEY (junction_id) REFERENCES junction(id)
      NOT VALID;
    ALTER TABLE ONLY junction_point
      VALIDATE CONSTRAINT junction_point_fk1;
  END IF;

  -- project_link_roadway_fk
  IF to_regclass('project_link') IS NOT NULL
     AND to_regclass('roadway') IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='project_link_roadway_fk') THEN
    ALTER TABLE ONLY project_link
      ADD CONSTRAINT project_link_roadway_fk
      FOREIGN KEY (roadway_id) REFERENCES roadway(id)
      NOT VALID;
    ALTER TABLE ONLY project_link
      VALIDATE CONSTRAINT project_link_roadway_fk;
  END IF;

END $$;

-- Indexes
-- complementary_data_link_id_i
CREATE INDEX IF NOT EXISTS complementary_data_link_id_i
  ON complementary_link_table USING btree (id);

-- complementary_data_geometry_i
CREATE INDEX IF NOT EXISTS complementary_data_geometry_i
  ON complementary_link_table USING gist (geometry);

-- Unique index
CREATE UNIQUE INDEX IF NOT EXISTS roadway_history_i
  ON roadway USING btree (
    road_number,
    road_part_number,
    start_addr_m,
    end_addr_m,
    track,
    discontinuity,
    start_date,
    COALESCE(end_date, '1900-01-01'::date),
    valid_from,
    COALESCE(valid_to, '1900-01-01 00:00:00'::timestamp without time zone),
    ely,
    administrative_class,
    terminated
  );