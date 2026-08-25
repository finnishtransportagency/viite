-- Cache of Velho ("Luokitusrekisteri") object geometries, refreshed once a day by an external job.
-- One row per Velho object (oid); geometry is a GeoJSON GeometryCollection reprojected to EPSG:4326.
-- Velho geometries include elevation (Z), hence GeometryCollectionZ instead of plain GeometryCollection.
CREATE TABLE velho_geometry_cache (
  id bigserial PRIMARY KEY,
  oid varchar NOT NULL,
  target_class varchar NOT NULL,
  namespace varchar NOT NULL,
  geometry geometry(GeometryCollectionZ, 4326) NOT NULL,
  fetched_time timestamp NOT NULL DEFAULT now()
);
CREATE INDEX velho_geometry_cache_geometry_i ON velho_geometry_cache USING gist (geometry);
CREATE INDEX velho_geometry_cache_target_class_i ON velho_geometry_cache USING btree (target_class);