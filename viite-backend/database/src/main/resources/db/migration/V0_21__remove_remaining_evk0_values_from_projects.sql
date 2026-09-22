-- Remove EVK0 from project.road_maintainers where present

UPDATE project p
SET road_maintainers = array_remove(p.road_maintainers, 'EVK0'::varchar)
WHERE 'EVK0'::varchar = ANY(p.road_maintainers);
