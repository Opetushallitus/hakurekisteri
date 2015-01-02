package fi.vm.sade.hakurekisteri.kkhakija

import fi.vm.sade.hakurekisteri.rest.support.{Cell, StringCell, Row, HakijatExcelWriter}


object KkExcelUtil extends HakijatExcelWriter[Seq[Hakija]] {

  private val headers = Seq(
    "Hetu", "Oppijanumero", "Sukunimi", "Etunimet", "Kutsumanimi", "Lahiosoite", "Postinumero",
    "Postitoimipaikka", "Maa", "Kansalaisuus", "Matkapuhelin", "Puhelin", "Sahkoposti", "Kotikunta", "Sukupuoli",
    "Aidinkieli", "Asiointikieli", "Koulusivistyskieli", "Koulutusmarkkinointilupa", "On ylioppilas",
    "Haku", "Hakuvuosi", "Hakukausi", "Hakemusnumero", "Organisaatio", "Hakukohde", "Hakukohteen kk-id", "Avoin vayla",
    "Valinnan tila", "Vastaanottotieto", "Ilmoittautumiset", "Pohjakoulutus", "Julkaisulupa", "Hakukelpoisuus",
    "Hakukelpoisuuden lahde", "Hakukohteen koulutukset"
  )

  override def getHeaders: Set[Row] = Set(Row(0, headers.zipWithIndex.toSet.map((h: (String, Int)) => StringCell(h._2, h._1))))
  
  override def getRows(hakijat: Seq[Hakija]): Set[Row] = hakijat.flatMap((hakija) => hakija.hakemukset.map(hakemus => {
    val rivi = Seq(
      hakija.hetu,
      hakija.oppijanumero,
      hakija.sukunimi,
      hakija.etunimet,
      hakija.kutsumanimi,
      hakija.lahiosoite,
      hakija.postinumero,
      hakija.postitoimipaikka,
      hakija.maa,
      hakija.kansalaisuus,
      hakija.matkapuhelin.getOrElse(""),
      hakija.puhelin.getOrElse(""),
      hakija.sahkoposti.getOrElse(""),
      hakija.kotikunta,
      hakija.sukupuoli,
      hakija.aidinkieli,
      hakija.asiointikieli,
      hakija.koulusivistyskieli,
      toBooleanX(hakija.koulutusmarkkinointilupa),
      toBooleanX(hakija.onYlioppilas),
      hakemus.haku,
      hakemus.hakuVuosi.toString,
      hakemus.hakuKausi,
      hakemus.hakemusnumero,
      hakemus.organisaatio,
      hakemus.hakukohde,
      hakemus.hakukohdeKkId.getOrElse(""),
      toBooleanX(hakemus.avoinVayla),
      hakemus.valinnanTila.map(_.toString).getOrElse(""),
      hakemus.vastaanottotieto.map(_.toString).getOrElse(""),
      hakemus.ilmoittautumiset.mkString(","),
      hakemus.pohjakoulutus.mkString(","),
      toBooleanX(hakemus.julkaisulupa),
      hakemus.hKelpoisuus,
      hakemus.hKelpoisuusLahde.getOrElse(""),
      hakemus.hakukohteenKoulutukset.map(k => s"Koulutus(${k.komoOid},${k.tkKoulutuskoodi},${k.kkKoulutusId.getOrElse("")})").mkString(",")).zipWithIndex.toSet

    for (sarake <- rivi) yield StringCell(sarake._2, sarake._1)
  })).zipWithIndex.toSet.map((rivi: (Set[Cell], Int)) => Row(rivi._2 + 1, rivi._1))

}