package fi.vm.sade.hakurekisteri.acceptance.tools

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorSystem, Props}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.dates.{Ajanjakso, InFuture}
import fi.vm.sade.hakurekisteri.hakija.{Hakija, _}
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.hakemus.{ListHakemus, _}
import fi.vm.sade.hakurekisteri.integration.haku.{Haku, Kieliversiot}
import fi.vm.sade.hakurekisteri.integration.koodisto._
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.integration.tarjonta.{Hakukohde, _}
import fi.vm.sade.hakurekisteri.integration.valintatulos._
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, User}
import fi.vm.sade.hakurekisteri.web.rest.support.HakurekisteriSwagger
import fi.vm.sade.hakurekisteri.{MockConfig, SpecsLikeMockito}
import org.joda.time.DateTime
import org.scalatest.Suite
import org.scalatra.swagger.Swagger

import scala.compat.Platform
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

trait HakeneetSupport extends Suite with HakurekisteriJsonSupport with SpecsLikeMockito {
  object OppilaitosX extends Organisaatio("1.10.1", Map("fi" -> "Oppilaitos X"), None, Some("00001"), None, None, Seq())
  object OppilaitosY extends Organisaatio("1.10.2", Map("fi" -> "Oppilaitos Y"), None, Some("00002"), None, None, Seq())
  object OppilaitosZ extends Organisaatio("1.10.6", Map("fi" -> "Oppilaitos Z"), None, Some("00003"), None, None, Seq())

  object OpetuspisteX extends Organisaatio("1.10.3", Map("fi" -> "Opetuspiste X"), Some("0000101"), None, Some("1.10.1"), None, Seq())
  object OpetuspisteZ extends Organisaatio("1.10.5", Map("fi" -> "Opetuspiste Z"), Some("0000101"), None, Some("1.10.1"), None, Seq())
  object OpetuspisteY extends Organisaatio("1.10.4", Map("fi" -> "Opetuspiste Y"), Some("0000201"), None, Some("1.10.2"), None, Seq())

  object AtaruOpetuspiste1 extends Organisaatio("1.2.246.562.10.39920288212", Map("fi" -> "AtaruOpetuspiste1"), None, None, None,
    Some("1.2.246.562.10.00000000001,1.2.246.562.10.82388989657,1.2.246.562.10.56753942459"), Seq())
  object AtaruOpetuspiste2 extends Organisaatio("1.2.246.562.10.2014041814420657444022", Map("fi" -> "AtaruOpetuspiste2"), None, None, None,
    Some("1.2.246.562.10.00000000001,1.2.246.562.10.240484683010,1.2.246.562.10.38515028629,1.2.246.562.10.665851030310"), Seq())

