package fi.vm.sade.hakurekisteri.kkhakija

import fi.vm.sade.hakurekisteri.rest.support.HakijatExcelWriter


object KkExcelUtil extends HakijatExcelWriter[Seq[Hakija]] {

  private val headers = Set("hetu", "oppijanumero", "sukunimi", "etunimet", "kutsumanimi", "lahiosoite", "postinumero",
    "postitoimipaikka", "maa", "kansalaisuus", "matkapuhelin", "puhelin", "sahkoposti", "kotikunta", "sukupuoli",
    "aidinkieli", "asiointikieli", "koulusivistyskieli", "koulutusmarkkinointilupa", "onYlioppilas")

  override def getHeaders: Set[Row] = Set(Row(0, headers.zipWithIndex.map(h => StringCell(h._2, h._1))))

  override def getRows(hakijat: Seq[Hakija]): Set[Row] = hakijat.flatMap((hakija) => hakija.hakemukset.flatMap(hakemus => hakemus.hakukohteenKoulutukset.map(koulutus => Set[Cell](
    StringCell(0, hakija.hetu),
    StringCell(1, hakija.oppijanumero),
    StringCell(2, hakija.sukunimi),
    StringCell(3, hakija.etunimet),
    StringCell(4, hakija.kutsumanimi),
    StringCell(5, hakija.lahiosoite),
    StringCell(6, hakija.postinumero),
    StringCell(7, hakija.postitoimipaikka),
    StringCell(8, hakija.maa),
    StringCell(9, hakija.kansalaisuus),
    StringCell(10, hakija.matkapuhelin.getOrElse("")),
    StringCell(12, hakija.puhelin.getOrElse("")),
    StringCell(13, hakija.sahkoposti.getOrElse("")),
    StringCell(14, hakija.kotikunta),
    StringCell(15, hakija.sukupuoli),
    StringCell(16, hakija.aidinkieli),
    StringCell(17, hakija.asiointikieli),
    StringCell(18, hakija.koulusivistyskieli),
    StringCell(19, toBooleanX(hakija.koulutusmarkkinointilupa)),
    StringCell(20, toBooleanX(hakija.onYlioppilas))
  )))).toSet.zipWithIndex.map((rowWithIndex) => Row(rowWithIndex._2 + 1, rowWithIndex._1))

}