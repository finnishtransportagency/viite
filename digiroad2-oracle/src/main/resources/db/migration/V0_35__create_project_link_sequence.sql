declare
  nextId number;
begin
  select MAX(ID) + 1 into nextId from PROJECT_LINK;
  if nextId IS NULL then nextId := 1; end if;
  execute immediate 'CREATE SEQUENCE VIITE_PROJECT_LINK_SEQ START WITH ' || nextId || ' CACHE 100 INCREMENT BY 1';
END;
