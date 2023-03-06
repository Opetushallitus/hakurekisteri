package fi.vm.sade.hakurekisteri.web.kkhakija

import fi.vm.sade.hakurekisteri.rest.support.{Cell, HakijatExcelWriter, Row, StringCell}

object KkExcelUtilV5 extends HakijatExcelWriter[Seq[Hakija]] {

  private val headers = Seq(
    "Hetu",
    "Syntymäaika",
    "Oppijanumero",
    "Sukunimi",
    "Etunimet",
    "Kutsumanimi",
    "Lahiosoite",
    "Postinumero",
    "Postitoimipaikka",
    "Maa",
    "Kansalaisuudet",
    "Matkapuhelin",
    "Puhelin",
    "Sahkoposti",
    "Lukuvuosimaksu",
    "Kotikunta",
    "Sukupuoli",
    "Aidinkieli",
    "Asiointikieli",
    "Koulusivistyskielet",
    "Koulutusmarkkinointilupa",
    "On ylioppilas",
    "Suoritusvuosi",
    "On ensikertalainen",
    "Haku",
    "Hakuvuosi",
    "Hakukausi",
    "Hakemusnumero",
    "Hakemuksen jättämisen aikaleima",
    "Hakemuksen viimeinen muokkausaikaleima",
    "Organisaatio",
    "Hakukohde",
    "Hakukohteen kk-id",
    "Hakutoiveen prioriteetti",
    "Avoin vayla",
    "Valinnan tila",
    "Valinnan aikaleima",
    "Pisteet",
    "Hyväksymisen ehto (ehdolliisestiHyvaksyttavissa,ehtoKoodi,ehtoFI,ehtoSV,ehtoEN)",
    "Valintatapajonon tyyppi",
    "Valintatapajonon nimi",
    "Vastaanottotieto",
    "Ilmoittautumiset",
    "Pohjakoulutus",
    "Julkaisulupa",
    "Hakukelpoisuus",
    "Hakukelpoisuuden lahde",
    "Maksuvelvollisuus",
    "Hakukohteen koulutukset 1(komoOid,koulutusKoodi,kkKoulutusId,koulutuksenAlkamisvuosi,koulutuksenAlkamiskausi," +
      "johtaaTutkintoon)",
    "Koulutus 2",
    "Koulutus 3",
    "Koulutus 4",
    "Koulutus 5",
    "Koulutus 6",
    "Liite 1(hakuId,hakuRyhmäId,tila,saapumisenTila,nimi,vastaanottaja)",
    "Liite 2",
    "Liite 3",
    "Liite 4",
    "Liite 5",
    "Liite 6"
  )

  override def getHeaders(hakijat: Seq[Hakija]): Set[Row] = Set(
    Row(0, headers.zipWithIndex.toSet.map((h: (String, Int)) => StringCell(h._2, h._1)))
  )

