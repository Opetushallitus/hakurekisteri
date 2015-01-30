package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.hakurekisteri.rest.support.{Cell, StringCell, Row, HakijatExcelWriter}


object ExcelUtil extends HakijatExcelWriter[XMLHakijat] {
  
  private val headers = Seq(
    "Hetu", "Oppijanumero", "Sukunimi", "Etunimet", "Kutsumanimi", "Lahiosoite", "Postinumero", "Maa", "Kansalaisuus", 
    "Matkapuhelin", "Muupuhelin", "Sahkoposti", "Kotikunta", "Sukupuoli", "Aidinkieli", "Koulutusmarkkinointilupa", 
    "Vuosi", "Kausi", "Hakemusnumero", "Lahtokoulu", "Lahtokoulunnimi", "Luokka", "Luokkataso", "Pohjakoulutus", 
    "Todistusvuosi", "Julkaisulupa", "Yhteisetaineet", "Lukiontasapisteet", "Yleinenkoulumenestys", "Lisapistekoulutus", 
    "Painotettavataineet", "Hakujno", "Oppilaitos", "Opetuspiste", "Opetuspisteennimi", "Koulutus", 
    "Harkinnanvaraisuuden peruste", "Urheilijan ammatillinen koulutus", "Yhteispisteet", "Valinta", "Vastaanotto", 
    "Lasnaolo", "Terveys", "Aiempiperuminen", "Kaksoistutkinto"
  )

  override def getHeaders: Set[Row] = Set(Row(0, headers.zipWithIndex.toSet.map((header: (String, Int)) => StringCell(header._2, header._1))))

  override def getRows(hakijat: XMLHakijat): Set[Row] = hakijat.hakijat.flatMap((h) => h.hakemus.hakutoiveet.map(ht => {
    val rivi = Seq(
      h.hetu,
      h.oppijanumero,
      h.sukunimi,
      h.etunimet,
      h.kutsumanimi.getOrElse(""),
      h.lahiosoite,
      h.postinumero,
      h.maa,
      h.kansalaisuus,
      h.matkapuhelin.getOrElse(""),
      h.muupuhelin.getOrElse(""),
      h.sahkoposti.getOrElse(""),
      h.kotikunta.getOrElse(""),
      h.sukupuoli,
      h.aidinkieli,
      toBooleanX(h.koulutusmarkkinointilupa),
      h.hakemus.vuosi,
      h.hakemus.kausi,
      h.hakemus.hakemusnumero,
      h.hakemus.lahtokoulu.getOrElse(""),
      h.hakemus.lahtokoulunnimi.getOrElse(""),
      h.hakemus.luokka.getOrElse(""),
      h.hakemus.luokkataso.getOrElse(""),
      h.hakemus.pohjakoulutus,
      h.hakemus.todistusvuosi.getOrElse(""),
      toBooleanX(h.hakemus.julkaisulupa),
      h.hakemus.yhteisetaineet.getOrElse(zero).toString(),
      h.hakemus.lukiontasapisteet.getOrElse(zero).toString(),
      h.hakemus.yleinenkoulumenestys.getOrElse(zero).toString(),
      h.hakemus.lisapistekoulutus.getOrElse(""),
      h.hakemus.painotettavataineet.getOrElse(zero).toString(),
      ht.hakujno.toString,
      ht.oppilaitos,
      ht.opetuspiste.getOrElse(""),
      ht.opetuspisteennimi.getOrElse(""),
      ht.koulutus,
      ht.harkinnanvaraisuusperuste.getOrElse(""),
      ht.urheilijanammatillinenkoulutus.getOrElse(false).toString,
      ht.yhteispisteet.getOrElse(zero).toString(),
      ht.valinta.getOrElse(""),
      ht.vastaanotto.getOrElse(""),
      ht.lasnaolo.getOrElse(""),
      toBooleanX(ht.terveys),
      toBooleanX(ht.aiempiperuminen),
      toBooleanX(ht.kaksoistutkinto)).zipWithIndex.toSet

    for (sarake <- rivi) yield StringCell(sarake._2, sarake._1)
  })).zipWithIndex.toSet.map((rivi: (Set[Cell], Int)) => Row(rivi._2 + 1, rivi._1))

}
