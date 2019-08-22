-- drop all indexes from a_ tables except the primary key ones.

drop index if exists a_arvosana_suoritus_deleted_idx;

drop index if exists a_import_batch_batch_type_idx;
drop index if exists a_import_batch_external_id_idx;
drop index if exists a_import_batch_resource_id_inserted_idx;
drop index if exists a_import_batch_state_idx;

drop index if exists a_opiskelija_henkilo_oid_idx;

drop index if exists a_suoritus_henkilo_oid_idx;
drop index if exists a_suoritus_komo_idx;
drop index if exists a_suoritus_myontaja_idx;