  override def getRows(hakijat: Seq[Hakija]): Set[Row] = hakijat
    .flatMap(hakija =>
      hakija.hakemukset.map(hakemus => {
        val rivi = Seq(
          hakija.hetu,
          hakija.syntymaaika.getOrElse(""),
          hakija.oppijanumero,
          hakija.sukunimi,
          hakija.etunimet,
          hakija.kutsumanimi,
          hakija.lahiosoite,
          hakija.postinumero,
          hakija.postitoimipaikka,
          hakija.maa,
          hakija.kansalaisuudet.getOrElse(List.empty).mkString(", "),
          hakija.matkapuhelin.getOrElse(""),
          hakija.puhelin.getOrElse(""),
          hakija.sahkoposti.getOrElse(""),
          hakemus.lukuvuosimaksu.getOrElse(""),
          hakija.kotikunta,
          hakija.sukupuoli,
          hakija.aidinkieli,
          hakija.asiointikieli,
          hakija.koulusivistyskielet.getOrElse(List.empty).mkString(", "),
          toBooleanX(hakija.koulutusmarkkinointilupa),
          toBooleanX(hakija.onYlioppilas),
          hakija.yoSuoritusVuosi.getOrElse(""),
          hakija.ensikertalainen.map(e => toBooleanX(e)).getOrElse(""),
          hakemus.haku,
          hakemus.hakuVuosi.toString,
          hakemus.hakuKausi,
          hakemus.hakemusnumero,
          hakemus.hakemusJattoAikaleima.getOrElse(""),
          hakemus.hakemusViimeinenMuokkausAikaleima.getOrElse(""),
          hakemus.organisaatio,
          hakemus.hakukohde,
          hakemus.hakukohdeKkId.getOrElse(""),
          hakemus.hakutoivePrioriteetti.map(_.toString).getOrElse(""),
          toBooleanX(hakemus.avoinVayla),
          hakemus.valinnanTila.map(_.toString).getOrElse(""),
          hakemus.valinnanAikaleima.getOrElse(""),
          hakemus.pisteet.map(_.toString()).getOrElse(""),
          hakemus.hyvaksymisenEhto match {
            case None => ""
            case Some(h) =>
              s"HyvaksymisenEhto(${toBooleanX(h.ehdollisestiHyvaksyttavissa)},${h.ehtoKoodi.getOrElse("")}," +
                s"${h.ehtoFI.getOrElse("")},${h.ehtoSV.getOrElse("")},${h.ehtoEN.getOrElse("")})"
          },
          hakemus.valintatapajononTyyppi.getOrElse(""),
          hakemus.valintatapajononNimi.getOrElse(""),
          hakemus.vastaanottotieto.map(_.toString).getOrElse(""),
          hakemus.ilmoittautumiset.mkString(","),
          hakemus.pohjakoulutus.mkString(","),
          toBooleanX(hakemus.julkaisulupa),
          hakemus.hKelpoisuus,
          hakemus.hKelpoisuusLahde.getOrElse(""),
          hakemus.hKelpoisuusMaksuvelvollisuus.getOrElse(""),
          hakemus.hakukohteenKoulutukset lift 0 match {
            case Some(k) =>
              s"Koulutus(${k.komoOid},${k.tkKoulutuskoodi},${k.kkKoulutusId.getOrElse("")}," +
                s"${k.koulutuksenAlkamisvuosi.getOrElse("")},${k.koulutuksenAlkamiskausi.getOrElse("")}," +
                s"${k.johtaaTutkintoon.getOrElse("")})"
            case None => ""
          },
          hakemus.hakukohteenKoulutukset lift 1 match {
            case Some(k) =>
              s"Koulutus(${k.komoOid},${k.tkKoulutuskoodi},${k.kkKoulutusId.getOrElse("")}," +
                s"${k.koulutuksenAlkamisvuosi.getOrElse("")},${k.koulutuksenAlkamiskausi.getOrElse("")}," +
                s"${k.johtaaTutkintoon.getOrElse("")})"
            case None => ""
          },
          hakemus.hakukohteenKoulutukset lift 2 match {
            case Some(k) =>
              s"Koulutus(${k.komoOid},${k.tkKoulutuskoodi},${k.kkKoulutusId.getOrElse("")}," +
                s"${k.koulutuksenAlkamisvuosi.getOrElse("")},${k.koulutuksenAlkamiskausi.getOrElse("")}," +
                s"${k.johtaaTutkintoon.getOrElse("")})"
            case None => ""
          },
          hakemus.hakukohteenKoulutukset lift 3 match {
            case Some(k) =>
              s"Koulutus(${k.komoOid},${k.tkKoulutuskoodi},${k.kkKoulutusId.getOrElse("")}," +
                s"${k.koulutuksenAlkamisvuosi.getOrElse("")},${k.koulutuksenAlkamiskausi.getOrElse("")}," +
                s"${k.johtaaTutkintoon.getOrElse("")})"
            case None => ""
          },
          hakemus.hakukohteenKoulutukset lift 4 match {
            case Some(k) =>
              s"Koulutus(${k.komoOid},${k.tkKoulutuskoodi},${k.kkKoulutusId.getOrElse("")}," +
                s"${k.koulutuksenAlkamisvuosi.getOrElse("")},${k.koulutuksenAlkamiskausi.getOrElse("")}," +
                s"${k.johtaaTutkintoon.getOrElse("")})"
            case None => ""
          },
          hakemus.hakukohteenKoulutukset lift 5 match {
            case Some(k) =>
              s"Koulutus(${k.komoOid},${k.tkKoulutuskoodi},${k.kkKoulutusId.getOrElse("")}," +
                s"${k.koulutuksenAlkamisvuosi.getOrElse("")},${k.koulutuksenAlkamiskausi.getOrElse("")}," +
                s"${k.johtaaTutkintoon.getOrElse("")})"
            case None => ""
          },
          hakemus.liitteet match {
            case None => ""
            case Some(l) =>
              l lift 0 match {
                case Some(j) =>
                  s"Liite(${j.hakuId},${j.hakuRyhmaId},${j.tila},${j.saapumisenTila},${j.nimi},${j.vastaanottaja})"
                case None => ""
              }
          },
          hakemus.liitteet match {
            case None => ""
            case Some(l) =>
              l lift 1 match {
                case Some(j) =>
                  s"Liite(${j.hakuId},${j.hakuRyhmaId},${j.tila},${j.saapumisenTila},${j.nimi},${j.vastaanottaja})"
                case None => ""
              }
          },
          hakemus.liitteet match {
            case None => ""
            case Some(l) =>
              l lift 2 match {
                case Some(j) =>
                  s"Liite(${j.hakuId},${j.hakuRyhmaId},${j.tila},${j.saapumisenTila},${j.nimi},${j.vastaanottaja})"
                case None => ""
              }
          },
          hakemus.liitteet match {
            case None => ""
            case Some(l) =>
              l lift 3 match {
                case Some(j) =>
                  s"Liite(${j.hakuId},${j.hakuRyhmaId},${j.tila},${j.saapumisenTila},${j.nimi},${j.vastaanottaja})"
                case None => ""
              }
          },
          hakemus.liitteet match {
            case None => ""
            case Some(l) =>
              l lift 4 match {
                case Some(j) =>
                  s"Liite(${j.hakuId},${j.hakuRyhmaId},${j.tila},${j.saapumisenTila},${j.nimi},${j.vastaanottaja})"
                case None => ""
              }
          },
          hakemus.liitteet match {
            case None => ""
            case Some(l) =>
              l lift 5 match {
                case Some(j) =>
                  s"Liite(${j.hakuId},${j.hakuRyhmaId},${j.tila},${j.saapumisenTila},${j.nimi},${j.vastaanottaja})"
                case None => ""
              }
          }
        ).zipWithIndex.toSet
        for (sarake <- rivi) yield StringCell(sarake._2, sarake._1)
      })
    )
    .zipWithIndex
    .toSet
    .map((rivi: (Set[Cell], Int)) => Row(rivi._2 + 1, rivi._1))

}
