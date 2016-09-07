-- Function: arkistoi_arvosana_deltat()

-- DROP FUNCTION arkistoi_arvosana_deltat();



CREATE TABLE IF NOT EXISTS a_arvosana (LIKE arvosana INCLUDING ALL);

ALTER TABLE a_arvosana OWNER TO oph;



CREATE OR REPLACE FUNCTION arkistoi_arvosana_deltat(amount integer)
  RETURNS integer AS
$BODY$
DECLARE
  _resource_id varchar(200);
  _inserted bigint;
  _count int := 0;
  delta record;
BEGIN
  FOR delta IN
    SELECT resource_id, inserted FROM arvosana where not current
    LIMIT amount
  LOOP
    INSERT INTO a_arvosana SELECT
                             resource_id,
                             suoritus,
                             arvosana,
                             asteikko,
                             aine,
                             lisatieto,
                             valinnainen,
                             inserted,
                             deleted,
                             pisteet,
                             myonnetty,
                             source,
                             jarjestys,
                             lahde_arvot FROM arvosana WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    DELETE FROM arvosana WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    _count := _count + 1;
    RAISE NOTICE '%: archived delta: %, %', _count, delta.resource_id, delta.inserted;
  END LOOP;

  RETURN _count;
END;
$BODY$
  LANGUAGE plpgsql VOLATILE
  COST 100;

ALTER FUNCTION arkistoi_arvosana_deltat(integer) OWNER TO oph;
