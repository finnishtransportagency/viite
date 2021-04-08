-- Rename ROAD_TYPE column to ADMINISTRATIVE_CLASS in ROADWAY table.
ALTER TABLE ROADWAY RENAME COLUMN ROAD_TYPE TO ADMINISTRATIVE_CLASS;

COMMENT ON COLUMN ROADWAY.ADMINISTRATIVE_CLASS
 IS 'Tells who is responsible of the roadkeeping. Allowed values: 1=Valtio, 2=Kunta, 3=Yksityinen.  Replaced functionality of the ROAD_TYPE since 2021-02.';

-- We may not have NULL values. Set ADMINISTRATIVE_CLASS column values based on newly renamed ADMINISTRATIVE_CLASS (previously ROAD_TYPE) column values.
-- Maantie (1), Lauttaväylä maantiellä (2), Maantien työmaa (4)     -> Valtio       (1)
-- Kunnan katuosuus (3)                                             -> Kunta        (2)
-- Yksityistie (5), Omistaja selvittämättä (9), Ei määritelty (99)  -> Yksityinen   (3)
UPDATE ROADWAY SET ADMINISTRATIVE_CLASS = 1 WHERE ADMINISTRATIVE_CLASS IN (1,2,4);
UPDATE ROADWAY SET ADMINISTRATIVE_CLASS = 2 WHERE ADMINISTRATIVE_CLASS IN (3);
UPDATE ROADWAY SET ADMINISTRATIVE_CLASS = 3 WHERE ADMINISTRATIVE_CLASS IN (5,9,99);

-- Rename OLD_ROAD_TYPE column to OLD_ADMINISTRATIVE_CLASS in ROADWAY_CHANGES table.
ALTER TABLE ROADWAY_CHANGES RENAME COLUMN OLD_ROAD_TYPE TO OLD_ADMINISTRATIVE_CLASS;

COMMENT ON COLUMN ROADWAY_CHANGES.OLD_ADMINISTRATIVE_CLASS
 IS 'Tells who is responsible of the roadkeeping. Allowed values: 1=Valtio, 2=Kunta, 3=Yksityinen.  Replaced functionality of the OLD_ROAD_TYPE since 2021-02.';

-- We may not have NULL values. Set OLD_ADMINISTRATIVE_CLASS column values based on newly renamed OLD_ADMINISTRATIVE_CLASS (previously OLD_ROAD_TYPE) column values.
-- Maantie (1), Lauttaväylä maantiellä (2), Maantien työmaa (4)     -> Valtio       (1)
-- Kunnan katuosuus (3)                                             -> Kunta        (2)
-- Yksityistie (5), Omistaja selvittämättä (9), Ei määritelty (99)  -> Yksityinen   (3)
UPDATE ROADWAY_CHANGES SET OLD_ADMINISTRATIVE_CLASS = 1 WHERE OLD_ADMINISTRATIVE_CLASS IN (1,2,4);
UPDATE ROADWAY_CHANGES SET OLD_ADMINISTRATIVE_CLASS = 2 WHERE OLD_ADMINISTRATIVE_CLASS IN (3);
UPDATE ROADWAY_CHANGES SET OLD_ADMINISTRATIVE_CLASS = 3 WHERE OLD_ADMINISTRATIVE_CLASS IN (5,9,99);

-- Rename NEW_ROAD_TYPE column to NEW_ADMINISTRATIVE_CLASS in ROADWAY_CHANGES table.
ALTER TABLE ROADWAY_CHANGES RENAME COLUMN NEW_ROAD_TYPE TO NEW_ADMINISTRATIVE_CLASS;

COMMENT ON COLUMN ROADWAY_CHANGES.NEW_ADMINISTRATIVE_CLASS
 IS 'Tells who is responsible of the roadkeeping. Allowed values: 1=Valtio, 2=Kunta, 3=Yksityinen.  Replaced functionality of the NEW_ROAD_TYPE since 2021-02.';

