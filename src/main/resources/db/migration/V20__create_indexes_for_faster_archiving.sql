-- to speed up queries to find out rows to be archived

create index arvosana_inserted_current_idx on arvosana(inserted, current);

create index import_batch_inserted_current_idx on import_batch(inserted, current);

create index opiskelija_inserted_current_idx on opiskelija(inserted, current);

create index opiskeluoikeus_inserted_current_idx on opiskeluoikeus(inserted, current);

create index suoritus_inserted_current_idx on suoritus(inserted, current);
