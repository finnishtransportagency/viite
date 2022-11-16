-- Used to set sequences after copying database tables.


-- Create from current sequence values.
-- Copy paste result to target db.
select
'CREATE SEQUENCE '||c.relname||
' START '||(select setval(c.relname::text, nextval(c.relname::text)-1)) || ';'
AS "CREATE VIITE SEQUENCEs"
FROM
  pg_class c
WHERE
  c.relkind = 'S'
and
 c.relnamespace = (select oid from pg_namespace where nspname = 'public')
and
 c.relowner = (select oid from pg_authid where rolname = 'viite');


-- Alter existing sequences
select
'ALTER SEQUENCE '||c.relname||
' RESTART WITH '||(select setval(c.relname::text, nextval(c.relname::text)-1)) || ';'
AS "ALTER VIITE SEQUENCEs"
FROM
  pg_class c
WHERE
  c.relkind = 'S'
and
 c.relnamespace = (select oid from pg_namespace where nspname = 'public')
and
 c.relowner = (select oid from pg_authid where rolname = 'viite');
