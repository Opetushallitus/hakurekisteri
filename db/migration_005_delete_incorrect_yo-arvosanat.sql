BEGIN TRANSACTION;

SELECT 'lahdearvottomat yo-arvosanat alku' as t, current_timestamp;

INSERT INTO arvosana (resource_id, suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, inserted, deleted, pisteet, myonnetty, source, jarjestys, lahde_arvot)
  SELECT v.resource_id, v.suoritus, v.arvosana, v.asteikko, v.aine, v.lisatieto, v.valinnainen, floor(extract(epoch from now()) * 1000), true as deleted, v.pisteet, v.myonnetty, v.source, v.jarjestys, v.lahde_arvot
  FROM v_arvosana v
  WHERE v.asteikko IN ('YO', 'OSAKOE')
    AND v.myonnetty > '1990-01-01'
    AND v.lahde_arvot = '{}';

COMMIT TRANSACTION;

SELECT 'lahdearvottomat yo-arvosanat loppu' as t, current_timestamp;




BEGIN TRANSACTION;

SELECT 'duplikaatti yo-arvosanat alku' as t, current_timestamp;

INSERT INTO arvosana (resource_id, suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, inserted, deleted, pisteet, myonnetty, source, jarjestys, lahde_arvot)
  SELECT DISTINCT v.resource_id, v.suoritus, v.arvosana, v.asteikko, v.aine, v.lisatieto, v.valinnainen, floor(extract(epoch from now()) * 1000), true as deleted, v.pisteet, v.myonnetty, v.source, v.jarjestys, v.lahde_arvot
  FROM v_arvosana v
    JOIN (
      SELECT resource_id, lisatieto FROM arvosana
      UNION
      SELECT resource_id, lisatieto FROM a_arvosana
    ) t ON t.resource_id = v.resource_id AND t.lisatieto <> v.lisatieto
  WHERE v.asteikko IN ('YO', 'OSAKOE')
    AND v.myonnetty > '1990-01-01';

COMMIT TRANSACTION;

SELECT 'duplikaatti yo-arvosanat loppu' as t, current_timestamp;

--edellinen poisto vaikuttaa seuraavien henkilöiden arvosanoihin:
--"1.2.246.562.24.43999806398"
--"1.2.246.562.24.58766762456"
--"1.2.246.562.24.65813729432"
--"1.2.246.562.24.22900376595"
--"1.2.246.562.24.38713337728"
--"1.2.246.562.24.88825976645"
--"1.2.246.562.24.69485890193"
--"1.2.246.562.24.61786195692"
--"1.2.246.562.24.88338728102"
--"1.2.246.562.24.47560237318"
--"1.2.246.562.24.83454817824"
--"1.2.246.562.24.55863098547"
--"1.2.246.562.24.23548351612"
--"1.2.246.562.24.92245738611"
--"1.2.246.562.24.70538226535"
--"1.2.246.562.24.49130846700"
--"1.2.246.562.24.60285410905"
--"1.2.246.562.24.79357442601"




BEGIN TRANSACTION;

SELECT 'tyhjat yo-suoritukset alku' as t, current_timestamp;

INSERT INTO suoritus (resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, inserted, deleted, source, kuvaus, vuosi, tyyppi, index, vahvistettu)
  SELECT resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, floor(extract(epoch from now()) * 1000) as inserted, true as deleted, source, kuvaus, vuosi, tyyppi, index, vahvistettu
  FROM v_suoritus v
  WHERE v.resource_id IN (
    SELECT resource_id FROM v_suoritus WHERE komo = '1.2.246.562.5.2013061010184237348007'
    EXCEPT
    SELECT suoritus FROM v_arvosana WHERE asteikko IN ('YO', 'OSAKOE')
  );

COMMIT TRANSACTION;

SELECT 'tyhjat yo-suoritukset loppu' as t, current_timestamp;




BEGIN TRANSACTION;

SELECT 'hetulliset yo-suoritukset alku' as t, current_timestamp;

INSERT INTO suoritus (resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, inserted, deleted, source, kuvaus, vuosi, tyyppi, index, vahvistettu)
  SELECT resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, floor(extract(epoch from now()) * 1000) as inserted, true as deleted, source, kuvaus, vuosi, tyyppi, index, vahvistettu
  FROM v_suoritus v
  WHERE komo = '1.2.246.562.5.2013061010184237348007'
    AND length(henkilo_oid) = 11;

COMMIT TRANSACTION;

SELECT 'hetulliset yo-suoritukset loppu' as t, current_timestamp;




BEGIN TRANSACTION;

SELECT 'orvot arvosanat alku' as t, current_timestamp;

INSERT INTO arvosana (resource_id, suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, inserted, deleted, pisteet, myonnetty, source, jarjestys, lahde_arvot)
  SELECT v.resource_id, v.suoritus, v.arvosana, v.asteikko, v.aine, v.lisatieto, v.valinnainen, floor(extract(epoch from now()) * 1000), true as deleted, v.pisteet, v.myonnetty, v.source, v.jarjestys, v.lahde_arvot
  FROM v_arvosana v
  WHERE suoritus IN (
    SELECT DISTINCT suoritus FROM v_arvosana
    EXCEPT
    SELECT resource_id FROM v_suoritus
  );

COMMIT TRANSACTION;

SELECT 'orvot arvosanat loppu' as t, current_timestamp;
