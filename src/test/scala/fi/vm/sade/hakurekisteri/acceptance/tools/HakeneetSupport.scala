package fi.vm.sade.hakurekisteri.acceptance.tools

import com.stackmob.newman.response.HttpResponseCode
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.hakemus.{AkkaHakupalvelu, Hakupalvelu, ListHakemus, FullHakemus}
import fi.vm.sade.hakurekisteri.integration.koodisto.{Koodisto, Koodi, KoodistoActor}
import fi.vm.sade.hakurekisteri.integration.organisaatio.{Organisaatio, OrganisaatioActor}
import fi.vm.sade.hakurekisteri.integration.sijoittelu._
import org.scalatra.swagger.Swagger
import fi.vm.sade.hakurekisteri.rest.support.{User, HakurekisteriJsonSupport, HakurekisteriSwagger}
import akka.actor.{Props, Actor, ActorSystem}
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import fi.vm.sade.hakurekisteri.hakija.HakijaQuery
import org.scalatest.Suite
import org.scalatra.test.HttpComponentsClient
import org.specs.mock.Mockito
import org.specs.specification.Examples
import scala.concurrent.{Future, ExecutionContext}

trait HakeneetSupport extends Suite with HttpComponentsClient with HakurekisteriJsonSupport with Mockito {
  override def forExample: Examples = ???
  override def lastExample: Option[Examples] = ???

  object OppilaitosX extends Organisaatio("1.10.1", Map("fi" -> "Oppilaitos X"), None, Some("00001"), None)
  object OppilaitosY extends Organisaatio("1.10.2", Map("fi" -> "Oppilaitos Y"), None, Some("00002"), None)

  object OpetuspisteX extends Organisaatio("1.10.3", Map("fi" -> "Opetuspiste X"), Some("0000101"), None, Some("1.10.1"))
  object OpetuspisteZ extends Organisaatio("1.10.5", Map("fi" -> "Opetuspiste Z"), Some("0000101"), None, Some("1.10.1"))
  object OpetuspisteY extends Organisaatio("1.10.4", Map("fi" -> "Opetuspiste Y"), Some("0000201"), None, Some("1.10.2"))

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
  object FullHakemus2 extends FullHakemus("1.25.2", Some("1.24.2"), "1.2",
    Some(Map(
      "henkilotiedot" -> Map(
        "kansalaisuus" -> "FIN",
        "asuinmaa" -> "FIN",
        "matkapuhelinnumero1" -> "0401234568",
        "Sukunimi" -> "Virtanen",
        "Henkilotunnus" -> "200394-959H",
        "Postinumero" -> "00100",
        "lahiosoite" -> "Katu 2",
        "sukupuoli" -> "1",
        "Sähköposti" -> "ville@testi.oph.fi",
        "Kutsumanimi" -> "Ville",
        "Etunimet" -> "Ville",
        "kotikunta" -> "098",
        "aidinkieli" -> "FI",
        "syntymaaika" -> "20.03.1994",
        "onkoSinullaSuomalainenHetu" -> "true"),
      "koulutustausta" -> Map(
        "PK_PAATTOTODISTUSVUOSI" -> "2014",
        "POHJAKOULUTUS" -> "1",
        "perusopetuksen_kieli" -> "FI",
        "lahtokoulu" -> OppilaitosY.oid,
        "lahtoluokka" -> "9A",
        "luokkataso" -> "9"),
      "hakutoiveet" -> Map(
        "preference2-Opetuspiste" -> "Ammattiopisto Loppi2\"",
        "preference2-Opetuspiste-id" -> "1.10.5",
        "preference2-Koulutus" -> "Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)2",
        "preference2-Koulutus-id" -> "1.11.1",
        "preference2-Koulutus-id-aoIdentifier" -> "460",
        "preference2-Koulutus-id-educationcode" -> "koulutus_321204",
        "preference2-Koulutus-id-lang" -> "FI",
        "preference1-Opetuspiste" -> "Ammattiopisto Loppi",
        "preference1-Opetuspiste-id" -> "1.10.4",
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
        "preference1_kaksoistutkinnon_lisakysymys" -> "true"),
      "lisatiedot" -> Map(
        "lupaMarkkinointi" -> "true",
        "lupaJulkaisu" -> "true"))),
    Some("INCOMPLETE")
  )

  object notEmpty

  implicit def fullHakemus2SmallHakemus(h: FullHakemus): ListHakemus = {
    ListHakemus(h.oid)
  }

  import _root_.akka.pattern.ask
  implicit val system = ActorSystem()
  implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout = Timeout(60, TimeUnit.SECONDS)

