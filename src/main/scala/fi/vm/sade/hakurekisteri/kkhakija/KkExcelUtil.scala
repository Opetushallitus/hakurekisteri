package fi.vm.sade.hakurekisteri.kkhakija

import fi.vm.sade.hakurekisteri.rest.support.HakijatExcelWriter


object KkExcelUtil extends HakijatExcelWriter[Seq[Hakija]] {

  private val headers = Set("hetu", "oppijanumero", "sukunimi", "etunimet", "kutsumanimi", "lahiosoite", "postinumero",
    "postitoimipaikka", "maa", "kansalaisuus", "matkapuhelin", "puhelin", "sahkoposti", "kotikunta", "sukupuoli",
    "aidinkieli", "asiointikieli", "koulusivistyskieli", "koulutusmarkkinointilupa", "onYlioppilas")

  override def getHeaders: Set[Row] = Set(Row(0, headers.zipWithIndex.map(h => StringCell(h._2, h._1))))

  override def getRows(hakijat: Seq[Hakija]): Set[Row] = {
    hakijat.flatMap((h) => h.hakemukset.map(ht => Set[Cell](
      StringCell(0, h.hetu),
      StringCell(1, h.oppijanumero),
      StringCell(2, h.sukunimi),
      StringCell(3, h.etunimet),
      StringCell(4, h.kutsumanimi),
      StringCell(5, h.lahiosoite),
      StringCell(6, h.postinumero),
      StringCell(7, h.postitoimipaikka),
      StringCell(8, h.maa),
      StringCell(9, h.kansalaisuus),
      StringCell(10, h.matkapuhelin.getOrElse("")),
      StringCell(12, h.puhelin.getOrElse("")),
      StringCell(13, h.sahkoposti.getOrElse("")),
      StringCell(14, h.kotikunta),
      StringCell(15, h.sukupuoli),
      StringCell(16, h.aidinkieli),
      StringCell(17, h.asiointikieli),
      StringCell(18, h.koulusivistyskieli),
      StringCell(19, toBooleanX(h.koulutusmarkkinointilupa)),
      StringCell(20, toBooleanX(h.onYlioppilas))
    ))).toSet.zipWithIndex.map((rowWithIndex) => Row(rowWithIndex._2 + 1, rowWithIndex._1))
  }

}