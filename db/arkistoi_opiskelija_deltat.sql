-- Function: arkistoi_opiskelija_deltat()

-- DROP FUNCTION arkistoi_opiskelija_deltat();



CREATE TABLE IF NOT EXISTS a_opiskelija (LIKE opiskelija INCLUDING ALL);

ALTER TABLE a_opiskelija OWNER TO oph;



CREATE OR REPLACE FUNCTION arkistoi_opiskelija_deltat(amount integer)
  RETURNS integer AS
$BODY$
DECLARE
  _resource_id varchar(200);
  _inserted bigint;
  _count int := 0;
  delta record;
BEGIN
  FOR delta IN
    SELECT resource_id, inserted FROM opiskelija
    EXCEPT
    SELECT resource_id, inserted FROM v_opiskelija
    LIMIT amount
  LOOP
    INSERT INTO a_opiskelija SELECT * FROM opiskelija WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    DELETE FROM opiskelija WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    _count := _count + 1;
    RAISE NOTICE '%: archived delta: %, %', _count, delta.resource_id, delta.inserted;
  END LOOP;

  RETURN _count;
END;
$BODY$
  LANGUAGE plpgsql VOLATILE
  COST 100;

ALTER FUNCTION arkistoi_opiskelija_deltat(integer) OWNER TO oph;
