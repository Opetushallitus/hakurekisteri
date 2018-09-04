package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.hakurekisteri.hakija.representation.JSONHakijat
import fi.vm.sade.hakurekisteri.rest.support.{Cell, HakijatExcelWriter, Row, StringCell}


object ExcelUtilV2 extends HakijatExcelWriter[JSONHakijat] {

  private val headers = Seq(
    "Hetu", "Oppijanumero", "Sukunimi", "Etunimet", "Kutsumanimi", "Lahiosoite", "Postinumero", "Postitoimipaikka", "Maa",
    "Kansalaisuus", "Matkapuhelin", "Muupuhelin", "Sahkoposti", "Kotikunta", "Sukupuoli", "Aidinkieli", "Huoltajan nimi",
    "Huoltajan puhelinnumero", "Huoltajan sähköposti", "Koulutusmarkkinointilupa", "Kiinnostunut oppisopimuskoulutuksesta",
    "Vuosi", "Kausi", "Hakemusnumero", "Lahtokoulu", "Lahtokoulunnimi", "Luokka", "Luokkataso", "Pohjakoulutus",
    "Todistusvuosi", "Julkaisulupa", "Yhteisetaineet", "Lukiontasapisteet", "Yleinenkoulumenestys", "Lisapistekoulutus",
    "Painotettavataineet", "Hakujno", "Oppilaitos", "Opetuspiste", "Opetuspisteennimi", "Koulutus",
    "Harkinnanvaraisuuden peruste", "Urheilijan ammatillinen koulutus", "Yhteispisteet", "Valinta", "Vastaanotto",
    "Lasnaolo", "Terveys", "Aiempiperuminen", "Kaksoistutkinto"
  )

  def getLisakysymysIdsAndQuestionsInOrder(hakijat: JSONHakijat) = {
    val raw: Seq[(String, String)] = hakijat.hakijat.flatMap(_.lisakysymykset.map(lk => lk.kysymysid -> lk.kysymysteksti))
      .distinct.sortBy(_._2)
    raw.map(t => lisakysymysHeader(t._1, t._2))
  }

  case class lisakysymysHeader(id: String, header: String)

  override def getHeaders(hakijat: JSONHakijat): Set[Row] = {
    val lisakysymysQuestions = getLisakysymysIdsAndQuestionsInOrder(hakijat).map(_.header)
    val headersWithLisakysymys = headers ++ lisakysymysQuestions
    Set(Row(0, headersWithLisakysymys.zipWithIndex.toSet.map((header: (String, Int)) => StringCell(header._2, header._1))))
  }

  override def getRows(hakijat: JSONHakijat): Set[Row] = hakijat.hakijat.flatMap((h) => h.hakemus.hakutoiveet.map(ht => {
    val mainAnswers = Seq(
      h.hetu,
      h.oppijanumero,
      h.sukunimi,
      h.etunimet,
      h.kutsumanimi.getOrElse(""),
      h.lahiosoite,
      h.postinumero,
      h.postitoimipaikka,
      h.maa,
      h.kansalaisuus.mkString(","),
      h.matkapuhelin.getOrElse(""),
      h.muupuhelin.getOrElse(""),
      h.sahkoposti.getOrElse(""),
      h.kotikunta.getOrElse(""),
      h.sukupuoli,
      h.aidinkieli,
      h.huoltajannimi.getOrElse(""),
      h.huoltajanpuhelinnumero.getOrElse(""),
      h.huoltajansahkoposti.getOrElse(""),
      toBooleanX(h.koulutusmarkkinointilupa),
      toBooleanX(h.kiinnostunutoppisopimuksesta),
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
      toBooleanX(ht.kaksoistutkinto))

    def getLisakysymysAnswer(lisakysymykset: Seq[Lisakysymys], id: String): String = {
      val answers = for {
        lk <- lisakysymykset.filter(_.kysymysid == id)
      } yield for {
        answer <- lk.vastaukset
      } yield answer.vastausteksti
      val list: Seq[String] = answers.flatten
      list.mkString(", ")
    }

    val lisakysymysIds = getLisakysymysIdsAndQuestionsInOrder(hakijat)

    val allAnswers = mainAnswers ++ lisakysymysIds.map(q => getLisakysymysAnswer(h.lisakysymykset, q.id))

    val rivi = allAnswers.zipWithIndex.toSet

    for (sarake <- rivi) yield StringCell(sarake._2, sarake._1)
  })).zipWithIndex.toSet.map((rivi: (Set[Cell], Int)) => Row(rivi._2 + 1, rivi._1))

}
