CREATE
OR REPLACE TRIGGER validToDate_validation BEFORE
UPDATE
   ON ROAD_ADDRESS FOR EACH ROW
   BEGIN
      if :new.start_date = :new.end_date
   THEN
      :NEW.valid_to := :NEW.valid_from ;
   END
   IF;
END
;

CREATE
OR REPLACE TRIGGER validToDate_validation2 BEFORE
INSERT
   ON ROAD_ADDRESS FOR EACH ROW
   BEGIN
      if :new.start_date = :new.end_date
   THEN
      :NEW.valid_to := :NEW.valid_from ;
   END
   IF;
END
;