-- We may not have NULL values. Set NEW_ADMINISTRATIVE_CLASS column values based on newly renamed NEW_ADMINISTRATIVE_CLASS (previously NEW_ROAD_TYPE) column values.
-- Maantie (1), Lauttaväylä maantiellä (2), Maantien työmaa (4)     -> Valtio       (1)
-- Kunnan katuosuus (3)                                             -> Kunta        (2)
-- Yksityistie (5), Omistaja selvittämättä (9), Ei määritelty (99)  -> Yksityinen   (3)
UPDATE ROADWAY_CHANGES SET NEW_ADMINISTRATIVE_CLASS = 1 WHERE NEW_ADMINISTRATIVE_CLASS IN (1,2,4);
UPDATE ROADWAY_CHANGES SET NEW_ADMINISTRATIVE_CLASS = 2 WHERE NEW_ADMINISTRATIVE_CLASS IN (3);
UPDATE ROADWAY_CHANGES SET NEW_ADMINISTRATIVE_CLASS = 3 WHERE NEW_ADMINISTRATIVE_CLASS IN (5,9,99);

-- Rename ROAD_TYPE column to ADMINISTRATIVE_CLASS in PROJECT_LINK table.
ALTER TABLE PROJECT_LINK RENAME COLUMN ROAD_TYPE TO ADMINISTRATIVE_CLASS;

COMMENT ON COLUMN PROJECT_LINK.ADMINISTRATIVE_CLASS
 IS 'Tells who is responsible of the roadkeeping. Allowed values: 1=Valtio, 2=Kunta, 3=Yksityinen.  Replaced functionality of the ROAD_TYPE since 2021-02.';

-- We may not have NULL values. Set ADMINISTRATIVE_CLASS column values based on newly renamed ADMINISTRATIVE_CLASS (previously ROAD_TYPE) column values.
-- Maantie (1), Lauttaväylä maantiellä (2), Maantien työmaa (4)     -> Valtio       (1)
-- Kunnan katuosuus (3)                                             -> Kunta        (2)
-- Yksityistie (5), Omistaja selvittämättä (9), Ei määritelty (99)  -> Yksityinen   (3)
UPDATE PROJECT_LINK SET ADMINISTRATIVE_CLASS = 1 WHERE ADMINISTRATIVE_CLASS IN (1,2,4);
UPDATE PROJECT_LINK SET ADMINISTRATIVE_CLASS = 2 WHERE ADMINISTRATIVE_CLASS IN (3);
UPDATE PROJECT_LINK SET ADMINISTRATIVE_CLASS = 3 WHERE ADMINISTRATIVE_CLASS IN (5,9,99);

-- Rename ROAD_TYPE column to ADMINISTRATIVE_CLASS in PROJECT_LINK_HISTORY table.
ALTER TABLE PROJECT_LINK_HISTORY RENAME COLUMN ROAD_TYPE TO ADMINISTRATIVE_CLASS;

COMMENT ON COLUMN PROJECT_LINK_HISTORY.ADMINISTRATIVE_CLASS
 IS 'Tells who is responsible of the roadkeeping. Allowed values: 1=Valtio, 2=Kunta, 3=Yksityinen.  Replaced functionality of the ROAD_TYPE since 2021-02.';

-- We may not have NULL values. Set ADMINISTRATIVE_CLASS column values based on newly renamed ADMINISTRATIVE_CLASS (previously ROAD_TYPE) column values.
-- Maantie (1), Lauttaväylä maantiellä (2), Maantien työmaa (4)     -> Valtio       (1)
-- Kunnan katuosuus (3)                                             -> Kunta        (2)
-- Yksityistie (5), Omistaja selvittämättä (9), Ei määritelty (99)  -> Yksityinen   (3)
UPDATE PROJECT_LINK_HISTORY SET ADMINISTRATIVE_CLASS = 1 WHERE ADMINISTRATIVE_CLASS IN (1,2,4);
UPDATE PROJECT_LINK_HISTORY SET ADMINISTRATIVE_CLASS = 2 WHERE ADMINISTRATIVE_CLASS IN (3);
UPDATE PROJECT_LINK_HISTORY SET ADMINISTRATIVE_CLASS = 3 WHERE ADMINISTRATIVE_CLASS IN (5,9,99);
