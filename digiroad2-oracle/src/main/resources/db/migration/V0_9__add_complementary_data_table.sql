-- Add complementary data table to local data base - VIITE-2845.
CREATE TABLE public.complementary_data (
                                           id varchar NULL,
                                           datasource int4 NULL,
                                           adminclass int4 NULL,
                                           municipalitycode int4 NULL,
                                           featureclass int4 NULL,
                                           roadclass int4 NULL,
                                           roadnamefin varchar NULL,
                                           roadnameswe varchar NULL,
                                           roadnamesme text NULL,
                                           roadnamesmn text NULL,
                                           roadnamesms text NULL,
                                           roadnumber int4 NULL,
                                           roadpartnumber int4 NULL,
                                           surfacetype int4 NULL,
                                           lifecyclestatus int4 NULL,
                                           directiontype int4 NULL,
                                           surfacerelation int4 NULL,
                                           xyaccuracy float8 NULL,
                                           zaccuracy float8 NULL,
                                           horizontallength float8 NULL,
                                           addressfromleft int4 NULL,
                                           addresstoleft int4 NULL,
                                           addressfromright int4 NULL,
                                           addresstoright int4 NULL,
                                           starttime timestamp NULL,
                                           versionstarttime timestamp NULL,
                                           sourcemodificationtime timestamp NULL,
                                           geometry public.geometry NULL,
                                           ajorata int4 NULL
);
CREATE INDEX complementary_data_geometry_i ON public.complementary_data USING gist (geometry);
CREATE INDEX complementary_data_link_id_i ON public.complementary_data USING btree (id);
