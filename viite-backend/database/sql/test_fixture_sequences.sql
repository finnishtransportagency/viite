
drop sequence if exists ROADWAY_SEQ;
create sequence ROADWAY_SEQ
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists LINEAR_LOCATION_SEQ;
create sequence LINEAR_LOCATION_SEQ
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists viite_project_seq;
create sequence viite_project_seq
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists project_link_seq;
create sequence project_link_seq
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists project_link_name_seq;
create sequence project_link_name_seq
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists PROJECT_CAL_POINT_ID_SEQ;
create sequence PROJECT_CAL_POINT_ID_SEQ
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists viite_general_seq;
create sequence viite_general_seq
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists ROADWAY_NUMBER_SEQ;
create sequence ROADWAY_NUMBER_SEQ
  minvalue 1
  no maxvalue
  start with 1010000
  increment by 1
  cache 100
  cycle;

drop sequence if exists ROAD_NETWORK_ERROR_SEQ;
create sequence ROAD_NETWORK_ERROR_SEQ
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists ROADWAY_CHANGE_LINK;
create sequence ROADWAY_CHANGE_LINK
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists PUBLISHED_ROAD_NETWORK_SEQ;
create sequence PUBLISHED_ROAD_NETWORK_SEQ
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists ROADWAY_POINT_SEQ;
create sequence ROADWAY_POINT_SEQ
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists CALIBRATION_POINT_SEQ;
create sequence CALIBRATION_POINT_SEQ
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists NODE_SEQ;
create sequence NODE_SEQ
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists NODE_NUMBER_SEQ;
create sequence NODE_NUMBER_SEQ
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists NODE_POINT_SEQ;
create sequence NODE_POINT_SEQ
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists JUNCTION_SEQ;
create sequence JUNCTION_SEQ
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists JUNCTION_POINT_SEQ;
create sequence JUNCTION_POINT_SEQ
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists ROAD_NAME_SEQ;
create sequence ROAD_NAME_SEQ
  minvalue 1
  no maxvalue
  start with 1000000
  increment by 1
  cache 100
  cycle;

drop sequence if exists SERVICE_USER_SEQ;
create sequence SERVICE_USER_SEQ
  minvalue 1
  no maxvalue
  start with 100
  increment by 1
  cache 10
  cycle;
