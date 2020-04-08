
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

drop sequence viite_project_seq;
create sequence viite_project_seq
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence project_link_seq;
create sequence project_link_seq
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence project_link_name_seq;
create sequence project_link_name_seq
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence PROJECT_CAL_POINT_ID_SEQ;
create sequence PROJECT_CAL_POINT_ID_SEQ
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence viite_general_seq;
create sequence viite_general_seq
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence ROADWAY_NUMBER_SEQ;
create sequence ROADWAY_NUMBER_SEQ
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence ROAD_NETWORK_ERROR_SEQ;
create sequence ROAD_NETWORK_ERROR_SEQ
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence ROADWAY_CHANGE_LINK;
create sequence ROADWAY_CHANGE_LINK
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence PUBLISHED_ROAD_NETWORK_SEQ;
create sequence PUBLISHED_ROAD_NETWORK_SEQ
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence ROADWAY_POINT_SEQ;
create sequence ROADWAY_POINT_SEQ
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence CALIBRATION_POINT_SEQ;
create sequence CALIBRATION_POINT_SEQ
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence NODE_SEQ;
create sequence NODE_SEQ
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence NODE_NUMBER_SEQ;
create sequence NODE_NUMBER_SEQ
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence NODE_POINT_SEQ;
create sequence NODE_POINT_SEQ
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence JUNCTION_SEQ;
create sequence JUNCTION_SEQ
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence JUNCTION_POINT_SEQ;
create sequence JUNCTION_POINT_SEQ
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence ROAD_NAME_SEQ;
create sequence ROAD_NAME_SEQ
  minvalue 1
  maxvalue 999999999999999999999999999
  start with 1000000
  increment by 1
  cache 100
  cycle;
