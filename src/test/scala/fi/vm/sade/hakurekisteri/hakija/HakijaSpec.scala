package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.sijoittelu._
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers


class HakijaSpec extends FlatSpec with ShouldMatchers {

  object OppilaitosX extends Organisaatio("1.10.1", Map("fi" -> "Oppilaitos X"), None, Some("00001"), None)

  object FullHakemus1 extends FullHakemus("1.25.1", None, "1.1",
    Some(Map(
      "henkilotiedot" -> Map(
        "kansalaisuus" -> "FIN",
        "asuinmaa" -> "FIN",
        "matkapuhelinnumero1" -> "0401234567",
        "Sukunimi" -> "Mäkinen",
        "Henkilotunnus" -> "200394-9839",
        "Postinumero" -> "00100",
        "lahiosoite" -> "Katu 1",
        "sukupuoli" -> "1",
        "Sähköposti" -> "mikko@testi.oph.fi",
        "Kutsumanimi" -> "Mikko",
        "Etunimet" -> "Mikko",
        "kotikunta" -> "098",
        "aidinkieli" -> "FI",
        "syntymaaika" -> "20.03.1994",
        "onkoSinullaSuomalainenHetu" -> "true"),
      "koulutustausta" -> Map(
        "PK_PAATTOTODISTUSVUOSI" -> "2014",
        "POHJAKOULUTUS" -> "1",
        "perusopetuksen_kieli" -> "FI",
        "lahtokoulu" -> OppilaitosX.oid,
        "lahtoluokka" -> "9A",
        "luokkataso" -> "9"),
      "hakutoiveet" -> Map(
        "preference2-Opetuspiste" -> "Ammattikoulu Lappi2",
        "preference2-Opetuspiste-id" -> "1.10.4",
        "preference2-Koulutus" -> "Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)4",
        "preference2-Koulutus-id" -> "1.11.2",
        "preference2-Koulutus-id-aoIdentifier" -> "460",
        "preference2-Koulutus-id-educationcode" -> "koulutus_321204",
        "preference2-Koulutus-id-lang" -> "FI",
        "preference1-Opetuspiste" -> "Ammattikoulu Lappi",
        "preference1-Opetuspiste-id" -> "1.10.3",
        "preference1-Koulutus" -> "Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)",
        "preference1-Koulutus-id" -> "1.11.1",
        "preference1-Koulutus-id-aoIdentifier" -> "460",
        "preference1-Koulutus-id-educationcode" -> "koulutus_321204",
        "preference1-Koulutus-id-lang" -> "FI",
        "preference1-Koulutus-id-sora" -> "true",
        "preference1_sora_terveys" -> "true",
        "preference1_sora_oikeudenMenetys" -> "true",
        "preference1-discretionary-follow-up" -> "sosiaalisetsyyt",
        "preference1_urheilijan_ammatillisen_koulutuksen_lisakysymys" -> "true",
        "preference1_kaksoistutkinnon_lisakysymys" -> "true"),
      "lisatiedot" -> Map(
        "lupaMarkkinointi" -> "true",
        "lupaJulkaisu" -> "true"))),
    state = Some("ACTIVE")
  )


  val toive = AkkaHakupalvelu.getHakija(FullHakemus1).hakemus.hakutoiveet.head


  behavior of "Hakemuksen lasnaolotieto"

  it should "have vastaanotto as 3 for someone who is present" in {

    val xmlht = xmlHTFor(SijoitteluHakemuksenTila.HYVAKSYTTY, SijoitteluValintatuloksenTila.VASTAANOTTANUT_LASNA)

    xmlht.vastaanotto should be (Some("3"))

  }

  it should "have lasnaolo as 1 for someone who is present" in {

    val xmlht = xmlHTFor(SijoitteluHakemuksenTila.HYVAKSYTTY, SijoitteluValintatuloksenTila.VASTAANOTTANUT_LASNA)

    xmlht.lasnaolo should be (None)

  }

  it should "have lasnaolo as 2 for someone who is not present" in {

    val xmlht = xmlHTFor(SijoitteluHakemuksenTila.HYVAKSYTTY, SijoitteluValintatuloksenTila.VASTAANOTTANUT_POISSAOLEVA)

    xmlht.lasnaolo should be (None)

  }

  it should "not have lasnaolo for someone who's presence is unknown" in {

    val xmlht = xmlHTFor(SijoitteluHakemuksenTila.HYVAKSYTTY, SijoitteluValintatuloksenTila.VASTAANOTTANUT)

    xmlht.lasnaolo should be (None)

  }


  def xmlHTFor(hyvaksytty: SijoitteluHakemuksenTila.Value, vastaanottanut_lasna: SijoitteluValintatuloksenTila.Value): XMLHakutoive = {
    XMLHakutoive(Hakutoive(toive,
      Some(hyvaksytty),
      Some(vastaanottanut_lasna)), OppilaitosX, "koodi")
  }
}
