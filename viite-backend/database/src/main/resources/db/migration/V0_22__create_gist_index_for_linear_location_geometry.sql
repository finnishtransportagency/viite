-- Create GIST index for linear_location geometry column to speed up spatial queries

CREATE INDEX IF NOT EXISTS linear_location_geometry_i ON linear_location USING gist (geometry);