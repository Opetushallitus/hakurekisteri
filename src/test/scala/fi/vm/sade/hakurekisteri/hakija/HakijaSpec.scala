package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.sijoittelu._
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers


class HakijaSpec extends FlatSpec with ShouldMatchers {

  object OppilaitosX extends Organisaatio("1.10.1", Map("fi" -> "Oppilaitos X"), None, Some("00001"), None)

  object FullHakemus1 extends FullHakemus("1.25.1", None, "1.1",
    answers = Some(
      HakemusAnswers(
        henkilotiedot = Some(
          HakemusHenkilotiedot(
            kansalaisuus =  Some("FIN"),
            asuinmaa = Some("FIN"),
            matkapuhelinnumero1 = Some("0401234567"),
            Sukunimi = Some("Mäkinen"),
            Henkilotunnus = Some("200394-9839"),
            Postinumero = Some("00100"),
            lahiosoite = Some("Katu 1"),
            sukupuoli = Some("1"),
            Sähköposti = Some("mikko@testi.oph.fi"),
            Kutsumanimi = Some("Mikko"),
            Etunimet = Some("Mikko"),
            kotikunta = Some("098"),
            aidinkieli = Some("FI"),
            syntymaaika = Some("20.03.1994"),
            onkoSinullaSuomalainenHetu = Some("true"))),
        koulutustausta = Some(
          Koulutustausta(
            PK_PAATTOTODISTUSVUOSI = Some("2014"),
            POHJAKOULUTUS = Some("1"),
            lahtokoulu = Some(OppilaitosX.oid),
            luokkataso = Some("9"),
            LISAKOULUTUS_KYMPPI = None,
            LISAKOULUTUS_VAMMAISTEN = None,
            LISAKOULUTUS_TALOUS = None,
            LISAKOULUTUS_AMMATTISTARTTI = None,
            LISAKOULUTUS_KANSANOPISTO = None,
            LISAKOULUTUS_MAAHANMUUTTO = None,
            lahtoluokka = Some("9A"),
            lukioPaattotodistusVuosi = None
          )),
        hakutoiveet =  Some(Map(
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
          "preference1_kaksoistutkinnon_lisakysymys" -> "true")),
        lisatiedot = Some(
          Lisatiedot(
            lupaMarkkinointi = Some("true"),
            lupaJulkaisu = Some("true"))))),
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
