alter table roadway drop constraint roadway_history_uk;

create unique index roadway_history_i on roadway
  (road_number, road_part_number, start_addr_m, end_addr_m, track, discontinuity,
   start_date, coalesce(end_date, '1900-01-01'), valid_from, coalesce(valid_to, '1900-01-01'), ely, road_type, terminated);

alter table linear_location drop constraint linear_location_uk;

create unique index linear_location_i on linear_location
  (roadway_number, order_number, link_id, start_measure, end_measure, coalesce(valid_to, '1900-01-01'), side);
