#!/usr/bin/env bash


template=$(cat <<'END-OF-TEMPLATE'
--
-- Archive table: %%TABLE
--

CREATE TABLE IF NOT EXISTS a_%%TABLE (LIKE %%TABLE INCLUDING ALL);

ALTER TABLE a_%%TABLE OWNER TO oph;


CREATE OR REPLACE FUNCTION arkistoi_%%TABLE_deltat(amount integer, oldest bigint)
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
    FROM %%TABLE
    WHERE not current AND inserted < oldest
    LIMIT amount
  LOOP
    INSERT INTO a_%%TABLE (%%COLUMNS)
      SELECT %%COLUMNS
        FROM %%TABLE
        WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    DELETE FROM %%TABLE WHERE resource_id = delta.resource_id AND inserted = delta.inserted;
    _count := _count + 1;
    RAISE NOTICE '%: archived %%TABLE delta: %, %', _count, delta.resource_id, delta.inserted;
  END LOOP;

  RETURN _count;
END;
$BODY$
  LANGUAGE plpgsql VOLATILE
  COST 100;

ALTER FUNCTION arkistoi_%%TABLE_deltat(integer, bigint) OWNER TO oph;

-- invoke the function
select arkistoi_%%TABLE_deltat(:amount, :oldest), :amount, :oldest;

-- END of archive table: %%TABLE
END-OF-TEMPLATE
)

rootDir="db-scripts"
fileName="arkistoi_kaikki_deltat.sql"
echo "-- Generated on " `date` > $rootDir/$fileName
cat <<END-OF-HEADER >>$rootDir/$fileName
--
-- Use this SQL script to create archive tables and archive functions
-- to move the irrelevant rows from original tables into their corresponding
-- archive tables. The script will also invoke the archive functions.
--
-- This file is generated at image creation time. Do not edit manually.
-- If you want to generate this file manually, run the generating script from its
-- parent directory: db-scripts/generate-archive-function.sh
--
END-OF-HEADER


while IFS=':' read table columns
do
    echo "Generate archiving sql script for: $table ($columns)"
    echo "$template" | sed "s/%%TABLE/$table/g;s/%%COLUMNS/$columns/g" >> $rootDir/$fileName
done < $rootDir/tables.data


