BEGIN TRANSACTION;

SELECT 'alku' as t, current_timestamp;

INSERT INTO arvosana (resource_id, suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, inserted, deleted, pisteet, myonnetty, source, jarjestys, lahde_arvot)
SELECT v.resource_id, v.suoritus, v.arvosana, v.asteikko, v.aine, null, v.valinnainen, floor(extract(epoch from now()) * 1000), v.deleted, v.pisteet, v.myonnetty, v.source, v.jarjestys, v.lahde_arvot
FROM v_arvosana v WHERE v.lisatieto IS NOT null AND v.lisatieto = '';

COMMIT TRANSACTION;

SELECT 'loppu' as t, current_timestamp;




BEGIN TRANSACTION;

SELECT 'alku' as t, current_timestamp;

INSERT INTO suoritus (resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, inserted, deleted, source, kuvaus, vuosi, tyyppi, index, vahvistettu)
SELECT resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, floor(extract(epoch from now()) * 1000) as inserted, true as deleted, source, kuvaus, vuosi, tyyppi, index, vahvistettu
FROM v_suoritus s
WHERE vahvistettu = false
AND myontaja LIKE '1.2.246.562.%'
AND NOT EXISTS(SELECT 1 FROM arvosana WHERE suoritus = s.resource_id)
AND source = henkilo_oid;

COMMIT TRANSACTION;

SELECT 'loppu' as t, current_timestamp;




BEGIN TRANSACTION;

SELECT 'alku' as t, current_timestamp;

INSERT INTO suoritus (resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, inserted, deleted, source, kuvaus, vuosi, tyyppi, index, vahvistettu)
SELECT resource_id, komo, myontaja, 'VALMIS' as tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, floor(extract(epoch from now()) * 1000) as inserted, false as deleted, source, kuvaus, vuosi, tyyppi, index, vahvistettu
FROM v_suoritus s
WHERE valmistuminen < '2015-08-01'
AND tila = 'KESKEN'
AND vahvistettu = true
AND komo <> '1.2.246.562.5.2013061010184237348007'
AND EXISTS(SELECT 1 FROM v_arvosana WHERE suoritus = s.resource_id);

COMMIT TRANSACTION;

SELECT 'loppu' as t, current_timestamp;
