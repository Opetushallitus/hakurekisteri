BEGIN TRANSACTION;

ALTER TABLE "suoritus"
  ADD "lahde_arvot" TEXT NOT NULL DEFAULT '{}';
ALTER TABLE "a_suoritus"
  ADD "lahde_arvot" TEXT NOT NULL DEFAULT '{}';

END TRANSACTION;