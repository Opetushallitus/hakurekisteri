INSERT INTO arvosana (resource_id, inserted, deleted, suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, pisteet, myonnetty, source, jarjestys)
  SELECT a.resource_id, floor(extract(epoch from now()) * 1000), false, a.suoritus, a.arvosana, a.asteikko, a.aine, a.lisatieto, a.valinnainen, a.pisteet, s.valmistuminen, a.source, a.jarjestys
    FROM v_arvosana a
      JOIN v_suoritus s ON a.suoritus = s.resource_id
    WHERE a.asteikko IN ('YO', 'OSAKOE')
      AND a.myonnetty IS NULL;