-- Function: arkistoi_import_batch_deltat()

-- DROP FUNCTION arkistoi_import_batch_deltat();



CREATE TABLE IF NOT EXISTS a_import_batch (LIKE import_batch INCLUDING ALL);

ALTER TABLE a_import_batch OWNER TO oph;



CREATE OR REPLACE FUNCTION arkistoi_import_batch_deltat(amount integer)
  RETURNS integer AS
$BODY$
DECLARE
  _resource_id varchar(200);
  _inserted bigint;
  _count int := 0;
  delta record;
BEGIN
  FOR delta IN
    SELECT resource_id, inserted FROM import_batch
    EXCEPT
    SELECT resource_id, inserted FROM v_import_batch
    LIMIT amount
  LOOP
    INSERT INTO a_import_batch SELECT * FROM import_batch WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    DELETE FROM import_batch WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    _count := _count + 1;
    RAISE NOTICE '%: archived delta: %, %', _count, delta.resource_id, delta.inserted;
  END LOOP;

  RETURN _count;
END;
$BODY$
  LANGUAGE plpgsql VOLATILE
  COST 100;

ALTER FUNCTION arkistoi_import_batch_deltat(integer) OWNER TO oph;
