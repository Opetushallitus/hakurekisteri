package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.hakurekisteri.hakija.representation.JSONHakijatV6
import fi.vm.sade.hakurekisteri.rest.support._

object ExcelUtilV6 extends HakijatExcelWriterV3[JSONHakijatV6] {

  private val headers = Seq(
    "Hetu",
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
    "Muupuhelin",
    "Sahkoposti",
    "Kotikunta",
    "Sukupuoli",
    "Aidinkieli",
    "Opetuskieli",
    "Huoltaja 1 nimi",
    "Huoltaja 1 puh",
    "Huoltaja 1 email",
    "Huoltaja 2 nimi",
    "Huoltaja 2 puh",
    "Huoltaja 2 email",
    "Koulutusmarkkinointilupa",
    "Kiinnostunut oppisopimuskoulutuksesta",
    "Oppivelvollisuus voimassa asti",
    "Oikeus maksuttomaan koulutukseen voimassa asti",
    "Vuosi",
    "Kausi",
    "Hakemusnumero",
    "Hakemus jätetty",
    "Hakemusta viimeksi muokattu",
    "Lahtokoulu",
    "Lahtokoulunnimi",
    "Luokka",
    "Luokkataso",
    "Pohjakoulutus",
    "Todistusvuosi", /*"Minkä muun koulutuksen/opintoja olet suorittanut?",*/ "Julkaisulupa",
    "Yhteisetaineet",
    "Lukiontasapisteet",
    "Yleinenkoulumenestys",
    "Lisapistekoulutus",
    "Painotettavataineet",
    "Keskiarvo valintalaskennasta",
    "Hakujno",
    "Oppilaitos",
    "Opetuspiste",
    "Opetuspisteennimi",
    "Koulutus",
    "HakukohdeOid",
    "Harkinnanvaraisuuden peruste",
    "Urheilijan ammatillinen koulutus",
    "Yhteispisteet",
    "Valinta",
    "Vastaanotto",
    "Lasnaolo",
    "Terveys",
    "Aiempiperuminen",
    "Kaksoistutkinto", /*, "Yleinenkielitutkinto", "Valtionhallinnonkielitutkinto"*/
    "Urheilija-peruskoulu",
    "Urheilija-keskiarvo",
    "Urheilija-tamakausi",
    "Urheilija.viimekausi",
    "Urheilija-toissakausi",
    "Urheilija-sivulaji",
    "Urheilija-valmennusryhma-seurajoukkue",
    "Urheilija-valmennusryhma-piirijoukkue",
    "Urheilija-valmennusryhma-maajoukkue",
    "Urheilija-valmentaja-nimi",
    "Urheilija-valmentaja-email",
    "Urheilija-valmentaja-puh",
    "Urheilija-laji",
    "Urheilija-liitto",
    "Urheilija-seura"
  )

  private def getLisakysymysIdsAndQuestionsInOrder(
    hakijat: JSONHakijatV6,
    hakukohdeOid: String
  ): Seq[lisakysymysHeader] = {
    val raw: Seq[(String, String)] = hakijat.hakijat
      .flatMap(
        _.lisakysymykset
          .filter(lk => lk.hakukohdeOids.isEmpty || lk.hakukohdeOids.contains(hakukohdeOid))
          .map(lk => lk.kysymysid -> lk.kysymysteksti)
      )
      .distinct
      .sortBy(_._2)
    raw.map(t => lisakysymysHeader(t._1, t._2))
  }

  case class lisakysymysHeader(id: String, header: String)

  def kieleistys(totuusArvo: Option[String]): String = totuusArvo match {
    case Some("true")  => "Kyllä"
    case Some("false") => "Ei"
    case _             => ""
  }

