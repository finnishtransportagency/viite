-- Add ADMINISTRATIVE_CLASS column to ROADWAY table.
ALTER TABLE "ROADWAY" ADD (ADMINISTRATIVE_CLASS NUMBER(2,0));

COMMENT ON COLUMN ROADWAY.ADMINISTRATIVE_CLASS
 IS 'Tells who is responsible of the roadkeeping. Allowed values: 1=Valtio, 2=Kunta, 3=Yksityinen.  Replaces functionality of the ROAD_TYPE since 2021-02.';

-- We may not have NULL values. Set ADMINISTRATIVE_CLASS column values based on ROAD_TYPE column values.
-- ADMINISTRATIVE_CLASS     ROAD_TYPE
-- (1) Valtio,              Maantie (1), Lauttaväylä maantiellä (2), Maantien työmaa (4)
-- (2) Kunta,               Kunnan katuosuus (3)
-- (3) Yksityinen,          Yksityistie (5), Omistaja selvittämättä (9), Ei määritelty (99)
UPDATE ROADWAY SET ADMINISTRATIVE_CLASS = 1 WHERE ROAD_TYPE IN (1,2,4);
UPDATE ROADWAY SET ADMINISTRATIVE_CLASS = 2 WHERE ROAD_TYPE IN (3);
UPDATE ROADWAY SET ADMINISTRATIVE_CLASS = 3 WHERE ROAD_TYPE IN (5,9,99);

-- set NOT NULL constraint
ALTER TABLE "ROADWAY" MODIFY (ADMINISTRATIVE_CLASS NUMBER(2,0) NOT NULL);

-- Update ROAD_TYPE values to be inline with new logic
-- (1) Maantie
-- (2) Lauttaväylä
-- (3) Kunnan katuosuus
-- (4) Maantien työmaa
-- (5) Yksityistie
-- (9) Omistaja selvittämättä
-- (99) Ei määritelty
UPDATE ROADWAY SET ROAD_TYPE = 1 WHERE ROAD_TYPE IN (2,4);
UPDATE ROADWAY SET ROAD_TYPE = 5 WHERE ROAD_TYPE IN (9,99);

-- Add OLD_ADMINISTRATIVE_CLASS column to ROADWAY_CHANGES table
ALTER TABLE "ROADWAY_CHANGES" ADD (OLD_ADMINISTRATIVE_CLASS NUMBER(2,0));

COMMENT ON COLUMN ROADWAY_CHANGES.OLD_ADMINISTRATIVE_CLASS
 IS 'Tells who is responsible of the roadkeeping. Allowed values: 1=Valtio, 2=Kunta, 3=Yksityinen.  Replaces functionality of the ROAD_TYPE since 2021-02.';

-- We may not have NULL values. Set OLD_ADMINISTRATIVE_CLASS column values based on OLD_ROAD_TYPE column values.
-- OLD_ADMINISTRATIVE_CLASS     OLD_ROAD_TYPE
-- (1) Valtio,                  Maantie (1), Lauttaväylä maantiellä (2), Maantien työmaa (4)
-- (2) Kunta,                   Kunnan katuosuus (3)
-- (3) Yksityinen,              Yksityistie (5), Omistaja selvittämättä (9), Ei määritelty (99)
UPDATE ROADWAY_CHANGES SET OLD_ADMINISTRATIVE_CLASS = 1 WHERE OLD_ROAD_TYPE IN (1,2,4);
UPDATE ROADWAY_CHANGES SET OLD_ADMINISTRATIVE_CLASS = 2 WHERE OLD_ROAD_TYPE IN (3);
UPDATE ROADWAY_CHANGES SET OLD_ADMINISTRATIVE_CLASS = 3 WHERE OLD_ROAD_TYPE IN (5,9,99);

-- Add NEW_ADMINISTRATIVE_CLASS column to ROADWAY_CHANGES table
ALTER TABLE "ROADWAY_CHANGES" ADD (NEW_ADMINISTRATIVE_CLASS NUMBER(2,0));

COMMENT ON COLUMN ROADWAY_CHANGES.NEW_ADMINISTRATIVE_CLASS
 IS 'Tells who is responsible of the roadkeeping. Allowed values: 1=Valtio, 2=Kunta, 3=Yksityinen.  Replaces functionality of the ROAD_TYPE since 2021-02.';

-- We may not have NULL values. Set OLD_ADMINISTRATIVE_TYPE column values based on OLD_ROAD_TYPE column values.
-- NEW_ADMINISTRATIVE_CLASS     NEW_ROAD_TYPE
-- (1) Valtio,                  Maantie (1), Lauttaväylä maantiellä (2), Maantien työmaa (4)
-- (2) Kunta,                   Kunnan katuosuus (3)
-- (3) Yksityinen,              Yksityistie (5), Omistaja selvittämättä (9), Ei määritelty (99)
UPDATE ROADWAY_CHANGES SET NEW_ADMINISTRATIVE_CLASS = 1 WHERE NEW_ROAD_TYPE IN (1,2,4);
UPDATE ROADWAY_CHANGES SET NEW_ADMINISTRATIVE_CLASS = 2 WHERE NEW_ROAD_TYPE IN (3);
UPDATE ROADWAY_CHANGES SET NEW_ADMINISTRATIVE_CLASS = 3 WHERE NEW_ROAD_TYPE IN (5,9,99);

-- Update OLD_ROAD_TYPE values in ROADWAY_CHANGES table to be inline with new logic
UPDATE ROADWAY_CHANGES SET OLD_ROAD_TYPE = 1 WHERE OLD_ROAD_TYPE IN (2,4);
UPDATE ROADWAY_CHANGES SET OLD_ROAD_TYPE = 5 WHERE OLD_ROAD_TYPE IN (9,99);

-- Update NEW_ROAD_TYPE values in ROADWAY_CHANGES table to be inline with new logic
UPDATE ROADWAY_CHANGES SET NEW_ROAD_TYPE = 1 WHERE NEW_ROAD_TYPE IN (2,4);
UPDATE ROADWAY_CHANGES SET NEW_ROAD_TYPE = 5 WHERE NEW_ROAD_TYPE IN (9,99);

-- set NOT NULL constraint
ALTER TABLE "ROADWAY_CHANGES" MODIFY (NEW_ADMINISTRATIVE_CLASS NUMBER(2,0) NOT NULL);
