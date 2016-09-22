create table arvosana_new as select resource_id, suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, inserted, deleted, pisteet, myonnetty, source, jarjestys, lahde_arvot, TRUE as current from arvosana, (select resource_id as ri, max(inserted) as mi from arvosana group by resource_id) as x2 where arvosana.resource_id = x2.ri and arvosana.inserted = x2.mi;
alter table arvosana_new alter column current set default false;
create unique index arvosana_new_resource_id_inserted_idx on arvosana_new (resource_id, inserted);
create index arvosana_new_suoritus_idx on arvosana_new (suoritus, deleted);


create table import_batch_new as select resource_id, inserted, deleted, data, external_id, batch_type, source, state, status, TRUE as current from import_batch, (select resource_id as ri, max(inserted) as mi from import_batch group by resource_id) as x2 where import_batch.resource_id = x2.ri and import_batch.inserted = x2.mi;
alter table import_batch_new alter column current set default false;
create unique index import_batch_resource_id_inserted_idx on import_batch_new (resource_id, inserted);
create index i_import_batch_external_id on import_batch_new (external_id);
create index i_import_batch_state on import_batch_new (state);
create index i_import_batch_batch_type on import_batch_new (batch_type);


create table opiskelija_new as select resource_id, oppilaitos_oid, luokkataso, luokka, henkilo_oid, alku_paiva, loppu_paiva, inserted, deleted, source, TRUE as current from opiskelija, (select resource_id as ri, max(inserted) as mi from opiskelija group by resource_id) as x2 where opiskelija.resource_id = x2.ri and opiskelija.inserted = x2.mi;
alter table opiskelija_new alter column current set default false;
create unique index "opiskelija_resource_id_inserted_idx" on opiskelija_new (resource_id, inserted);
create index opiskelija_henkilo_oid_idx on opiskelija_new (henkilo_oid);
create index oppilaitos_oid_idx on opiskelija_new (oppilaitos_oid);


create table opiskeluoikeus_new as select resource_id, alku_paiva, loppu_paiva, henkilo_oid, komo, myontaja, source, inserted, deleted, TRUE as current from opiskeluoikeus, (select resource_id as ri, max(inserted) as mi from opiskeluoikeus group by resource_id) as x2 where opiskeluoikeus.resource_id = x2.ri and opiskeluoikeus.inserted = x2.mi;
alter table opiskeluoikeus_new alter column current set default false;
create unique index opiskeluoikeus_resource_id_inserted_idx on opiskeluoikeus_new  (resource_id, inserted);
create index "opiskeluoikeus_henkilo_oid_idx" on opiskeluoikeus_new (henkilo_oid)

  
create table suoritus_new as select resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli,  inserted, deleted, source, kuvaus, vuosi, tyyppi, index, vahvistettu, TRUE as current from suoritus, (select resource_id as ri, max(inserted) as mi from suoritus group by resource_id) as x2 where suoritus.resource_id = x2.ri and suoritus.inserted = x2.mi;
alter table suoritus_new alter column current set default false;
create unique index suoritus_resource_id_inserted_idx on suoritus_new (resource_id, inserted)
create index suoritus_henkilo_oid_idx on suoritus_new (henkilo_oid)
create index  suoritus_komo_idx on suoritus_new (komo)
create index  suoritus_myontaja_idx on suoritus_new (myontaja)


alter table arvosana rename to arvosana_old;
alter table arvosana_new rename to arvosana;

alter table import_batch rename to import_batch_old;
alter table import_batch_new rename to import_batch;

alter table opiskelija rename to opiskelija_old;
alter table opiskelija_new rename to opiskelija;

alter table opiskeluoikeus rename to opiskeluoikeus_old;
alter table opiskeluoikeus_new rename to opiskeluoikeus;

alter table suoritus rename to suoritus_old;
alter table suoritus_new rename to suoritus;