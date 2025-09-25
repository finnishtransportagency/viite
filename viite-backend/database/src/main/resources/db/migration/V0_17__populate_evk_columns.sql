-- This script populates the road_maintainer column in all relevant tables,
-- use after creating the road_maintainer column

UPDATE roadway SET road_maintainer = 'EVK0';
UPDATE project_link SET road_maintainer = 'EVK0';
UPDATE project SET road_maintainers = ARRAY_APPEND(road_maintainers, 'EVK0');
UPDATE project_link_history SET road_maintainer = 'EVK0';
UPDATE municipality SET road_maintainer = 'EVK0';
UPDATE roadway_changes SET new_road_maintainer = 'EVK0';
UPDATE roadway_changes SET old_road_maintainer = 'EVK0';
