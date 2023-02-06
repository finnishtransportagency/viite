ALTER TABLE PROJECT ADD COLUMN elys int4[];
update project p set elys = COALESCE (
     (select array_agg(distinct(pl.ely)) from project_link pl where pl.project_id = p.id),
     (select array_agg(distinct(plh.ely)) from project_link_history plh where plh.project_id = p.id)
    );

