package fi.vm.sade.hakurekisteri.hakija.representation

import java.text.SimpleDateFormat

import fi.vm.sade.hakurekisteri.hakija.{Hakija, Lisakysymys}
import fi.vm.sade.hakurekisteri.tools.RicherString

import scala.util.Try

object JSONHakija {
  import RicherString._

  private[hakija] def apply(hakija: Hakija, hakemus: XMLHakemus): JSONHakija =
    JSONHakija(
      hetu = hetu(hakija.henkilo.hetu, hakija.henkilo.syntymaaika),
      oppijanumero = hakija.henkilo.oppijanumero,
      sukunimi = hakija.henkilo.sukunimi,
      etunimet = hakija.henkilo.etunimet,
      kutsumanimi = hakija.henkilo.kutsumanimi.blankOption,
      lahiosoite = hakija.henkilo.lahiosoite,
      postinumero = hakija.henkilo.postinumero,
      postitoimipaikka = hakija.henkilo.postitoimipaikka,
      maa = hakija.henkilo.maa,
      kansalaisuus = hakija.henkilo.kansalaisuus.getOrElse(""),
      matkapuhelin = hakija.henkilo.matkapuhelin.blankOption,
      muupuhelin = hakija.henkilo.puhelin.blankOption,
      sahkoposti = hakija.henkilo.sahkoposti.blankOption,
      kotikunta = hakija.henkilo.kotikunta.blankOption,
      sukupuoli = Hakija.resolveSukupuoli(hakija),
      aidinkieli = hakija.henkilo.aidinkieli,
      opetuskieli = hakija.henkilo.opetuskieli,
      koulutusmarkkinointilupa = hakija.henkilo.markkinointilupa.getOrElse(false),
      kiinnostunutoppisopimuksesta = hakija.henkilo.kiinnostunutoppisopimuksesta.getOrElse(false),
      huoltajannimi = hakija.henkilo.huoltajannimi.blankOption,
      huoltajanpuhelinnumero = hakija.henkilo.huoltajanpuhelinnumero.blankOption,
      huoltajansahkoposti = hakija.henkilo.huoltajansahkoposti.blankOption,
      hakemus = hakemus,
      lisakysymykset = hakija.henkilo.lisakysymykset
    )

  def hetu(hetu: String, syntymaaika: String): String = hetu match {
    case "" =>
      Try(
        new SimpleDateFormat("ddMMyyyy").format(
          new SimpleDateFormat("dd.MM.yyyy").parse(syntymaaika)
        )
      ).getOrElse("")
    case _ => hetu
  }

}

object JSONHakijaV4 {
  import RicherString._

  private[hakija] def apply(hakija: Hakija, hakemus: XMLHakemus): JSONHakijaV4 =
    JSONHakijaV4(
      hetu = hetu(hakija.henkilo.hetu, hakija.henkilo.syntymaaika),
      oppijanumero = hakija.henkilo.oppijanumero,
      sukunimi = hakija.henkilo.sukunimi,
      etunimet = hakija.henkilo.etunimet,
      kutsumanimi = hakija.henkilo.kutsumanimi.blankOption,
      lahiosoite = hakija.henkilo.lahiosoite,
      postinumero = hakija.henkilo.postinumero,
      postitoimipaikka = hakija.henkilo.postitoimipaikka,
      maa = hakija.henkilo.maa,
      kansalaisuudet = hakija.henkilo.kansalaisuudet.getOrElse(List.empty),
      matkapuhelin = hakija.henkilo.matkapuhelin.blankOption,
      muupuhelin = hakija.henkilo.puhelin.blankOption,
      sahkoposti = hakija.henkilo.sahkoposti.blankOption,
      kotikunta = hakija.henkilo.kotikunta.blankOption,
      sukupuoli = Hakija.resolveSukupuoli(hakija),
      aidinkieli = hakija.henkilo.aidinkieli,
      opetuskieli = hakija.henkilo.opetuskieli,
      koulutusmarkkinointilupa = hakija.henkilo.markkinointilupa.getOrElse(false),
      kiinnostunutoppisopimuksesta = hakija.henkilo.kiinnostunutoppisopimuksesta.getOrElse(false),
      huoltajannimi = hakija.henkilo.huoltajannimi.blankOption,
      huoltajanpuhelinnumero = hakija.henkilo.huoltajanpuhelinnumero.blankOption,
      huoltajansahkoposti = hakija.henkilo.huoltajansahkoposti.blankOption,
      hakemus = hakemus,
      lisakysymykset = hakija.henkilo.lisakysymykset
    )

