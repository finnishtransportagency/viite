-- Add ADMINISTRATIVE_CLASS column to ROADWAY table.
ALTER TABLE "ROADWAY"
    ADD (ADMINISTRATIVE_CLASS NUMBER(2,0));
COMMENT ON COLUMN ROADWAY.ADMINISTRATIVE_CLASS IS 'Tells who is responsible of the roadkeeping. Allowed values: 1=Valtio, 2=Kunta, 3=Yksityinen.  Replaces functionality of the old ROAD_TYPE since 2021-02.';

-- We may not have NULL values. Set ADMINISTRATIVE_CLASS column values based on old ROAD_TYPE column values.
UPDATE "ROADWAY"
SET ADMINISTRATIVE_CLASS = 1 -- Valtio
WHERE ROAD_TYPE IN (1,2,4); -- maantie (1), lauttaväylä maantiellä (2), maantien työmaa (4)

UPDATE "ROADWAY"
SET ADMINISTRATIVE_CLASS = 2 -- Kunta
WHERE ROAD_TYPE IN (3); -- kunnan katuosuus (3)

UPDATE "ROADWAY"
SET ADMINISTRATIVE_CLASS = 3 -- Yksityinen
WHERE ROAD_TYPE IN (5,9,99); -- yksityistie (5), omistaja selvittämättä (9), ei määritelty (99)

-- set NOT NULL constraint
ALTER TABLE "ROADWAY" MODIFY (ADMINISTRATIVE_CLASS NUMBER(2,0) NOT NULL);

-- Update ROAD_TYPE values to be inline with new logic
UPDATE "ROADWAY"
SET ROAD_TYPE = 1 -- Maantie
WHERE ROAD_TYPE IN (2,4); -- Lauttaväylä maantiellä (2), Maantien työmaa (4)

UPDATE "ROADWAY"
SET ROAD_TYPE = 5 -- Yksityistie
WHERE ROAD_TYPE IN (9,99); -- Omistaja selvittämättä (9), Ei määritelty (99)

-- Add OLD_ADMINISTRATIVE_CLASS column to ROADWAY_CHANGES table
ALTER TABLE "ROADWAY_CHANGES"
    ADD (OLD_ADMINISTRATIVE_CLASS NUMBER(2,0));
COMMENT ON COLUMN ROADWAY_CHANGES.OLD_ADMINISTRATIVE_CLASS IS 'Tells who is responsible of the roadkeeping. Allowed values: 1=Valtio, 2=Kunta, 3=Yksityinen.  Replaces functionality of the old ROAD_TYPE since 2021-02.';

-- We may not have NULL values. Set OLD_ADMINISTRATIVE_CLASS column values based on old OLD_ROAD_TYPE column values.
UPDATE "ROADWAY_CHANGES"
SET OLD_ADMINISTRATIVE_CLASS = 1 -- Valtio
WHERE OLD_ROAD_TYPE IN (1,2,4); -- maantie (1), lauttaväylä maantiellä (2), maantien työmaa (4)

UPDATE "ROADWAY_CHANGES"
SET OLD_ADMINISTRATIVE_CLASS = 2 -- Kunta
WHERE OLD_ROAD_TYPE IN (3); -- kunnan katuosuus (3)

UPDATE "ROADWAY_CHANGES"
SET OLD_ADMINISTRATIVE_CLASS = 3 -- Yksityinen
WHERE OLD_ROAD_TYPE IN (5,9,99); -- yksityistie (5), omistaja selvittämättä (9), ei määritelty (99)

-- Add NEW_ADMINISTRATIVE_CLASS column to ROADWAY_CHANGES table
ALTER TABLE "ROADWAY_CHANGES"
    ADD (NEW_ADMINISTRATIVE_CLASS NUMBER(2,0));
COMMENT ON COLUMN ROADWAY_CHANGES.NEW_ADMINISTRATIVE_CLASS IS 'Tells who is responsible of the roadkeeping. Allowed values: 1=Valtio, 2=Kunta, 3=Yksityinen.  Replaces functionality of the old ROAD_TYPE since 2021-02.';

-- We may not have NULL values. Set OLD_ADMINISTRATIVE_TYPE column values based on old OLD_ROAD_TYPE column values.
UPDATE "ROADWAY_CHANGES"
SET NEW_ADMINISTRATIVE_CLASS = 1 -- Valtio
WHERE NEW_ROAD_TYPE IN (1,2,4); -- maantie (1), lauttaväylä maantiellä (2), maantien työmaa (4)

UPDATE "ROADWAY_CHANGES"
SET NEW_ADMINISTRATIVE_CLASS = 2 -- Kunta
WHERE NEW_ROAD_TYPE IN (3); -- kunnan katuosuus (3)

UPDATE "ROADWAY_CHANGES"
SET NEW_ADMINISTRATIVE_CLASS = 3 -- Yksityinen
WHERE NEW_ROAD_TYPE IN (5,9,99); -- yksityistie (5), omistaja selvittämättä (9), ei määritelty (99)

-- set NOT NULL constraint
ALTER TABLE "ROADWAY_CHANGES" MODIFY (NEW_ADMINISTRATIVE_CLASS NUMBER(2,0) NOT NULL);
