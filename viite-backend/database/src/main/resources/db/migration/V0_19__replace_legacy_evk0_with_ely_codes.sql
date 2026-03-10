-- roadway
UPDATE roadway
SET road_maintainer = 'ELY' || ely::text
WHERE road_maintainer = 'EVK0';

-- project_link
UPDATE project_link
SET road_maintainer = 'ELY' || ely::text
WHERE road_maintainer = 'EVK0';

-- project_link_history
UPDATE project_link_history
SET road_maintainer = 'ELY' || ely::text
WHERE road_maintainer = 'EVK0';

-- municipality (skip road_maintainer_id=0, assumed Åland)
UPDATE municipality
SET road_maintainer = 'ELY' || road_maintainer_id::text
WHERE road_maintainer = 'EVK0'
  AND road_maintainer_id <> 0;

-- project:
-- Only projects whose road_maintainers is exactly {EVK0}
-- are rewritten based on elys array (e.g. {10,7} -> {ELY10,ELY7})
UPDATE project p
SET road_maintainers = (
  SELECT array_agg(('ELY' || e::text)::varchar(5) ORDER BY e) -- array_agg collects to list again
  FROM unnest(p.elys) AS e -- rows for db handling
  )
WHERE p.elys IS NOT NULL
  AND p.road_maintainers = ARRAY['EVK0']::varchar[]; -- tyyppipakotus