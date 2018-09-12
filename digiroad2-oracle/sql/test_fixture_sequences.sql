drop sequence ROAD_ADDRESS_SEQ;
create sequence ROAD_ADDRESS_SEQ
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
