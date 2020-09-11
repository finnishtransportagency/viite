declare
  nextId number;
begin
  select MAX(ID) + 1 into nextId from ROAD_NAME;
  if nextId IS NULL then nextId := 1; end if;
  begin
    execute immediate 'DROP SEQUENCE ROAD_NAME_SEQ';
    exception when others then
      null;
  end;
  execute immediate 'CREATE SEQUENCE ROAD_NAME_SEQ START WITH ' || nextId || ' CACHE 20 INCREMENT BY 1';
END;
