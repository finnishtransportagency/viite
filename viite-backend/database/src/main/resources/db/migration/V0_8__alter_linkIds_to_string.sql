-- Change linkId type from BigInt to VarChar for KGV ids - VIITE-2783.
ALTER TABLE CALIBRATION_POINT DROP constraint cp_link_fk;
ALTER TABLE LINEAR_LOCATION DROP constraint LINEAR_LOCATION_LINK_FK;

ALTER TABLE CALIBRATION_POINT ALTER COLUMN LINK_ID TYPE VARCHAR;
ALTER TABLE LINK ALTER COLUMN ID TYPE VARCHAR;
ALTER TABLE LINEAR_LOCATION ALTER COLUMN LINK_ID TYPE VARCHAR;
ALTER TABLE PROJECT_LINK_HISTORY ALTER COLUMN LINK_ID TYPE VARCHAR, ALTER COLUMN CONNECTED_LINK_ID TYPE VARCHAR;
ALTER TABLE PROJECT_LINK ALTER COLUMN LINK_ID TYPE VARCHAR, ALTER COLUMN CONNECTED_LINK_ID TYPE VARCHAR;
ALTER TABLE COMPLEMENTARY_FILTER ALTER COLUMN LINK_ID TYPE VARCHAR;
ALTER TABLE UNADDRESSED_ROAD_LINK ALTER COLUMN LINK_ID TYPE VARCHAR;

ALTER TABLE CALIBRATION_POINT add CONSTRAINT CP_LINK_FK FOREIGN KEY (LINK_ID) REFERENCES LINK (ID);
ALTER TABLE LINEAR_LOCATION add CONSTRAINT LINEAR_LOCATION_LINK_FK FOREIGN KEY (LINK_ID) REFERENCES LINK (ID);
