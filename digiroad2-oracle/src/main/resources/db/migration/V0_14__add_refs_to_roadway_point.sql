ALTER TABLE node_point ADD CONSTRAINT fk_np_roadway_point_id FOREIGN KEY (roadway_point_id) REFERENCES roadway_point(id);
ALTER TABLE junction_point ADD CONSTRAINT fk_jp_roadway_point_id FOREIGN KEY (roadway_point_id) REFERENCES roadway_point(id);