  def hetu(hetu: String, syntymaaika: String): String = hetu match {
    case "" =>
      Try(
        new SimpleDateFormat("ddMMyyyy").format(
          new SimpleDateFormat("dd.MM.yyyy").parse(syntymaaika)
        )
      ).getOrElse("")
    case _ => hetu
  }
}

object JSONHakijaV5 {
  import RicherString._

  private[hakija] def apply(hakija: Hakija, hakemus: XMLHakemus): JSONHakijaV5 =
    JSONHakijaV5(
      hetu = hetu(hakija.henkilo.hetu, hakija.henkilo.syntymaaika),
      oppijanumero = hakija.henkilo.oppijanumero,
      sukunimi = hakija.henkilo.sukunimi,
      etunimet = hakija.henkilo.etunimet,
      kutsumanimi = hakija.henkilo.kutsumanimi.blankOption,
      lahiosoite = hakija.henkilo.lahiosoite,
      postinumero = hakija.henkilo.postinumero,
      postitoimipaikka = hakija.henkilo.postitoimipaikka,
      maa = hakija.henkilo.maa,
      kansalaisuudet = hakija.henkilo.kansalaisuudet.getOrElse(List.empty),
      matkapuhelin = hakija.henkilo.matkapuhelin.blankOption,
      muupuhelin = hakija.henkilo.puhelin.blankOption,
      sahkoposti = hakija.henkilo.sahkoposti.blankOption,
      kotikunta = hakija.henkilo.kotikunta.blankOption,
      sukupuoli = Hakija.resolveSukupuoli(hakija),
      aidinkieli = hakija.henkilo.aidinkieli,
      opetuskieli = hakija.henkilo.opetuskieli,
      koulutusmarkkinointilupa = hakija.henkilo.markkinointilupa.getOrElse(false),
      kiinnostunutoppisopimuksesta = hakija.henkilo.kiinnostunutoppisopimuksesta.getOrElse(false),
      huoltajannimi = hakija.henkilo.huoltajannimi.blankOption,
      huoltajanpuhelinnumero = hakija.henkilo.huoltajanpuhelinnumero.blankOption,
      huoltajansahkoposti = hakija.henkilo.huoltajansahkoposti.blankOption,
      hakemus = hakemus,
      oppivelvollisuusVoimassaAsti = hakija.henkilo.oppivelvollisuusVoimassaAsti,
      oikeusMaksuttomaanKoulutukseenVoimassaAsti =
        hakija.henkilo.oikeusMaksuttomaanKoulutukseenVoimassaAsti,
      lisakysymykset = hakija.henkilo.lisakysymykset
    )

  def hetu(hetu: String, syntymaaika: String): String = hetu match {
    case "" =>
      Try(
        new SimpleDateFormat("ddMMyyyy").format(
          new SimpleDateFormat("dd.MM.yyyy").parse(syntymaaika)
        )
      ).getOrElse("")
    case _ => hetu
  }
}

