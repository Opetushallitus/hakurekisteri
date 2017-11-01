package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.hakurekisteri.hakija.representation.JSONHakijat
import fi.vm.sade.hakurekisteri.rest.support._


object ExcelUtilV4 extends HakijatExcelWriterV3[JSONHakijat] {

  private val headers = Seq(
    "Hetu", "Oppijanumero", "Sukunimi", "Etunimet", "Kutsumanimi", "Lahiosoite", "Postinumero", "Postitoimipaikka", "Maa",
    "Kansalaisuus", "Matkapuhelin", "Muupuhelin", "Sahkoposti", "Kotikunta", "Sukupuoli", "Aidinkieli", "Huoltajan nimi",
    "Huoltajan puhelinnumero", "Huoltajan sähköposti", "Koulutusmarkkinointilupa", "Kiinnostunut oppisopimuskoulutuksesta",
    "Vuosi", "Kausi", "Hakemusnumero", "Lahtokoulu", "Lahtokoulunnimi", "Luokka", "Luokkataso", "Pohjakoulutus",
    "Todistusvuosi", "Minkä muun koulutuksen/opintoja olet suorittanut?", "Julkaisulupa", "Yhteisetaineet", "Lukiontasapisteet",
    "Yleinenkoulumenestys", "Kymppiluokka", "Kymppiluokka vuosi", "Kansanopisto", "Kansanopisto vuosi", "LUVA", "LUVA vuosi",
    "TELMA", "TELMA vuosi", "VALMA", "VALMA vuosi",
    "Painotettavataineet", "Hakujno", "Oppilaitos", "Opetuspiste", "Opetuspisteennimi", "Koulutus", "HakukohdeOid",
    "Harkinnanvaraisuuden peruste", "Yhteispisteet", "Valinta", "Vastaanotto",
    "Lasnaolo", "Terveys", "Aiempiperuminen", "Kaksoistutkinto"
  )

  override def getRows(hakijat: JSONHakijat): Set[Row] = {
    val hakutoiveet = hakijat.hakijat.flatMap((h) => h.hakemus.hakutoiveet)

    val allLisakysymysHeaders: Seq[lisakysymysHeader] = hakutoiveet
      .flatMap(ht => getLisakysymysIdsAndQuestionsInOrder(hakijat, ht.hakukohdeOid)).distinct.sortBy(_.header)

    val rows: Set[Row] = hakijat.hakijat.flatMap((h) => h.hakemus.hakutoiveet.map(ht => {
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
        h.kansalaisuus,
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
        h.hakemus.muukoulutus.getOrElse(""),
        toBooleanX(h.hakemus.julkaisulupa),
        h.hakemus.yhteisetaineet.getOrElse(zero).toString(),
        h.hakemus.lukiontasapisteet.getOrElse(zero).toString(),
        h.hakemus.yleinenkoulumenestys.getOrElse(zero).toString(),
        h.hakemus.lisapistekoulutus.find(_ == "LISAKOULUTUS_KYMPPI").getOrElse(""),
        h.hakemus.lisapistevuosi.getOrElse("KYMPPI_PAATTOTODISTUSVUOSI", ""),
        h.hakemus.lisapistekoulutus.find(_ == "LISAKOULUTUS_KANSANOPISTO").getOrElse(""),
        h.hakemus.lisapistevuosi.getOrElse("KANSANOPISTO_PAATTOTODISTUSVUOSI", ""),
        h.hakemus.lisapistekoulutus.find(_ == "LISAKOULUTUS_MAAHANMUUTTO_LUKIO").getOrElse(""),
        h.hakemus.lisapistevuosi.getOrElse("LUVA_PAATTOTODISTUSVUOSI", ""),
        h.hakemus.lisapistekoulutus.find(_ == "LISAKOULUTUS_TELMA").getOrElse(""),
        h.hakemus.lisapistevuosi.getOrElse("TELMA_PAATTOTODISTUSVUOSI", ""),
        h.hakemus.lisapistekoulutus.find(_ == "LISAKOULUTUS_VALMA").getOrElse(""),
        h.hakemus.lisapistevuosi.getOrElse("VALMA_PAATTOTODISTUSVUOSI", ""),
        h.hakemus.painotettavataineet.getOrElse(zero).toString(),
        ht.hakujno.toString,
        ht.oppilaitos,
        ht.opetuspiste.getOrElse(""),
        ht.opetuspisteennimi.getOrElse(""),
        ht.koulutus,
        ht.hakukohdeOid,
        ht.harkinnanvaraisuusperuste.getOrElse(""),
        ht.yhteispisteet.getOrElse(zero).toString(),
        ht.valinta.getOrElse(""),
        ht.vastaanotto.getOrElse(""),
        ht.lasnaolo.getOrElse(""),
        toBooleanX(ht.terveys),
        toBooleanX(ht.aiempiperuminen),
        toBooleanX(ht.kaksoistutkinto))

      def getLisakysymysAnswer(lisakysymykset: Seq[Lisakysymys], id: String): String = {
        val answers: Seq[Seq[String]] = for {
          lk <- lisakysymykset.filter(_.kysymysid == id)
        } yield for {
          answer <- lk.vastaukset
        } yield answer.vastausteksti
        val list: Seq[String] = answers.flatten
        list match {
          case Nil => ""
          case l => list.mkString(", ")
        }
      }

      val allAnswers: Seq[String] = mainAnswers ++ allLisakysymysHeaders.map(q => getLisakysymysAnswer(h.lisakysymykset, q.id))

      val rivi: Set[(String, Int)] = allAnswers.zipWithIndex.toSet

      for (sarake <- rivi) yield StringCell(sarake._2, sarake._1)
    })).zipWithIndex.toSet.map((rivi: (Set[Cell], Int)) => Row(rivi._2 + 1, rivi._1))

    val allHeaders: Set[Row] = {
      val lisakysymysQuestions = allLisakysymysHeaders.map(_.header)
      val headersWithLisakysymys = headers ++ lisakysymysQuestions
      Set(Row(0, headersWithLisakysymys.zipWithIndex.toSet.map((header: (String, Int)) => StringCell(header._2, header._1))))
    }

    allHeaders ++ rows
  }

}