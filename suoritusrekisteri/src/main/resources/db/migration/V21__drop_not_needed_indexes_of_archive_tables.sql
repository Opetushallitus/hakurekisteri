-- drop all indexes from a_ tables including the primary key ones.
-- this list of indexes is found in production, it may happen
-- that in other environments a_ tables have different set of indexes

drop index if exists a_arvosana_resource_id_inserted_idx;
drop index if exists a_arvosana_suoritus_deleted_idx;

alter table if exists a_import_batch drop constraint if exists a_import_batch_pkey;
drop index if exists a_import_batch_pkey;
drop index if exists a_import_batch_batch_type_idx;
drop index if exists a_import_batch_external_id_idx;
drop index if exists a_import_batch_resource_id_inserted_idx;
drop index if exists a_import_batch_state_idx;

drop index if exists a_opiskelija_henkilo_oid_idx;
drop index if exists a_opiskelija_resource_id_inserted_idx;

drop index if exists a_opiskeluoikeus_resource_id_inserted_idx;

drop index if exists a_suoritus_resource_id_inserted_idx;
drop index if exists a_suoritus_henkilo_oid_idx;
drop index if exists a_suoritus_komo_idx;
drop index if exists a_suoritus_myontaja_idx;

