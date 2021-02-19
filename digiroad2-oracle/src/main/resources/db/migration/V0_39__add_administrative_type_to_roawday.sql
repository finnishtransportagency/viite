-- Add ADMINISTRATIVE_TYPE column to ROADWAY table.
ALTER TABLE "ROADWAY"
    ADD (ADMINISTRATIVE_TYPE NUMBER(2,0));
COMMENT ON COLUMN ROADWAY.ADMINISTRATIVE_TYPE IS 'Tells who is responsible of the roadkeeping. Allowed values: 1=Kunta, 2=Valtio, 3=Yksityinen.  Replaces functionality of the old ROAD_TYPE since 2021-02.';

-- We may not have NULL values. Set ADMINISTRATIVE_TYPE column values based on old ROAD_TYPE column values.
UPDATE "ROADWAY"
SET ADMINISTRATIVE_TYPE = 1 -- Kunta
WHERE ROAD_TYPE IN (3); -- kunnan katuosuus (3)

UPDATE "ROADWAY"
SET ADMINISTRATIVE_TYPE = 2 -- Valtio
WHERE ROAD_TYPE IN (1,2,4); -- maantie (1), lauttaväylä maantiellä (2), maantien työmaa (4)

UPDATE "ROADWAY"
SET ADMINISTRATIVE_TYPE = 3 -- Yksityinen
WHERE ROAD_TYPE IN (5,9,99); -- yksityistie (5), omistaja selvittämättä (9), ei määritelty (99)

-- Last, set NOT NULL constraint
ALTER TABLE "ROADWAY" MODIFY (ADMINISTRATIVE_TYPE NUMBER(2,0) NOT NULL);
