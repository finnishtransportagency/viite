declare
  nextId number;
begin
  select MAX(ID) + 1 into nextId from PROJECT;
  if nextId IS NULL then nextId := 1; end if;
  begin
    execute immediate 'DROP SEQUENCE VIITE_PROJECT_SEQ';
    exception when others then
      null;
  end;
  execute immediate 'CREATE SEQUENCE VIITE_PROJECT_SEQ START WITH ' || nextId || ' CACHE 20 INCREMENT BY 1';
END;
