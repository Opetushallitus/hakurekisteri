BEGIN TRANSACTION;

SELECT 'alku' as t, current_timestamp;

INSERT INTO suoritus (resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, inserted, deleted, source, kuvaus, vuosi, tyyppi, index, vahvistettu)
SELECT resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, floor(extract(epoch from now()) * 1000) as inserted, deleted, source, kuvaus, vuosi, tyyppi, index, true as vahvistettu
FROM v_suoritus s
WHERE vahvistettu IS NULL
AND tyyppi IS NULL;

COMMIT TRANSACTION;

SELECT 'loppu' as t, current_timestamp;