  object hakupalvelu extends Hakupalvelu {
    var tehdytHakemukset: Seq[FullHakemus] = Seq()

    override def getHakijat(q: HakijaQuery): Future[Seq[Hakija]] = q.organisaatio match {
      case Some(org) => {
        println("Haetaan tarjoajalta %s".format(org))
        Future(hakijat.filter(_.hakemus.hakutoiveet.exists(_.hakukohde.koulutukset.exists((kohde) => {println(kohde);kohde.tarjoaja == org}))))
      }
      case _ => Future(hakijat)
    }

    def hakijat: Seq[Hakija] = {
      tehdytHakemukset.map(AkkaHakupalvelu.getHakija(_))
    }

    def find(q: HakijaQuery): Future[Seq[ListHakemus]] = q.organisaatio match {
      case Some(OpetuspisteX.oid) => Future(Seq(FullHakemus1))
      case Some(OpetuspisteY.oid) => Future(Seq(FullHakemus2))
      case None => Future(Seq(FullHakemus1, FullHakemus2))
    }

    def get(hakemusOid: String, user: Option[User]): Future[Option[FullHakemus]] = hakemusOid match {
      case "1.25.1" => Future(Some(FullHakemus1))
      case "1.25.2" => Future(Some(FullHakemus2))
      case default => Future(None)
    }

    def is(token:Any) = token match {
      case notEmpty => has(FullHakemus1, FullHakemus2)
    }

    def has(hakemukset: FullHakemus*) = {
      tehdytHakemukset = hakemukset
    }
  }

  class MockedOrganisaatioActor extends Actor {
    import akka.pattern.pipe
    override def receive: Receive = {
      case OppilaitosX.oid => Future.successful(Some(OppilaitosX)) pipeTo sender
      case OppilaitosY.oid => Future.successful(Some(OppilaitosY)) pipeTo sender
      case OpetuspisteX.oid => Future.successful(Some(OpetuspisteX)) pipeTo sender
      case OpetuspisteY.oid => Future.successful(Some(OpetuspisteY)) pipeTo sender
      case default => Future.successful(None) pipeTo sender
    }
  }

  val organisaatioActor = system.actorOf(Props(new MockedOrganisaatioActor()))

  val koodistoClient = mock[VirkailijaRestClient]
  koodistoClient.readObject[Seq[Koodi]]("", HttpResponseCode.Ok) returns Future.successful(Seq(Koodi("246", "", Koodisto(""))))
  val koodisto = system.actorOf(Props(new KoodistoActor(koodistoClient)))

  val f = Future.successful(
        SijoitteluPagination(
          Seq(
            SijoitteluHakija(
              hakemusOid = Some(FullHakemus1.oid),
              hakutoiveet=Some(Seq(
                SijoitteluHakutoive(
                  hakutoiveenValintatapajonot = Some(Seq(
                    SijoitteluHakutoiveenValintatapajono(
                      varalla = None,
                      hyvaksytty = None,
                      hakeneet = None,
                      alinHyvaksyttyPistemaara = None,
                      pisteet = Some(26.0),
                      tasasijaJonosija = None,
                      hyvaksyttyHarkinnanvaraisesti = None,
                      vastaanottotieto = None,
                      tilanKuvaukset = None,
                      tila = Some("HYVAKSYTTY"),
                      varasijanNumero = None,
                      paasyJaSoveltuvuusKokeenTulos = None,
                      jonosija = None,
                      valintatapajonoNimi = None,
                      valintatapajonoOid = None,
                      valintatapajonoPrioriteetti = None)
                  )),
                  pistetiedot = None,
                  tarjoajaOid = None,
                  hakukohdeOid = Some("1.11.2"),
                  hakutoive = None))),
              etunimi = None,
              sukunimi = None)),
          1))

  val sijoitteluClient = mock[VirkailijaRestClient]
  sijoitteluClient.readObject[SijoitteluPagination]("/resources/sijoittelu/1.1/sijoitteluajo/latest/hakemukset", HttpResponseCode.Ok) returns f
  sijoitteluClient.readObject[SijoitteluPagination]("/resources/sijoittelu/1.2/sijoitteluajo/latest/hakemukset", HttpResponseCode.Ok) returns f

  object hakijaResource {
    implicit val swagger: Swagger = new HakurekisteriSwagger

    val orgAct = system.actorOf(Props(new MockedOrganisaatioActor()))
    val sijoittelu = system.actorOf(Props(new SijoitteluActor(sijoitteluClient)))
    val hakijaActor = system.actorOf(Props(new HakijaActor(hakupalvelu, orgAct, koodisto, sijoittelu)))

    def get(q: HakijaQuery) = {
      hakijaActor ? q
    }
  }
}
