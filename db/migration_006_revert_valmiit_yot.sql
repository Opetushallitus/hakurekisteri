BEGIN TRANSACTION;

SELECT 'revert valmiit vanhat yo-arvosanat alku' as t, current_timestamp;

INSERT INTO suoritus (resource_id,
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
                      vahvistettu)
  SELECT DISTINCT viimeisin.resource_id,
         viimeisin.komo,
         viimeisin.myontaja,
         delta.tila,
         delta.valmistuminen,
         viimeisin.henkilo_oid,
         viimeisin.yksilollistaminen,
         viimeisin.suoritus_kieli,
         floor(extract(epoch from now()) * 1000) as inserted,
         viimeisin.deleted,
         viimeisin.source,
         viimeisin.kuvaus,
         viimeisin.vuosi,
         viimeisin.tyyppi,
         viimeisin.index,
         viimeisin.vahvistettu
    FROM v_suoritus viimeisin
    JOIN (SELECT resource_id, inserted, tila, valmistuminen, source FROM suoritus WHERE komo = '1.2.246.562.5.2013061010184237348007'
          UNION
          SELECT resource_id, inserted, tila, valmistuminen, source FROM a_suoritus WHERE komo = '1.2.246.562.5.2013061010184237348007') delta
        ON (delta.resource_id = viimeisin.resource_id
           AND delta.inserted < viimeisin.inserted
           AND delta.tila = 'VALMIS'
           AND delta.valmistuminen < '1990-01-01')
  WHERE viimeisin.tila = 'KESKEN'
    AND viimeisin.valmistuminen = '2015-12-21'
    AND viimeisin.komo = '1.2.246.562.5.2013061010184237348007'
    AND viimeisin.source = '1.2.246.562.10.43628088406';

COMMIT TRANSACTION;

SELECT 'revert valmiit vanhat yo-arvosanat loppu' as t, current_timestamp;

