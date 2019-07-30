drop sequence ROADWAY_SEQ;
create sequence ROADWAY_SEQ
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence LINEAR_LOCATION_SEQ;
create sequence LINEAR_LOCATION_SEQ
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence LINK_ID_SEQ;
create sequence LINK_ID_SEQ
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 20000000
  increment by 1
  cache 100
  cycle;
