BEGIN TRANSACTION;

ALTER TABLE "arvosana"
  ADD "lahde_arvot" TEXT NOT NULL DEFAULT '{}';
ALTER TABLE "a_arvosana"
  ADD "lahde_arvot" TEXT NOT NULL DEFAULT '{}';
CREATE OR REPLACE VIEW v_arvosana AS
  SELECT
    a.resource_id,
    a.suoritus,
    a.arvosana,
    a.asteikko,
    a.aine,
    a.lisatieto,
    a.valinnainen,
    a.inserted,
    a.deleted,
    a.pisteet,
    a.myonnetty,
    a.source,
    a.jarjestys,
    a.lahde_arvot
  FROM arvosana a, (SELECT arvosana.resource_id, max(arvosana.inserted) AS inserted FROM arvosana GROUP BY arvosana.resource_id) ai WHERE ((((a.resource_id)::text = (ai.resource_id)::text) AND (a.inserted = ai.inserted)) AND (a.deleted = false));

END TRANSACTION;
