ALTER TABLE junction ADD CONSTRAINT fk_junction_node_id FOREIGN KEY (node_id) REFERENCES node(id);
