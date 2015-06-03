
CREATE OR REPLACE VIEW v_opiskeluoikeus AS
 SELECT o.resource_id, o.alku_paiva, o.loppu_paiva, o.henkilo_oid, o.komo, o.myontaja, o.source, o.inserted, o.deleted
   FROM opiskeluoikeus o, ( SELECT opiskeluoikeus.resource_id, max(opiskeluoikeus.inserted) AS inserted
           FROM opiskeluoikeus
          GROUP BY opiskeluoikeus.resource_id) oi
  WHERE o.resource_id::text = oi.resource_id::text AND o.inserted = oi.inserted AND o.deleted = false;

ALTER TABLE v_opiskeluoikeus
  OWNER TO oph;
GRANT ALL ON TABLE v_opiskeluoikeus TO oph;
GRANT SELECT ON TABLE v_opiskeluoikeus TO ranninranta_ro;
GRANT SELECT ON TABLE v_opiskeluoikeus TO kilponen_ro;
GRANT SELECT ON TABLE v_opiskeluoikeus TO katselija;



BEGIN TRANSACTION;

SELECT 'alku' as t, current_timestamp;

INSERT INTO opiskeluoikeus (resource_id, alku_paiva, loppu_paiva, henkilo_oid, komo, myontaja, inserted, deleted, source)
SELECT resource_id, alku_paiva, loppu_paiva, henkilo_oid, komo, myontaja, floor(extract(epoch from now()) * 1000) as inserted, true as deleted, source
FROM v_opiskeluoikeus;

COMMIT TRANSACTION;

SELECT 'loppu' as t, current_timestamp;
