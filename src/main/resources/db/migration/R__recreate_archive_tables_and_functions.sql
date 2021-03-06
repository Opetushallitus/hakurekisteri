-- Generated on  Tue Sep 3 19:09:23 EEST 2019
--
-- Flyway will use this SQL script to create archive tables and archive functions
--
-- This file is generated, it contains a lot of duplicate code, and it is not
-- recommended to edit manually.
-- To (re)generate this script run: generate-archive-function.sh
--
--
-- Archive table: arvosana
--

CREATE TABLE IF NOT EXISTS a_arvosana (LIKE arvosana INCLUDING CONSTRAINTS INCLUDING DEFAULTS);


CREATE OR REPLACE FUNCTION arkistoi_arvosana_deltat(amount integer, oldest bigint)
  RETURNS integer AS
$BODY$
DECLARE
  _resource_id varchar(200);
  _inserted bigint;
  _count int := 0;
  delta record;
BEGIN
  FOR delta IN
    SELECT resource_id, inserted
    FROM arvosana
    WHERE not current AND inserted < oldest
    LIMIT amount
  LOOP
    INSERT INTO a_arvosana ( resource_id, suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, inserted, deleted, pisteet, myonnetty, source, jarjestys, lahde_arvot)
      SELECT  resource_id, suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, inserted, deleted, pisteet, myonnetty, source, jarjestys, lahde_arvot
        FROM arvosana
        WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    DELETE FROM arvosana WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    _count := _count + 1;
    RAISE NOTICE '%: archived arvosana delta: %, %', _count, delta.resource_id, delta.inserted;
  END LOOP;

  RETURN _count;
END;
$BODY$
  LANGUAGE plpgsql VOLATILE
  COST 100;

ALTER FUNCTION arkistoi_arvosana_deltat(integer, bigint) OWNER TO oph;

-- END of archive table: arvosana
--
-- Archive table: import_batch
--

CREATE TABLE IF NOT EXISTS a_import_batch (LIKE import_batch INCLUDING CONSTRAINTS INCLUDING DEFAULTS);


CREATE OR REPLACE FUNCTION arkistoi_import_batch_deltat(amount integer, oldest bigint)
  RETURNS integer AS
$BODY$
DECLARE
  _resource_id varchar(200);
  _inserted bigint;
  _count int := 0;
  delta record;
BEGIN
  FOR delta IN
    SELECT resource_id, inserted
    FROM import_batch
    WHERE not current AND inserted < oldest
    LIMIT amount
  LOOP
    INSERT INTO a_import_batch ( resource_id, inserted, deleted, data, external_id, batch_type, source, state, status)
      SELECT  resource_id, inserted, deleted, data, external_id, batch_type, source, state, status
        FROM import_batch
        WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    DELETE FROM import_batch WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    _count := _count + 1;
    RAISE NOTICE '%: archived import_batch delta: %, %', _count, delta.resource_id, delta.inserted;
  END LOOP;

  RETURN _count;
END;
$BODY$
  LANGUAGE plpgsql VOLATILE
  COST 100;

ALTER FUNCTION arkistoi_import_batch_deltat(integer, bigint) OWNER TO oph;

-- END of archive table: import_batch
--
-- Archive table: opiskelija
--

CREATE TABLE IF NOT EXISTS a_opiskelija (LIKE opiskelija INCLUDING CONSTRAINTS INCLUDING DEFAULTS);


CREATE OR REPLACE FUNCTION arkistoi_opiskelija_deltat(amount integer, oldest bigint)
  RETURNS integer AS
$BODY$
DECLARE
  _resource_id varchar(200);
  _inserted bigint;
  _count int := 0;
  delta record;
BEGIN
  FOR delta IN
    SELECT resource_id, inserted
    FROM opiskelija
    WHERE not current AND inserted < oldest
    LIMIT amount
  LOOP
    INSERT INTO a_opiskelija ( resource_id, oppilaitos_oid, luokkataso, luokka, henkilo_oid, alku_paiva, loppu_paiva, inserted, deleted, source)
      SELECT  resource_id, oppilaitos_oid, luokkataso, luokka, henkilo_oid, alku_paiva, loppu_paiva, inserted, deleted, source
        FROM opiskelija
        WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    DELETE FROM opiskelija WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    _count := _count + 1;
    RAISE NOTICE '%: archived opiskelija delta: %, %', _count, delta.resource_id, delta.inserted;
  END LOOP;

  RETURN _count;
END;
$BODY$
  LANGUAGE plpgsql VOLATILE
  COST 100;

ALTER FUNCTION arkistoi_opiskelija_deltat(integer, bigint) OWNER TO oph;

-- END of archive table: opiskelija
--
-- Archive table: opiskeluoikeus
--

CREATE TABLE IF NOT EXISTS a_opiskeluoikeus (LIKE opiskeluoikeus INCLUDING CONSTRAINTS INCLUDING DEFAULTS);


CREATE OR REPLACE FUNCTION arkistoi_opiskeluoikeus_deltat(amount integer, oldest bigint)
  RETURNS integer AS
$BODY$
DECLARE
  _resource_id varchar(200);
  _inserted bigint;
  _count int := 0;
  delta record;
BEGIN
  FOR delta IN
    SELECT resource_id, inserted
    FROM opiskeluoikeus
    WHERE not current AND inserted < oldest
    LIMIT amount
  LOOP
    INSERT INTO a_opiskeluoikeus ( resource_id, alku_paiva, loppu_paiva, henkilo_oid, komo, myontaja, source, inserted, deleted)
      SELECT  resource_id, alku_paiva, loppu_paiva, henkilo_oid, komo, myontaja, source, inserted, deleted
        FROM opiskeluoikeus
        WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    DELETE FROM opiskeluoikeus WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    _count := _count + 1;
    RAISE NOTICE '%: archived opiskeluoikeus delta: %, %', _count, delta.resource_id, delta.inserted;
  END LOOP;

  RETURN _count;
END;
$BODY$
  LANGUAGE plpgsql VOLATILE
  COST 100;

ALTER FUNCTION arkistoi_opiskeluoikeus_deltat(integer, bigint) OWNER TO oph;

-- END of archive table: opiskeluoikeus
--
-- Archive table: suoritus
--

CREATE TABLE IF NOT EXISTS a_suoritus (LIKE suoritus INCLUDING CONSTRAINTS INCLUDING DEFAULTS);


CREATE OR REPLACE FUNCTION arkistoi_suoritus_deltat(amount integer, oldest bigint)
  RETURNS integer AS
$BODY$
DECLARE
  _resource_id varchar(200);
  _inserted bigint;
  _count int := 0;
  delta record;
BEGIN
  FOR delta IN
    SELECT resource_id, inserted
    FROM suoritus
    WHERE not current AND inserted < oldest
    LIMIT amount
  LOOP
    INSERT INTO a_suoritus ( resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, inserted, deleted, source, kuvaus, vuosi, tyyppi, index, vahvistettu)
      SELECT  resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, inserted, deleted, source, kuvaus, vuosi, tyyppi, index, vahvistettu
        FROM suoritus
        WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    DELETE FROM suoritus WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    _count := _count + 1;
    RAISE NOTICE '%: archived suoritus delta: %, %', _count, delta.resource_id, delta.inserted;
  END LOOP;

  RETURN _count;
END;
$BODY$
  LANGUAGE plpgsql VOLATILE
  COST 100;

ALTER FUNCTION arkistoi_suoritus_deltat(integer, bigint) OWNER TO oph;

-- END of archive table: suoritus
