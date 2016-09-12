alter table arvosana add COLUMN current bool default FALSE;
alter table import_batch add COLUMN current bool default FALSE;
alter table opiskelija add COLUMN current bool default FALSE;
alter table opiskeluoikeus add COLUMN current bool default FALSE;
alter table suoritus add COLUMN current bool default FALSE;

create table arvosana_new as select resource_id, suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, inserted, deleted, pisteet, myonnetty, source, jarjestys, lahde_arvot, TRUE as current from arvosana, (select resource_id as ri, max(inserted) as mi from arvosana group by resource_id) as x2 where arvosana.resource_id = x2.ri and arvosana.inserted = x2.mi;

create unique index arvosana_new_resource_id_inserted_idx on arvosana_new (resource_id, inserted);
create index arvosana_new_suoritus_idx on arvosana_new (suoritus, deleted);

alter table arvosana rename to arvosana_old;
alter table arvosana_new rename to arvosana;

update import_batch set current = TRUE from (select resource_id, max(inserted) as max_inserted from import_batch group by resource_id) x2 where import_batch.resource_id = x2.resource_id and inserted = x2.max_inserted;
update opiskelija set current = TRUE from (select resource_id, max(inserted) as max_inserted from opiskelija group by resource_id) x2 where opiskelija.resource_id = x2.resource_id and inserted = x2.max_inserted;
update opiskeluoikeus set current = TRUE from (select resource_id, max(inserted) as max_inserted from opiskeluoikeus group by resource_id) x2 where opiskeluoikeus.resource_id = x2.resource_id and inserted = x2.max_inserted;
update suoritus set current = TRUE from (select resource_id, max(inserted) as max_inserted from suoritus group by resource_id) x2 where suoritus.resource_id = x2.resource_id and inserted = x2.max_inserted;
