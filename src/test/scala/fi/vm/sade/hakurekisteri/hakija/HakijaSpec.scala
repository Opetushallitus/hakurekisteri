package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.hakurekisteri.dates.{Ajanjakso, InFuture}
import fi.vm.sade.hakurekisteri.hakija.representation.{JSONHakija, XMLHakemus, XMLHakutoive}
import fi.vm.sade.hakurekisteri.integration.haku.{Haku, Kieliversiot}
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.valintatulos._
import org.joda.time.DateTime
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Future


class HakijaSpec extends FlatSpec with Matchers {

  object OppilaitosX extends Organisaatio("1.10.1", Map("fi" -> "Oppilaitos X"), None, Some("00001"), None, None, Seq())

  object FullHakemus1 extends FullHakemus("1.25.1", None, "1.1",
    answers = Some(
      HakemusAnswers(
        henkilotiedot = Some(
          HakemusHenkilotiedot(
            kansalaisuus = Some("FIN"),
            asuinmaa = Some("FIN"),
            matkapuhelinnumero1 = Some("0401234567"),
            matkapuhelinnumero2 = None,
            Sukunimi = Some("Mäkinen"),
            Henkilotunnus = Some("200394-9839"),
            Postinumero = Some("00100"),
            osoiteUlkomaa = None,
            postinumeroUlkomaa = None,
            kaupunkiUlkomaa = None,
            lahiosoite = Some("Katu 1"),
            sukupuoli = Some("1"),
            Sähköposti = Some("mikko@testi.oph.fi"),
            Kutsumanimi = Some("Mikko"),
            Etunimet = Some("Mikko"),
            kotikunta = Some("098"),
            aidinkieli = Some("FI"),
            syntymaaika = Some("20.03.1994"),
            onkoSinullaSuomalainenHetu = Some("true"),
            koulusivistyskieli = Some("FI"),
            huoltajannimi = Some("nimi"),
            turvakielto = None)),
        koulutustausta = Some(
          Koulutustausta(
            PK_PAATTOTODISTUSVUOSI = Some("2014"),
            KYMPPI_PAATTOTODISTUSVUOSI = None,
            POHJAKOULUTUS = Some("1"),
            lahtokoulu = Some(OppilaitosX.oid),
            luokkataso = Some("9"),
            LISAKOULUTUS_KYMPPI = None,
            LISAKOULUTUS_VAMMAISTEN = None,
            LISAKOULUTUS_TALOUS = None,
            LISAKOULUTUS_AMMATTISTARTTI = None,
            LISAKOULUTUS_KANSANOPISTO = None,
            LISAKOULUTUS_MAAHANMUUTTO = None,
            LISAKOULUTUS_MAAHANMUUTTO_LUKIO = None,
            lahtoluokka = Some("9A"),
            perusopetuksen_kieli = None,
            lukion_kieli = None,
            lukioPaattotodistusVuosi = None,
            pohjakoulutus_yo = None,
            pohjakoulutus_yo_vuosi = None,
            pohjakoulutus_am = None,
            pohjakoulutus_am_vuosi = None,
            pohjakoulutus_amt = None,
            pohjakoulutus_amt_vuosi = None,
            pohjakoulutus_kk = None,
            pohjakoulutus_kk_pvm = None,
            pohjakoulutus_avoin = None,
            pohjakoulutus_ulk = None,
            pohjakoulutus_ulk_vuosi = None,
            pohjakoulutus_muu = None,
            pohjakoulutus_muu_vuosi = None,
            aiempitutkinto_korkeakoulu = None,
            aiempitutkinto_tutkinto = None,
            aiempitutkinto_vuosi = None,
            suoritusoikeus_tai_aiempi_tutkinto = None,
            suoritusoikeus_tai_aiempi_tutkinto_vuosi = None,
            muukoulutus = None
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
        lisatiedot = Some(Map(
          "lupaMarkkinointi" -> "true",
          "lupaJulkaisu-id" -> "true",
          "kiinnostunutoppisopimuksesta" -> "true",
          "54bf445ee4b021d892c6583d" -> "option_0",
          "54e30c41e4b08eed6d776189" -> "Tekstivastaus",
          "54c8e11ee4b03c06d74fc5cc-option_0" -> "",
          "54c8e11ee4b03c06d74fc5cc-option_1" -> "true",
          "54c8e11ee4b03c06d74fc5cc-option_2" -> "true"
        )),
        osaaminen = None)
    ),
    state = Some("ACTIVE"),
    preferenceEligibilities = Seq(),
    attachmentRequests = Seq()
  )

  val haku = Haku(
    Kieliversiot(Some("haku"), None, None),
    "1.1",
    Ajanjakso(new DateTime(), InFuture),
    "kausi_s#1",
    2014,
    Some("kausi_k#1"),
    Some(2015), false, None, None)

  val tq1 = ThemeQuestion(`type` = "ThemeRadioButtonQuestion", messageText = "Millä kielellä haluat saada valintakokeen?", options = Some(Map(
    "option_0" -> "Suomi",
    "option_1" -> "Ruotsi")), applicationOptionOids=Seq("1.2.3.4"))
  val tq2 = ThemeQuestion(`type` = "ThemeCheckBoxQuestion", messageText = "Valintakokeet", options = Some(Map(
    "option_2" -> "Matematiikka (DI), fysiikka ja kemia",
    "option_0" -> "Matematiikka (DI) ja fysiikka",
    "option_1" -> "Matematiikka (DI) ja kemia")), applicationOptionOids=Seq("1.2.3.4"))
  val tq3 = ThemeQuestion(`type` = "ThemeTextQuestion", messageText = "Tanssin aiempi aktiivinen ja säännöllinen harrastaminen", options=Option.empty,
    applicationOptionOids=Set("1.2.3.4").toSeq)

  val themeQuestions: Map[String, ThemeQuestion] = Map(
    "54bf445ee4b021d892c6583d" -> tq1,
    "54c8e11ee4b03c06d74fc5cc" -> tq2,
    "54e30c41e4b08eed6d776189" -> tq3)

  val toive = AkkaHakupalvelu.getHakija(FullHakemus1, haku, themeQuestions, Option("1.2.3.4"), None, Map("246" -> "FIN")).hakemus.hakutoiveet.head


  behavior of "Hakemuksen lasnaolotieto"



  it should "not have lasnaolo for someone who's presence is unknown" in {

    val xmlht = xmlHTFor(Valintatila.HYVAKSYTTY, Vastaanottotila.VASTAANOTTANUT)

    xmlht.lasnaolo should be (None)

  }


  def xmlHTFor(hyvaksytty: Valintatila.Value, vastaanottanut_lasna: Vastaanottotila.Value): XMLHakutoive = {
    XMLHakutoive(Hakutoive(toive,
      Some(hyvaksytty),
      Some(vastaanottanut_lasna), None), OppilaitosX, "koodi")
  }

  it should "have v2 fields" in {
    val hakija = AkkaHakupalvelu.getHakija(FullHakemus1, haku, themeQuestions, Option.empty, None, Map("246" -> "FIN"))
    hakija.henkilo.huoltajannimi should be("nimi")
    hakija.henkilo.lisakysymykset.length should be(3 + AkkaHakupalvelu.hardCodedLisakysymys.size)
    hakija.henkilo.lisakysymykset.flatMap(_.vastaukset.map(_.vastausteksti)) should contain("Tekstivastaus")
  }


  behavior of "KoosteData"

  it should "resolve pohjakoulutus from KoosteData not from Hakemus" in {
    val nonExistentKoosteData = None
    val emptyKoosteData = Some(Map[String,String]())
    val koosteData = Some(Map("POHJAKOULUTUS" -> "1"))

    val hakija1 = AkkaHakupalvelu.getHakija(FullHakemus1, haku, themeQuestions, Option.empty, nonExistentKoosteData, Map("246" -> "FIN"))
    val hakija2 = AkkaHakupalvelu.getHakija(FullHakemus1, haku, themeQuestions, Option.empty, emptyKoosteData, Map("246" -> "FIN"))
    val hakija3 = AkkaHakupalvelu.getHakija(FullHakemus1, haku, themeQuestions, Option.empty, koosteData, Map("246" -> "FIN"))

    def getPohjaKoulutus(hakija: Hakija): String = {
      val hakemus: XMLHakemus = XMLHakemus.apply(hakija, opiskelutieto = None, lahtokoulu = None, toiveet = Seq(), osaaminen = None)
      val jsonHakija: JSONHakija = JSONHakija(hakija, hakemus)
      jsonHakija.hakemus.pohjakoulutus
    }

    getPohjaKoulutus(hakija1) should be("7")
    getPohjaKoulutus(hakija2) should be("7")
    getPohjaKoulutus(hakija3) should be("1")
  }
}