  object FullHakemus1 extends FullHakemus("1.25.1", Some("1.24.1"), "1.1",
    answers = Some(
      HakemusAnswers(
        osaaminen = Some(Map("yleinen_kielitutkinto_fi" -> "true", "valtionhallinnon_kielitutkinto_fi" -> "true")),
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
            turvakielto = Some("true"))),
        koulutustausta = Some(
          Koulutustausta(
            pohjakoulutus_muu_vuosi = None,
            perusopetuksen_kieli = None,
            lukion_kieli = None,
            pohjakoulutus_yo_vuosi = None,
            pohjakoulutus_am_vuosi = None,
            pohjakoulutus_amt_vuosi = None,
            pohjakoulutus_kk_pvm = None,
            pohjakoulutus_ulk_vuosi = None,
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
            lukioPaattotodistusVuosi = None,
            pohjakoulutus_yo = Some("true"),
            pohjakoulutus_am = None,
            pohjakoulutus_amt = None,
            pohjakoulutus_kk = None,
            pohjakoulutus_avoin = None,
            pohjakoulutus_ulk = None,
            pohjakoulutus_muu = None,
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
          "preference1-Opetuspiste-id-parents" -> "1.10.3,1.2.246.562.10.00000000001",
          "preference1-Koulutus" -> "Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)",
          "preference1-Koulutus-id" -> "1.11.1",
          "preference1-Koulutus-id-aoIdentifier" -> "460",
          "preference1-Koulutus-id-educationcode" -> "koulutus_321204",
          "preference1-Koulutus-id-lang" -> "FI",
          "preference1-Koulutus-id-sora" -> "true",
          "preference1-Koulutus-id-vocational" -> "true",
          "preference1_sora_terveys" -> "true",
          "preference1_sora_oikeudenMenetys" -> "true",
          "preference1-discretionary-follow-up" -> "sosiaalisetsyyt",
          "preference1_urheilijan_ammatillisen_koulutuksen_lisakysymys" -> "true",
          "preference1_kaksoistutkinnon_lisakysymys" -> "true")),
        lisatiedot = Some(Map(
          "lupaMarkkinointi" -> "true",
          "lupaJulkaisu-id" -> "true",
          "kiinnostunutoppisopimuksesta" -> "true")))),
    state = Some("ACTIVE"),
    preferenceEligibilities = Seq(PreferenceEligibility("1.11.1", "NOT_CHECKED", Some("UNKNOWN"), Some("NOT_CHECKED")), PreferenceEligibility("1.11.2", "NOT_CHECKED", Some("UNKNOWN"), Some("NOT_CHECKED"))),
    attachmentRequests = Seq(HakemusAttachmentRequest ("3bb18492-abe1-4c69-be59-7eb721447aa6", None, Some("1.2.246.562.20.18496942519"), "NOT_CHECKED", "NOT_RECEIVED", ApplicationAttachment(Option(Name(Translations("suomi", "ruotsi" ,"englanti"))), Option(Header(Translations("suomi", "ruotsi" ,"englanti"))), Address("Vastaanottaja", "Tie 1", "00100", "Helsinki"))))
  )
  object FullHakemus2 extends FullHakemus("1.25.2", Some("1.24.2"), "1.2",
    answers = Some(
      HakemusAnswers(
        osaaminen = None,
        henkilotiedot = Some(
          HakemusHenkilotiedot(
            kansalaisuus =  Some("FIN"),
            asuinmaa = Some("FIN"),
            matkapuhelinnumero1 = Some("0401234567"),
            matkapuhelinnumero2 = None,
            Sukunimi = Some("Mäkinen"),
            Henkilotunnus = Some("200394-9839"),
            Postinumero = Some("00100"),
            Postitoimipaikka = Some("Helsinki"),
            osoiteUlkomaa = None,
            postinumeroUlkomaa = None,
            kaupunkiUlkomaa = None,
            lahiosoite = Some("Katu 1"),
            sukupuoli = Some("1"),
            Sähköposti = Some("mikko@testi.oph.fi"),
            Kutsumanimi = Some("Mikko"),
            Etunimet = Some("Mikko"),
            kotikunta = None,
            aidinkieli = Some("FI"),
            syntymaaika = Some("20.03.1994"),
            onkoSinullaSuomalainenHetu = Some("true"),
            koulusivistyskieli = Some("FI"),
            turvakielto = None)),
        koulutustausta = Some(
          Koulutustausta(
            pohjakoulutus_muu_vuosi = None,
            perusopetuksen_kieli = None,
            lukion_kieli = None,
            pohjakoulutus_yo_vuosi = None,
            pohjakoulutus_am_vuosi = None,
            pohjakoulutus_amt_vuosi = None,
            pohjakoulutus_kk_pvm = None,
            pohjakoulutus_ulk_vuosi = None,
            PK_PAATTOTODISTUSVUOSI = Some("2014"),
            KYMPPI_PAATTOTODISTUSVUOSI = None,
            POHJAKOULUTUS = Some("1"),
            lahtokoulu = Some(OppilaitosY.oid),
            luokkataso = Some("9"),
            LISAKOULUTUS_KYMPPI = None,
            LISAKOULUTUS_VAMMAISTEN = None,
            LISAKOULUTUS_TALOUS = None,
            LISAKOULUTUS_AMMATTISTARTTI = None,
            LISAKOULUTUS_KANSANOPISTO = None,
            LISAKOULUTUS_MAAHANMUUTTO = None,
            LISAKOULUTUS_MAAHANMUUTTO_LUKIO = None,
            lahtoluokka = Some("9A"),
            lukioPaattotodistusVuosi = None,
            pohjakoulutus_yo = None,
            pohjakoulutus_am = Some("true"),
            pohjakoulutus_amt = None,
            pohjakoulutus_kk = None,
            pohjakoulutus_avoin = None,
            pohjakoulutus_ulk = None,
            pohjakoulutus_muu = None,
            aiempitutkinto_korkeakoulu = None,
            aiempitutkinto_tutkinto = None,
            aiempitutkinto_vuosi = None,
            suoritusoikeus_tai_aiempi_tutkinto = None,
            suoritusoikeus_tai_aiempi_tutkinto_vuosi = None,
            muukoulutus = None
          )),
        hakutoiveet =  Some(Map(
          "preference2-Opetuspiste" -> "Ammattiopisto Loppi2\"",
          "preference2-Opetuspiste-id" -> "1.10.5",
          "preference2-Koulutus" -> "Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)2",
          "preference2-Koulutus-id" -> "1.11.1",
          "preference2-Koulutus-id-aoIdentifier" -> "460",
          "preference2-Koulutus-id-educationcode" -> "koulutus_321204",
          "preference2-Koulutus-id-lang" -> "FI",
          "preference1-Opetuspiste" -> "Ammattiopisto Loppi",
          "preference1-Opetuspiste-id" -> "1.10.4",
          "preference1-Opetuspiste-id-parents" -> "1.10.4,1.2.246.562.10.00000000001",
          "preference1-Koulutus" -> "Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)",
          "preference1-Koulutus-id" -> "1.11.2",
          "preference1-Koulutus-id-aoIdentifier" -> "460",
          "preference1-Koulutus-id-educationcode" -> "koulutus_321204",
          "preference1-Koulutus-id-lang" -> "FI",
          "preference1-Koulutus-id-sora" -> "true",
          "preference1_sora_terveys" -> "true",
          "preference1_sora_oikeudenMenetys" -> "true",
          "preference1-discretionary-follow-up" -> "oppimisvaikudet",
          "preference1_urheilijan_ammatillisen_koulutuksen_lisakysymys" -> "true",
          "preference1_kaksoistutkinnon_lisakysymys" -> "true")),
        lisatiedot = Some(Map(
          "lupaMarkkinointi" -> "true",
          "lupaJulkaisu-id" -> "true",
          "kiinnostunutoppisopimuksesta" -> "true")))),
    state = Some("INCOMPLETE"),
    preferenceEligibilities = Seq(),
    attachmentRequests = Seq()
  )
  object FullHakemus3 extends FullHakemus("1.25.2", Some("1.24.2"), "1.2",
    answers = Some(
      HakemusAnswers(
        osaaminen = None,
        henkilotiedot = Some(
          HakemusHenkilotiedot(
            kansalaisuus =  Some("FIN"),
            asuinmaa = Some("NAN"),
            matkapuhelinnumero1 = Some("0401234567"),
            matkapuhelinnumero2 = None,
            Sukunimi = Some("Mäkinen"),
            Henkilotunnus = Some("200394-9839"),
            Postinumero = None,
            Postitoimipaikka = None,
            osoiteUlkomaa = Some("Passeig Calvel 45"),
            postinumeroUlkomaa = Some("VUORI6"),
            kaupunkiUlkomaa = Some("Parc la Vuori"),
            lahiosoite = None,
            sukupuoli = Some("1"),
            Sähköposti = Some("mikko@testi.oph.fi"),
            Kutsumanimi = Some("Mikko"),
            Etunimet = Some("Mikko"),
            kotikunta = None,
            aidinkieli = Some("FI"),
            syntymaaika = Some("20.03.1994"),
            onkoSinullaSuomalainenHetu = Some("true"),
            koulusivistyskieli = Some("FI"),
            huoltajannimi=Some("huoltajannimi"),
            turvakielto = None)),
        koulutustausta = Some(
          Koulutustausta(
            pohjakoulutus_muu_vuosi = None,
            perusopetuksen_kieli = None,
            lukion_kieli = None,
            pohjakoulutus_yo_vuosi = None,
            pohjakoulutus_am_vuosi = None,
            pohjakoulutus_amt_vuosi = None,
            pohjakoulutus_kk_pvm = None,
            pohjakoulutus_ulk_vuosi = None,
            PK_PAATTOTODISTUSVUOSI = Some("2014"),
            KYMPPI_PAATTOTODISTUSVUOSI = None,
            POHJAKOULUTUS = Some("1"),
            lahtokoulu = Some(OppilaitosY.oid),
            luokkataso = Some("9"),
            LISAKOULUTUS_KYMPPI = None,
            LISAKOULUTUS_VAMMAISTEN = None,
            LISAKOULUTUS_TALOUS = None,
            LISAKOULUTUS_AMMATTISTARTTI = None,
            LISAKOULUTUS_KANSANOPISTO = None,
            LISAKOULUTUS_MAAHANMUUTTO = None,
            LISAKOULUTUS_MAAHANMUUTTO_LUKIO = None,
            lahtoluokka = Some("9A"),
            lukioPaattotodistusVuosi = None,
            pohjakoulutus_yo = None,
            pohjakoulutus_am = Some("true"),
            pohjakoulutus_amt = None,
            pohjakoulutus_kk = None,
            pohjakoulutus_avoin = None,
            pohjakoulutus_ulk = None,
            pohjakoulutus_muu = None,
            aiempitutkinto_korkeakoulu = None,
            aiempitutkinto_tutkinto = None,
            aiempitutkinto_vuosi = None,
            suoritusoikeus_tai_aiempi_tutkinto = None,
            suoritusoikeus_tai_aiempi_tutkinto_vuosi = None,
            muukoulutus = None
          )),
        hakutoiveet =  Some(Map(
          "preference2-Opetuspiste" -> "Ammattiopisto Loppi2\"",
          "preference2-Opetuspiste-id" -> "1.10.5",
          "preference2-Koulutus" -> "Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)2",
          "preference2-Koulutus-id" -> "1.11.1",
          "preference2-Koulutus-id-aoIdentifier" -> "460",
          "preference2-Koulutus-id-educationcode" -> "koulutus_321204",
          "preference2-Koulutus-id-lang" -> "FI",
          "preference1-Opetuspiste" -> "Ammattiopisto Loppi",
          "preference1-Opetuspiste-id" -> "1.10.4",
          "preference1-Opetuspiste-id-parents" -> "1.10.4,1.2.246.562.10.00000000001",
          "preference1-Koulutus" -> "Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)",
          "preference1-Koulutus-id" -> "1.11.2",
          "preference1-Koulutus-id-aoIdentifier" -> "460",
          "preference1-Koulutus-id-educationcode" -> "koulutus_321204",
          "preference1-Koulutus-id-lang" -> "FI",
          "preference1-Koulutus-id-sora" -> "true",
          "preference1_sora_terveys" -> "true",
          "preference1_sora_oikeudenMenetys" -> "true",
          "preference1-discretionary-follow-up" -> "oppimisvaikudet",
          "preference1_urheilijan_ammatillisen_koulutuksen_lisakysymys" -> "true",
          "preference1_kaksoistutkinnon_lisakysymys" -> "true")),
        lisatiedot = Some(Map(
          "lupaMarkkinointi" -> "true",
          "lupaJulkaisu-id" -> "true",
          "kiinnostunutoppisopimuksesta" -> "true")))),
    state = Some("INCOMPLETE"),
    preferenceEligibilities = Seq(),
    attachmentRequests = Seq(HakemusAttachmentRequest("3bb18492-abe1-4c69-be59-7eb721447aa6", Some("1.2.246.562.20.18496942519"), None, "NOT_CHECKED", "NOT_RECEIVED", ApplicationAttachment(Option(Name(Translations("suomi", "ruotsi" ,"englanti"))), Option(Header(Translations("suomi", "ruotsi" ,"englanti"))), Address("Vastaanottajan kanslia", "Tie 1", "00100", "Helsinki")))))

  object FullHakemus4 extends FullHakemus("1.25.2", Some("1.24.2"), "1.2",
    answers = Some(
      HakemusAnswers(
        osaaminen = None,
        henkilotiedot = Some(
          HakemusHenkilotiedot(
            kansalaisuus =  Some("FIN"),
            asuinmaa = Some("NAN"),
            matkapuhelinnumero1 = Some("0401234567"),
            matkapuhelinnumero2 = None,
            Sukunimi = Some("Mäkinen"),
            Henkilotunnus = Some("200394-9839"),
            Postinumero = None,
            Postitoimipaikka = None,
            osoiteUlkomaa = Some("Passeig Calvel 45"),
            postinumeroUlkomaa = Some("VUORI6"),
            kaupunkiUlkomaa = Some("Parc la Vuori"),
            lahiosoite = None,
            sukupuoli = Some("1"),
            Sähköposti = Some("mikko@testi.oph.fi"),
            Kutsumanimi = Some("Mikko"),
            Etunimet = Some("Mikko"),
            kotikunta = None,
            aidinkieli = Some("FI"),
            syntymaaika = Some("20.03.1994"),
            onkoSinullaSuomalainenHetu = Some("true"),
            koulusivistyskieli = Some("FI"),
            huoltajannimi=Some("huoltajannimi"),
            turvakielto = None)),
        koulutustausta = Some(
          Koulutustausta(
            pohjakoulutus_muu_vuosi = None,
            perusopetuksen_kieli = None,
            lukion_kieli = None,
            pohjakoulutus_yo_vuosi = None,
            pohjakoulutus_am_vuosi = None,
            pohjakoulutus_amt_vuosi = None,
            pohjakoulutus_kk_pvm = None,
            pohjakoulutus_ulk_vuosi = None,
            PK_PAATTOTODISTUSVUOSI = Some("2014"),
            KYMPPI_PAATTOTODISTUSVUOSI = None,
            POHJAKOULUTUS = Some("1"),
            lahtokoulu = Some(OppilaitosY.oid),
            luokkataso = Some("9"),
            LISAKOULUTUS_KYMPPI = None,
            LISAKOULUTUS_VAMMAISTEN = None,
            LISAKOULUTUS_TALOUS = None,
            LISAKOULUTUS_AMMATTISTARTTI = None,
            LISAKOULUTUS_KANSANOPISTO = None,
            LISAKOULUTUS_MAAHANMUUTTO = None,
            LISAKOULUTUS_MAAHANMUUTTO_LUKIO = None,
            lahtoluokka = Some("9A"),
            lukioPaattotodistusVuosi = None,
            pohjakoulutus_yo = None,
            pohjakoulutus_am = Some("true"),
            pohjakoulutus_amt = None,
            pohjakoulutus_kk = None,
            pohjakoulutus_avoin = None,
            pohjakoulutus_ulk = None,
            pohjakoulutus_muu = None,
            aiempitutkinto_korkeakoulu = None,
            aiempitutkinto_tutkinto = None,
            aiempitutkinto_vuosi = None,
            suoritusoikeus_tai_aiempi_tutkinto = None,
            suoritusoikeus_tai_aiempi_tutkinto_vuosi = None,
            muukoulutus = None
          )),
        hakutoiveet =  Some(Map(
          "preference2-Opetuspiste" -> "Ammattikoulu Lappi2",
          "preference2-Opetuspiste-id" -> "1.10.3",
          "preference2-Opetuspiste-id-parents" -> "1.10.3,1.2.246.562.10.00000000001",
          "preference2-Koulutus" -> "Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)4",
          "preference2-Koulutus-id" -> "1.11.2",
          "preference2-Koulutus-id-aoIdentifier" -> "000",
          "preference2-Koulutus-id-educationcode" -> "koulutus_321204",
          "preference2-Koulutus-id-lang" -> "FI",
          "preference1-Opetuspiste" -> "Ammattikoulu Lappi",
          "preference1-Opetuspiste-id" -> "1.10.3",
          "preference1-Opetuspiste-id-parents" -> "1.10.3,1.2.246.562.10.00000000001",
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
          "preference1_kaksoistutkinnon_lisakysymys" -> "true",
          "lisakysymys1" -> "option_0",
          "lisakysymys2-option_0" -> "",
          "lisakysymys2-option_1" -> "true",
          "lisakysymys3" -> "Tekstikysymys")),
        lisatiedot = Some(Map(
          "lupaMarkkinointi" -> "true",
          "lupaJulkaisu-id" -> "true",
          "hojks" -> "true",
          "koulutuskokeilu" -> "false",
          "miksi_ammatilliseen" -> "Siksi ammatilliseen",
          "kiinnostunutoppisopimuksesta" -> "true")))),
    state = Some("INCOMPLETE"),
    preferenceEligibilities = Seq(),
    attachmentRequests = Seq()
  )

  object FullHakemus5 extends FullHakemus("1.24.5", Some("1.0.1"), "1.3",
    answers = Some(
      HakemusAnswers(
        osaaminen = Some(Map("yleinen_kielitutkinto_fi" -> "true", "valtionhallinnon_kielitutkinto_fi" -> "true")),
        henkilotiedot = Some(
          HakemusHenkilotiedot(
            kansalaisuus = Some("FIN"),
            asuinmaa = Some("FIN"),
            matkapuhelinnumero1 = Some("0401234567"),
            matkapuhelinnumero2 = None,
            Sukunimi = Some("Hyvaksytty"),
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
            turvakielto = Some("true"))),
        koulutustausta = Some(
          Koulutustausta(
            pohjakoulutus_muu_vuosi = None,
            perusopetuksen_kieli = None,
            lukion_kieli = None,
            pohjakoulutus_yo_vuosi = None,
            pohjakoulutus_am_vuosi = None,
            pohjakoulutus_amt_vuosi = None,
            pohjakoulutus_kk_pvm = None,
            pohjakoulutus_ulk_vuosi = None,
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
            lukioPaattotodistusVuosi = None,
            pohjakoulutus_yo = Some("true"),
            pohjakoulutus_am = None,
            pohjakoulutus_amt = None,
            pohjakoulutus_kk = None,
            pohjakoulutus_avoin = None,
            pohjakoulutus_ulk = None,
            pohjakoulutus_muu = None,
            aiempitutkinto_korkeakoulu = None,
            aiempitutkinto_tutkinto = None,
            aiempitutkinto_vuosi = None,
            suoritusoikeus_tai_aiempi_tutkinto = None,
            suoritusoikeus_tai_aiempi_tutkinto_vuosi = None,
            muukoulutus = None
          )),
        hakutoiveet =  Some(Map(
          "preference1-Opetuspiste" -> "Ammattikoulu Lappi",
          "preference1-Opetuspiste-id" -> "1.10.3",
          "preference1-Opetuspiste-id-parents" -> "",
          "preference1-Koulutus" -> "Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)",
          "preference1-Koulutus-id" -> "1.11.5",
          "preference1-Koulutus-id-aoIdentifier" -> "460",
          "preference1-Koulutus-id-educationcode" -> "koulutus_321204",
          "preference1-Koulutus-id-lang" -> "FI",
          "preference1-Koulutus-id-sora" -> "true",
          "preference1-Koulutus-id-vocational" -> "true",
          "preference1_sora_terveys" -> "true",
          "preference1_sora_oikeudenMenetys" -> "true",
          "preference1-discretionary-follow-up" -> "sosiaalisetsyyt",
          "preference1_urheilijan_ammatillisen_koulutuksen_lisakysymys" -> "true",
          "preference1_kaksoistutkinnon_lisakysymys" -> "true")),
        lisatiedot = Some(Map(
          "lupaMarkkinointi" -> "true",
          "lupaJulkaisu-id" -> "true",
          "kiinnostunutoppisopimuksesta" -> "true")))),
    state = Some("ACTIVE"),
    preferenceEligibilities = Seq(PreferenceEligibility("1.11.5", "NOT_CHECKED", Some("UNKNOWN"), Some("NOT_CHECKED"))),
    attachmentRequests = Seq()
  )

  object SynteettinenHakemus extends FullHakemus("1.25.3", Some("1.24.3"), "1.3",
    answers = Some(
      HakemusAnswers(
        osaaminen = None,
        henkilotiedot = Some(
          HakemusHenkilotiedot(
            kansalaisuus =  None,
            asuinmaa = None,
            matkapuhelinnumero1 = None,
            matkapuhelinnumero2 = None,
            Sukunimi = Some("Mäkinen"),
            Henkilotunnus = Some("200394-9839"),
            Postinumero = None,
            osoiteUlkomaa = None,
            postinumeroUlkomaa = None,
            kaupunkiUlkomaa = None,
            lahiosoite = None,
            sukupuoli = Some("1"),
            Sähköposti = Some("mikko@testi.oph.fi"),
            Kutsumanimi = Some("Mikko"),
            Etunimet = Some("Mikko"),
            kotikunta = None,
            aidinkieli = Some("FI"),
            syntymaaika = Some("20.03.1994"),
            onkoSinullaSuomalainenHetu = None,
            koulusivistyskieli = None,
            turvakielto = None)),
        koulutustausta = None,
        hakutoiveet =  Some(Map(
          "preference1-Koulutus-id" -> "1.2.246.562.20.41053753277",
          "preference1-Opetuspiste-id" -> "1.10.6",
          "preference1-Opetuspiste-id-parents" -> "1.10.1,1.2.246.562.10.00000000001")),
        lisatiedot = None)),
    state = Some("ACTIVE"),
    preferenceEligibilities = Seq(),
    attachmentRequests = Seq()
  )

  object VanhentuneenHaunHakemus extends FullHakemus("1.25.10", Some("1.24.10"), "1.3.10",
    answers = Some(
      HakemusAnswers(
        osaaminen = None,
        henkilotiedot = Some(
          HakemusHenkilotiedot(
            kansalaisuus =  None,
            asuinmaa = None,
            matkapuhelinnumero1 = None,
            matkapuhelinnumero2 = None,
            Sukunimi = Some("Mäkinen"),
            Henkilotunnus = Some("200394-9837"),
            Postinumero = None,
            osoiteUlkomaa = None,
            postinumeroUlkomaa = None,
            kaupunkiUlkomaa = None,
            lahiosoite = None,
            sukupuoli = Some("1"),
            Sähköposti = Some("mikko@testi.oph.fi"),
            Kutsumanimi = Some("Mikko"),
            Etunimet = Some("Mikko"),
            kotikunta = None,
            aidinkieli = Some("FI"),
            syntymaaika = Some("20.03.1994"),
            onkoSinullaSuomalainenHetu = None,
            koulusivistyskieli = None,
            turvakielto = None)),
        koulutustausta = None,
        hakutoiveet =  Some(Map(
          "preference1-Koulutus-id" -> "1.2.246.562.20.41053753277",
          "preference1-Opetuspiste-id" -> "1.10.6",
          "preference1-Opetuspiste-id-parents" -> "1.10.1,1.2.246.562.10.00000000001")),
        lisatiedot = None)),
    state = Some("ACTIVE"),
    preferenceEligibilities = Seq(),
    attachmentRequests = Seq()
  )

  object notEmpty

  implicit def fullHakemus2SmallHakemus(h: FullHakemus): ListHakemus = {
    ListHakemus(h.oid)
  }

  import _root_.akka.pattern.ask
  implicit val system = ActorSystem(s"test-system-${Platform.currentTime.toString}")
  implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout = Timeout(60, TimeUnit.SECONDS)

  object Hakupalvelu extends Hakupalvelu {
    var tehdytHakemukset: Seq[FullHakemus] = Seq()
    var lisakysymykset: Map[String, ThemeQuestion] = Map()
    val koosteData: Option[Map[String,String]] = None

    override def getHakijat(q: HakijaQuery): Future[Seq[Hakija]] = q.organisaatio match {
      case Some(org) => {
        Future(hakijat.filter(_.hakemus.hakutoiveet.exists(_.hakukohde.koulutukset.exists((kohde) => {kohde.tarjoaja == org}))))
      }
      case _ => Future(hakijat)
    }

    val haku = Haku(
      Kieliversiot(Some("haku"), None, None),
      "1.2",
      Ajanjakso(new DateTime(), InFuture),
      "kausi_s#1",
      2014,
      Some("kausi_k#1"),
      Some(2015), kkHaku = false, None, None)

    private val kansalaisuuskoodit = Map("246" -> "FIN")

    def hakijat: Seq[Hakija] = {
      tehdytHakemukset.map(h => AkkaHakupalvelu.getHakija(h, haku, lisakysymykset, Option.empty, koosteData, kansalaisuuskoodit))
    }

    def find(q: HakijaQuery): Future[Seq[ListHakemus]] = q.organisaatio match {
      case Some(OpetuspisteX.oid) => Future(Seq(FullHakemus1))
      case Some(OpetuspisteY.oid) => Future(Seq(FullHakemus2))
      case Some(OppilaitosZ.oid) => Future(Seq(SynteettinenHakemus))
      case Some(_) => Future(Seq[ListHakemus]())
      case None => Future(Seq(FullHakemus1, FullHakemus2))
    }

    def get(hakemusOid: String, user: Option[User]): Future[Option[FullHakemus]] = hakemusOid match {
      case "1.25.1" => Future(Some(FullHakemus1))
      case "1.25.2" => Future(Some(FullHakemus2))
      case "1.25.3" => Future(Some(SynteettinenHakemus))
      case "1.25.5" => Future(Some(FullHakemus5))
      case "1.25.10" => Future(Some(VanhentuneenHaunHakemus))
      case default => Future(None)
    }

    def is(token:Any): Unit = token match {
      case notEmpty => has(FullHakemus1, FullHakemus2)
    }

    def has(hakemukset: FullHakemus*): Unit = {
      tehdytHakemukset = hakemukset
    }

    def withLisakysymykset (lisakysymykset: Map[String, ThemeQuestion]): Unit = {
      this.lisakysymykset = lisakysymykset
    }

    override def getHakukohdeOids(hakukohderyhma: String, hakuOid: String): Future[Seq[String]] = Future.successful(Seq("1.2.246.562.20.14800254899", "1.2.246.562.20.44085996724"))
  }

  class MockedOrganisaatioActor extends Actor {
    import akka.pattern.pipe
    override def receive: Receive = {
      case OppilaitosX.oid => Future.successful(Some(OppilaitosX)) pipeTo sender
      case OppilaitosY.oid => Future.successful(Some(OppilaitosY)) pipeTo sender
      case OppilaitosZ.oid => Future.successful(Some(OppilaitosZ)) pipeTo sender
      case OpetuspisteX.oid => Future.successful(Some(OpetuspisteX)) pipeTo sender
      case OpetuspisteY.oid => Future.successful(Some(OpetuspisteY)) pipeTo sender
      case AtaruOpetuspiste1.oid => Future.successful(Some(AtaruOpetuspiste1)) pipeTo sender
      case AtaruOpetuspiste2.oid => Future.successful(Some(AtaruOpetuspiste2)) pipeTo sender
      case default => Future.successful(None) pipeTo sender
    }
  }

  val organisaatioActor = system.actorOf(Props(new MockedOrganisaatioActor()))

  val kausiKoodiK = TarjontaKoodi(Some("K"))
  val koulutus1 = Hakukohteenkoulutus("1.5.6", "123456", Some("AABB5tga"), Some(kausiKoodiK), Some(2015), None)
  val ataruHakukohde1 = Hakukohde("1.2.246.562.20.14800254899", Seq(), None, Some(Set("1.2.246.562.10.39920288212")))
  val ataruHakukohde2 = Hakukohde("1.2.246.562.20.44085996724", Seq(), None, Some(Set("1.2.246.562.10.2014041814420657444022")))

  def getHakukohde(oid: String): Option[Hakukohde] = oid match {
    case "1.2.246.562.20.14800254899" => Some(ataruHakukohde1)
    case "1.2.246.562.20.44085996724" => Some(ataruHakukohde2)
  }

  class MockedTarjontaActor extends Actor {
    override def receive: Actor.Receive = {
      case oid: HakukohdeOid => sender ! HakukohteenKoulutukset(oid.oid, Some("joku tunniste"), Seq(koulutus1))
      case q: HakukohdeQuery => sender ! getHakukohde(q.oid)
    }
  }

  class MockedKoodistoActor extends Actor {
    override def receive: Actor.Receive = {
      case GetRinnasteinenKoodiArvoQuery("maatjavaltiot1", "fin", _) => sender ! "246"
      case GetRinnasteinenKoodiArvoQuery("maatjavaltiot2", "246", _) => sender ! "FIN"
      case GetRinnasteinenKoodiArvoQuery("maatjavaltiot1", "nan", _) => sender ! "999"
      case q: GetKoodi =>
        sender ! Some(Koodi(q.koodiUri.split("_").last.split("#").head.toUpperCase, q.koodiUri, Koodisto(q.koodistoUri), Seq(KoodiMetadata(q.koodiUri.capitalize, "FI"))))
    }
  }

  val koodistoActor = system.actorOf(Props(new MockedKoodistoActor()))

  val valintatulokset = Future.successful(
    Seq(
      ValintaTulos(
        FullHakemus1.oid,
        Seq(
          ValintaTulosHakutoive(
            "1.11.2",
            "1.10.4",
            Valintatila.HYVAKSYTTY,
            Vastaanottotila.VASTAANOTTANUT,
            HakutoiveenIlmoittautumistila(Ilmoittautumistila.EI_TEHTY),
            None
          ),
          ValintaTulosHakutoive(
            "1.11.1",
            "1.10.3",
            Valintatila.PERUUTETTU,
            Vastaanottotila.KESKEN,
            HakutoiveenIlmoittautumistila(Ilmoittautumistila.EI_TEHTY),
            None
          )
        )
      ),
      ValintaTulos(
        FullHakemus2.oid,
        Seq(
          ValintaTulosHakutoive(
            "1.11.1",
            "1.10.5",
            Valintatila.KESKEN,
            Vastaanottotila.KESKEN,
            HakutoiveenIlmoittautumistila(Ilmoittautumistila.EI_TEHTY),
            None
          ),
          ValintaTulosHakutoive(
            "1.11.2",
            "1.10.4",
            Valintatila.KESKEN,
            Vastaanottotila.KESKEN,
            HakutoiveenIlmoittautumistila(Ilmoittautumistila.EI_TEHTY),
            None
          )
        )
      ),
      ValintaTulos(
        FullHakemus5.oid,
        Seq(
          ValintaTulosHakutoive(
            "1.11.5",
            "1.10.4",
            Valintatila.HYVAKSYTTY,
            Vastaanottotila.VASTAANOTTANUT,
            HakutoiveenIlmoittautumistila(Ilmoittautumistila.EI_TEHTY),
            None
          )
        )
      )
    )
  )

  val sijoitteluClient = mock[VirkailijaRestClient]
  sijoitteluClient.readObject[Seq[ValintaTulos]]("valinta-tulos-service.haku", "1.1")(200) returns valintatulokset
  sijoitteluClient.readObject[Seq[ValintaTulos]]("valinta-tulos-service.haku", "1.2")(200) returns valintatulokset
  sijoitteluClient.readObject[Seq[ValintaTulos]]("valinta-tulos-service.haku", "1.3")(200) returns valintatulokset

  val sijoittelu = system.actorOf(Props(new ValintaTulosActor(sijoitteluClient, new MockConfig, initOnStartup = true)))

  object testHakijaResource {
    implicit val swagger: Swagger = new HakurekisteriSwagger

    val hakijaActor = system.actorOf(Props(new HakijaActor(Hakupalvelu, organisaatioActor, koodistoActor, sijoittelu)))

    def get(q: HakijaQuery) = {
      hakijaActor ? q
    }
  }
}
