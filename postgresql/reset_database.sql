create or replace function truncate_if_exists(tablename text)
    returns void language plpgsql as $$
begin
    perform 1
    from information_schema.tables
    where table_name = tablename;
    if found then
        execute format('truncate %I', tablename);
    end if;
end $$;

select truncate_if_exists('arvosana');
select truncate_if_exists('suoritus');
select truncate_if_exists('opiskelija');
select truncate_if_exists('import_batch');
select truncate_if_exists('opiskeluoikeus');

select truncate_if_exists('a_arvosana');
select truncate_if_exists('a_suoritus');
select truncate_if_exists('a_opiskelija');
select truncate_if_exists('a_import_batch');
select truncate_if_exists('a_opiskeluoikeus');
