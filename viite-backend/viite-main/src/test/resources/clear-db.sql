/*
  select 'drop sequence if exists ' || tablename || ' cascade;'
  from pg_tables
  where schemaname = 'public';

  select 'drop sequence if exists ' || sequencename || ' cascade;'
  from pg_sequences
  where schemaname = 'public';
*/
drop table if exists complementary_link_table cascade;
drop table if exists complementary_filter cascade;
drop table if exists export_lock cascade;
drop table if exists municipality cascade;
drop table if exists node cascade;
drop table if exists road_name cascade;
drop table if exists roadway_changes cascade;
drop table if exists roadway_point cascade;
drop table if exists service_user cascade;
drop table if exists unaddressed_road_link cascade;
drop table if exists calibration_point cascade;
drop table if exists link cascade;
drop table if exists junction cascade;
drop table if exists junction_point cascade;
drop table if exists linear_location cascade;
drop table if exists node_point cascade;
drop table if exists project cascade;
drop table if exists project_link_history cascade;
drop table if exists project_link_name cascade;
drop table if exists project_reserved_road_part cascade;
drop table if exists roadway cascade;
drop table if exists road_network_error cascade;
drop table if exists published_roadway cascade;
drop table if exists project_link cascade;
drop table if exists roadway_changes_link cascade;
drop table if exists project_calibration_point cascade;
drop sequence if exists junction_point_seq cascade;
drop sequence if exists node_point_seq cascade;
drop sequence if exists project_cal_point_id_seq cascade;
drop sequence if exists junction_seq cascade;
drop sequence if exists project_link_seq cascade;
drop sequence if exists node_seq cascade;
drop sequence if exists node_number_seq cascade;
drop sequence if exists road_name_seq cascade;
drop sequence if exists service_user_seq cascade;
drop sequence if exists viite_project_seq cascade;
drop sequence if exists project_link_name_seq cascade;
drop sequence if exists roadway_change_link cascade;
drop sequence if exists road_network_error_seq cascade;
drop sequence if exists linear_location_seq cascade;
drop sequence if exists calibration_point_seq cascade;
drop sequence if exists roadway_point_seq cascade;
drop sequence if exists roadway_seq cascade;
drop sequence if exists viite_general_seq cascade;
drop sequence if exists roadway_number_seq cascade;
