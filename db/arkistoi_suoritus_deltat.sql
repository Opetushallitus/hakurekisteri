-- Function: arkistoi_suoritus_deltat()

-- DROP FUNCTION arkistoi_suoritus_deltat();



CREATE TABLE IF NOT EXISTS a_suoritus (LIKE suoritus INCLUDING ALL);

ALTER TABLE a_suoritus OWNER TO oph;



CREATE OR REPLACE FUNCTION arkistoi_suoritus_deltat(amount integer)
  RETURNS integer AS
$BODY$
DECLARE
  _resource_id varchar(200);
  _inserted bigint;
  _count int := 0;
  delta record;
BEGIN
  FOR delta IN
    SELECT resource_id, inserted FROM suoritus WHERE NOT current
    LIMIT amount
  LOOP
    INSERT INTO a_suoritus SELECT resource_id,
                             komo,
                             myontaja,
                             tila,
                             valmistuminen,
                             henkilo_oid,
                             yksilollistaminen,
                             suoritus_kieli,
                             inserted,
                             deleted,
                             source,
                             kuvaus,
                             vuosi,
                             tyyppi,
                             index,
                             vahvistettu FROM suoritus WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    DELETE FROM suoritus WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    _count := _count + 1;
    RAISE NOTICE '%: archived delta: %, %', _count, delta.resource_id, delta.inserted;
  END LOOP;

  RETURN _count;
END;
$BODY$
  LANGUAGE plpgsql VOLATILE
  COST 100;

ALTER FUNCTION arkistoi_suoritus_deltat(integer) OWNER TO oph;
