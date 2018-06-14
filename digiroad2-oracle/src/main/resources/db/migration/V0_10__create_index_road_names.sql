create index
    road_names_current_idx
on
   road_names
   (valid_to asc, end_date asc, road_number asc)
;
