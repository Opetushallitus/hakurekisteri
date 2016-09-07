-- Function: arkistoi_opiskeluoikeus_deltat()

-- DROP FUNCTION arkistoi_opiskeluoikeus_deltat();



CREATE TABLE IF NOT EXISTS a_opiskeluoikeus (LIKE opiskeluoikeus INCLUDING ALL);

ALTER TABLE a_opiskeluoikeus OWNER TO oph;



CREATE OR REPLACE FUNCTION arkistoi_opiskeluoikeus_deltat(amount integer)
  RETURNS integer AS
$BODY$
DECLARE
  _resource_id varchar(200);
  _inserted bigint;
  _count int := 0;
  delta record;
BEGIN
  FOR delta IN
    SELECT resource_id, inserted FROM opiskeluoikeus where not current
    LIMIT amount
  LOOP
    INSERT INTO a_opiskeluoikeus SELECT resource_id,
                                   alku_paiva,
                                   loppu_paiva,
                                   henkilo_oid,
                                   komo,
                                   myontaja,
                                   source,
                                   inserted,
                                   deleted FROM opiskeluoikeus WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    DELETE FROM opiskeluoikeus WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    _count := _count + 1;
    RAISE NOTICE '%: archived delta: %, %', _count, delta.resource_id, delta.inserted;
  END LOOP;

  RETURN _count;
END;
$BODY$
  LANGUAGE plpgsql VOLATILE
  COST 100;

ALTER FUNCTION arkistoi_opiskeluoikeus_deltat(integer) OWNER TO oph;
