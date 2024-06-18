comment on database suoritusrekisteri is 'Suoritusrekisteri';

--arvosana
COMMENT ON TABLE arvosana IS 'Yksittäisten arvosanojen tiedot ja versiot';
COMMENT ON COLUMN arvosana.resource_id IS 'Arvosanan tunniste';
COMMENT ON COLUMN arvosana.suoritus IS 'Sen suorituksen tunniste, johon tämä arvosana liittyy';
COMMENT ON COLUMN arvosana.current IS 'Onko tämä arvosana tuorein versio itsestään';
COMMENT ON COLUMN arvosana.deleted IS 'Onko tämä arvosana merkitty poistetuksi';
COMMENT ON COLUMN arvosana.arvosana IS 'Annettu arvosana';
COMMENT ON COLUMN arvosana.asteikko IS 'Käytetty arvosana-asteikko';
COMMENT ON COLUMN arvosana.aine IS 'Aine, johon arvosana liittyy';
COMMENT ON COLUMN arvosana.lisatieto IS 'Aineen lisätieto';
COMMENT ON COLUMN arvosana.valinnainen IS 'Onko arvosana valinnainen';
COMMENT ON COLUMN arvosana.inserted IS 'Arvosanan kantatallennuksen aikaleima';
COMMENT ON COLUMN arvosana.myonnetty IS 'Arvosanan myöntämisen aikaleima';
COMMENT ON COLUMN arvosana.pisteet IS 'Arvosanan pisteet';
COMMENT ON COLUMN arvosana.source IS 'Arvosanan lähde';
COMMENT ON COLUMN arvosana.jarjestys IS 'Arvosanan järjestys';
COMMENT ON COLUMN arvosana.lahde_arvot IS 'Mahdollisia lisätietoja json-muodossa';

--suoritus
COMMENT ON TABLE suoritus IS 'Suoritusten tiedot ja versiot';
COMMENT ON COLUMN suoritus.resource_id IS 'Suorituksen tunniste';
COMMENT ON COLUMN suoritus.komo IS 'Suorituksen koulutusmoduuli';
COMMENT ON COLUMN suoritus.myontaja IS 'Suorituksen myöntäjäorganisaatio';
COMMENT ON COLUMN suoritus.tila IS 'Suorituksen tila';
COMMENT ON COLUMN suoritus.valmistuminen IS 'Suorituksen valmistumisen aikaleima';
COMMENT ON COLUMN suoritus.henkilo_oid IS 'HenkilöOid, jolle suoritus kuuluu';
COMMENT ON COLUMN suoritus.yksilollistaminen IS 'Onko suorituksen oppimäärää yksilöllistetty';
COMMENT ON COLUMN suoritus.suoritus_kieli IS 'Kieli jolla suoritus on suoritettu';
COMMENT ON COLUMN suoritus.inserted IS 'Suorituksen kantatallennuksen aikaleima';
COMMENT ON COLUMN suoritus.deleted IS 'Onko suoritus merkitty poistetuksi';
COMMENT ON COLUMN suoritus.source IS 'Suoritustiedon lähde';
COMMENT ON COLUMN suoritus.kuvaus IS 'Suorituksen kuvaus';
COMMENT ON COLUMN suoritus.vuosi IS 'Suorituksen vuosi';
COMMENT ON COLUMN suoritus.tyyppi IS 'Suorituksen tyyppi';
COMMENT ON COLUMN suoritus.index IS 'Henkilö, jolle suoritus kuuluu';
COMMENT ON COLUMN suoritus.vahvistettu IS 'Onko suoritus vahvistettu';
COMMENT ON COLUMN suoritus.current IS 'Onko tämä suoritus tuorein versio itsestään';
COMMENT ON COLUMN suoritus.lahde_arvot IS 'Mahdollisia lisätietoja json-muodossa';

--opiskelija
COMMENT ON TABLE opiskelija IS 'Opiskelijatieto (luokkatieto), tyypillisesti löyhä yhteys johonkin suoritukseen';
COMMENT ON COLUMN opiskelija.resource_id IS 'Opiskelijatiedon tunniste';
COMMENT ON COLUMN opiskelija.oppilaitos_oid IS 'Oppilaitos';
COMMENT ON COLUMN opiskelija.luokkataso IS 'Luokkataso';
COMMENT ON COLUMN opiskelija.luokka IS 'Luokka';
COMMENT ON COLUMN opiskelija.henkilo_oid IS 'Henkilö, jota opiskelijatieto koskee';
COMMENT ON COLUMN opiskelija.alku_paiva IS 'Alkupäivä (aikaleima)';
COMMENT ON COLUMN opiskelija.loppu_paiva IS 'Loppupäivä (aikaleima)';
COMMENT ON COLUMN opiskelija.inserted IS 'Opiskelijatiedon kantatallennuksen aikaleima';
COMMENT ON COLUMN opiskelija.deleted IS 'Onko opiskelijatieto merkitty poistetuksi';
COMMENT ON COLUMN opiskelija.source IS 'Opiskelijatiedon lähde';
COMMENT ON COLUMN opiskelija.current IS 'Onko tämä opiskelijatieto tuorein versio itsestään';

--opiskeluoikeus
COMMENT ON TABLE opiskeluoikeus IS 'Opiskeluoikeuden tiedot ja versiot';
COMMENT ON COLUMN opiskeluoikeus.resource_id IS 'Opiskeluoikeuden tunniste';
COMMENT ON COLUMN opiskeluoikeus.alku_paiva IS 'Opiskeluoikeuden alkupäivä (aikaleima)';
COMMENT ON COLUMN opiskeluoikeus.loppu_paiva IS 'Opiskeluoikeuden loppupäivä (aikaleima)';
COMMENT ON COLUMN opiskeluoikeus.henkilo_oid IS 'HenkilöOid, jolle opiskeluoikeus kuuluu';
COMMENT ON COLUMN opiskeluoikeus.komo IS 'Opiskeluoikeuden koulutusmoduuli';
COMMENT ON COLUMN opiskeluoikeus.myontaja IS 'Opiskeluoikeuden myöntäjä';
COMMENT ON COLUMN opiskeluoikeus.source IS 'Tiedon lähde';
COMMENT ON COLUMN opiskeluoikeus.inserted IS 'Opiskeluoikeuden kantatallennuksen aikaleima';
COMMENT ON COLUMN opiskeluoikeus.deleted IS 'Onko opiskeluoikeustieto merkitty poistetuksi';
COMMENT ON COLUMN opiskeluoikeus.current IS 'Onko tämä opiskeluoikeus tuorein versio itsestään';

