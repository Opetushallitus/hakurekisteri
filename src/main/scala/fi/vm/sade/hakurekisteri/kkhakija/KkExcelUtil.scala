package fi.vm.sade.hakurekisteri.kkhakija

import fi.vm.sade.hakurekisteri.rest.support.HakijatExcelWriter


object KkExcelUtil extends HakijatExcelWriter[Seq[Hakija]] {

  private val headers = Set("hetu", "oppijanumero", "sukunimi", "etunimet", "kutsumanimi", "lahiosoite", "postinumero",
    "postitoimipaikka", "maa", "kansalaisuus", "matkapuhelin", "puhelin", "sahkoposti", "kotikunta", "sukupuoli",
    "aidinkieli", "asiointikieli", "koulusivistyskieli", "koulutusmarkkinointilupa", "onYlioppilas",
    "haku", "hakuVuosi", "hakuKausi", "hakemusnumero", "organisaatio", "hakukohde", "hakukohdeKkId", "avoinVayla",
    "valinnanTila", "vastaanottotieto", "ilmoittautumiset", "pohjakoulutus", "julkaisulupa", "hKelpoisuus",
    "hKelpoisuusLahde", "hakukohteenKoulutukset")

  override def getHeaders: Set[Row] = Set(Row(0, headers.zipWithIndex.map(h => StringCell(h._2, h._1))))
  
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
      hakemus.hakukohteenKoulutukset.mkString(",")).zipWithIndex.toSet

    for (sarake <- rivi) yield StringCell(sarake._2, sarake._1)
  })).zipWithIndex.toSet.map((rivi: (Set[Cell], Int)) => Row(rivi._2 + 1, rivi._1))

}