  override def getRows(hakijat: JSONHakijatV6): Set[Row] = {
    val hakutoiveet = hakijat.hakijat.flatMap((h) => h.hakemus.hakutoiveet)

    val allLisakysymysHeaders: Seq[lisakysymysHeader] = hakutoiveet
      .flatMap(ht => getLisakysymysIdsAndQuestionsInOrder(hakijat, ht.hakukohdeOid))
      .distinct
      .sortBy(_.header)

    val rows: Set[Row] = hakijat.hakijat
      .flatMap((h) =>
        h.hakemus.hakutoiveet.map(ht => {
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
            h.kansalaisuudet.mkString(", "),
            h.matkapuhelin.getOrElse(""),
            h.muupuhelin.getOrElse(""),
            h.sahkoposti.getOrElse(""),
            h.kotikunta.getOrElse(""),
            h.sukupuoli,
            h.aidinkieli,
            h.opetuskieli,
            h.huoltaja1.flatMap(_.nimi).getOrElse(""),
            h.huoltaja1.flatMap(_.puhelinnumero).getOrElse(""),
            h.huoltaja1.flatMap(_.sahkoposti).getOrElse(""),
            h.huoltaja2.flatMap(_.nimi).getOrElse(""),
            h.huoltaja2.flatMap(_.puhelinnumero).getOrElse(""),
            h.huoltaja2.flatMap(_.sahkoposti).getOrElse(""),
            toBooleanX(h.koulutusmarkkinointilupa),
            toBooleanX(h.kiinnostunutoppisopimuksesta),
            h.oppivelvollisuusVoimassaAsti.getOrElse(""),
            h.oikeusMaksuttomaanKoulutukseenVoimassaAsti.getOrElse(""),
            h.hakemus.vuosi,
            h.hakemus.kausi,
            h.hakemus.hakemusnumero,
            h.hakemus.hakemuksenJattopaiva,
            h.hakemus.hakemuksenMuokkauspaiva,
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
            ht.keskiarvo.getOrElse(""),
            ht.hakujno.toString,
            ht.oppilaitos,
            ht.opetuspiste.getOrElse(""),
            ht.opetuspisteennimi.getOrElse(""),
            ht.koulutus,
            ht.hakukohdeOid,
            ht.harkinnanvaraisuusperuste.getOrElse(""),
            if (ht.urheilijanammatillinenkoulutus.getOrElse(false)) "Kyllä" else "",
            ht.yhteispisteet.getOrElse(zero).toString(),
            ht.valinta.getOrElse(""),
            ht.vastaanotto.getOrElse(""),
            ht.lasnaolo.getOrElse(""),
            toBooleanX(ht.terveys),
            toBooleanX(ht.aiempiperuminen),
            toBooleanX(ht.kaksoistutkinto),
            h.hakemus.urheilijanLisakysymykset.flatMap(_.peruskoulu).getOrElse(""),
            h.hakemus.urheilijanLisakysymykset.flatMap(_.keskiarvo).getOrElse(""),
            h.hakemus.urheilijanLisakysymykset.flatMap(_.tamakausi).getOrElse(""),
            h.hakemus.urheilijanLisakysymykset.flatMap(_.viimekausi).getOrElse(""),
            h.hakemus.urheilijanLisakysymykset.flatMap(_.toissakausi).getOrElse(""),
            h.hakemus.urheilijanLisakysymykset.flatMap(_.sivulaji).getOrElse(""),
            h.hakemus.urheilijanLisakysymykset.flatMap(_.valmennusryhma_seurajoukkue).getOrElse(""),
            h.hakemus.urheilijanLisakysymykset.flatMap(_.valmennusryhma_piirijoukkue).getOrElse(""),
            h.hakemus.urheilijanLisakysymykset.flatMap(_.valmennusryhma_maajoukkue).getOrElse(""),
            h.hakemus.urheilijanLisakysymykset.flatMap(_.valmentaja_nimi).getOrElse(""),
            h.hakemus.urheilijanLisakysymykset.flatMap(_.valmentaja_email).getOrElse(""),
            h.hakemus.urheilijanLisakysymykset.flatMap(_.valmentaja_puh).getOrElse(""),
            h.hakemus.urheilijanLisakysymykset.flatMap(_.laji).getOrElse(""),
            h.hakemus.urheilijanLisakysymykset.flatMap(_.liitto).getOrElse(""),
            h.hakemus.urheilijanLisakysymykset.flatMap(_.seura).getOrElse("")
          )

          def getLisakysymysAnswer(lisakysymykset: Seq[Lisakysymys], id: String): String = {
            val answers: Seq[Seq[String]] = for {
              lk <- lisakysymykset.filter(_.kysymysid == id)
            } yield for {
              answer <- lk.vastaukset
            } yield answer.vastausteksti
            val list: Seq[String] = answers.flatten
            list match {
              case Nil => ""
              case l   => list.mkString(", ")
            }
          }

          val allAnswers: Seq[String] = mainAnswers ++ allLisakysymysHeaders.map(q =>
            getLisakysymysAnswer(h.lisakysymykset, q.id)
          )

          val rivi: Set[(String, Int)] = allAnswers.zipWithIndex.toSet

          for (sarake <- rivi) yield StringCell(sarake._2, sarake._1)
        })
      )
      .zipWithIndex
      .toSet
      .map((rivi: (Set[Cell], Int)) => Row(rivi._2 + 1, rivi._1))

    val allHeaders: Set[Row] = {
      val lisakysymysQuestions = allLisakysymysHeaders.map(_.header)
      val headersWithLisakysymys = headers ++ lisakysymysQuestions
      Set(
        Row(
          0,
          headersWithLisakysymys.zipWithIndex.toSet.map((header: (String, Int)) =>
            StringCell(header._2, header._1)
          )
        )
      )
    }

    allHeaders ++ rows
  }

}