case class JSONHakija(
  hetu: String,
  oppijanumero: String,
  sukunimi: String,
  etunimet: String,
  kutsumanimi: Option[String],
  lahiosoite: String,
  postinumero: String,
  postitoimipaikka: String,
  maa: String,
  kansalaisuus: String,
  matkapuhelin: Option[String],
  muupuhelin: Option[String],
  sahkoposti: Option[String],
  kotikunta: Option[String],
  sukupuoli: String,
  aidinkieli: String,
  opetuskieli: String,
  koulutusmarkkinointilupa: Boolean,
  kiinnostunutoppisopimuksesta: Boolean,
  huoltajannimi: Option[String],
  huoltajanpuhelinnumero: Option[String],
  huoltajansahkoposti: Option[String],
  hakemus: XMLHakemus,
  lisakysymykset: Seq[Lisakysymys]
)

case class JSONHakijaV4(
  hetu: String,
  oppijanumero: String,
  sukunimi: String,
  etunimet: String,
  kutsumanimi: Option[String],
  lahiosoite: String,
  postinumero: String,
  postitoimipaikka: String,
  maa: String,
  kansalaisuudet: List[String],
  matkapuhelin: Option[String],
  muupuhelin: Option[String],
  sahkoposti: Option[String],
  kotikunta: Option[String],
  sukupuoli: String,
  aidinkieli: String,
  opetuskieli: String,
  koulutusmarkkinointilupa: Boolean,
  kiinnostunutoppisopimuksesta: Boolean,
  huoltajannimi: Option[String],
  huoltajanpuhelinnumero: Option[String],
  huoltajansahkoposti: Option[String],
  hakemus: XMLHakemus,
  lisakysymykset: Seq[Lisakysymys]
)

case class JSONHakijaV5(
  hetu: String,
  oppijanumero: String,
  sukunimi: String,
  etunimet: String,
  kutsumanimi: Option[String],
  lahiosoite: String,
  postinumero: String,
  postitoimipaikka: String,
  maa: String,
  kansalaisuudet: List[String],
  matkapuhelin: Option[String],
  muupuhelin: Option[String],
  sahkoposti: Option[String],
  kotikunta: Option[String],
  sukupuoli: String,
  aidinkieli: String,
  opetuskieli: String,
  koulutusmarkkinointilupa: Boolean,
  kiinnostunutoppisopimuksesta: Boolean,
  huoltajannimi: Option[String],
  huoltajanpuhelinnumero: Option[String],
  huoltajansahkoposti: Option[String],
  hakemus: XMLHakemus,
  oppivelvollisuusVoimassaAsti: Option[String],
  oikeusMaksuttomaanKoulutukseenVoimassaAsti: Option[String],
  lisakysymykset: Seq[Lisakysymys]
)

case class JSONHakijaV6(
                         hetu: String,
                         oppijanumero: String,
                         sukunimi: String,
                         etunimet: String,
                         kutsumanimi: Option[String],
                         lahiosoite: String,
                         postinumero: String,
                         postitoimipaikka: String,
                         maa: String,
                         kansalaisuudet: List[String],
                         matkapuhelin: Option[String],
                         muupuhelin: Option[String],
                         sahkoposti: Option[String],
                         kotikunta: Option[String],
                         sukupuoli: String,
                         aidinkieli: String,
                         opetuskieli: String,
                         koulutusmarkkinointilupa: Boolean,
                         kiinnostunutoppisopimuksesta: Boolean,
                         huoltajannimi: Option[String],
                         huoltajanpuhelinnumero: Option[String],
                         huoltajansahkoposti: Option[String],
                         hakemus: XMLHakemus,
                         oppivelvollisuusVoimassaAsti: Option[String],
                         oikeusMaksuttomaanKoulutukseenVoimassaAsti: Option[String],
                         lisakysymykset: Seq[Lisakysymys]
                       )

case class JSONHakijat(hakijat: Seq[JSONHakija])
case class JSONHakijatV4(hakijat: Seq[JSONHakijaV4])
case class JSONHakijatV5(hakijat: Seq[JSONHakijaV5])
case class JSONHakijatV6(hakijat: Seq[JSONHakijaV6])