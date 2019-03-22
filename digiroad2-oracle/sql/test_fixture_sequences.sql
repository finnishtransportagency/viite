drop sequence ROADWAY_SEQ;
create sequence ROADWAY_SEQ
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence LINEAR_LOCATION_SEQ;
create sequence LINEAR_LOCATION_SEQ
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;
