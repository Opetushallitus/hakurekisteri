package fi.vm.sade.hakurekisteri.kkhakija

import akka.actor.{Actor, Props}
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.TestSecurity
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.dates.{Ajanjakso, InFuture}
import fi.vm.sade.hakurekisteri.hakija.{Puuttuu, Syksy, _}
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusActor}
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, Haku, Kieliversiot}
import fi.vm.sade.hakurekisteri.integration.koodisto._
import fi.vm.sade.hakurekisteri.integration.tarjonta.{HakukohdeOid, HakukohteenKoulutukset, Hakukohteenkoulutus, RestHaku, RestHakuAika}
import fi.vm.sade.hakurekisteri.integration.valintatulos.Ilmoittautumistila.Ilmoittautumistila
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila.Valintatila
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila.Vastaanottotila
import fi.vm.sade.hakurekisteri.integration.valintatulos.{ValintaTulosQuery, _}
import fi.vm.sade.hakurekisteri.integration.ytl.YTLXml
import fi.vm.sade.hakurekisteri.integration.{CapturingProvider, Endpoint, ServiceConfig, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.rest.support.User
import fi.vm.sade.hakurekisteri.storage.repository.{InMemJournal, Journal, Updated}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritysTyyppiQuery, VirallinenSuoritus}
import fi.vm.sade.hakurekisteri.web.kkhakija.{KkHakijaQuery, KkHakijaResource}
import fi.vm.sade.hakurekisteri.web.rest.support.HakurekisteriSwagger
import org.joda.time.LocalDate
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.Await
import scala.concurrent.duration._

class KkHakijaResourceSpec extends ScalatraFunSuite with HakeneetSupport {
  implicit val swagger: Swagger = new HakurekisteriSwagger

  val asyncProvider = new CapturingProvider(mock[Endpoint])
  val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/haku-app"), aClient = Some(new AsyncHttpClient(asyncProvider)))
  val hakemusJournal: Journal[FullHakemus, String] = seq2journal(Seq(FullHakemus1, FullHakemus2))
  val hakemusMock = system.actorOf(Props(new HakemusActor(hakemusClient = client, journal = hakemusJournal)))
  val tarjontaMock = system.actorOf(Props(new MockedTarjontaActor()))
  val hakuMock = system.actorOf(Props(new MockedHakuActor()))
  val suoritusMock = system.actorOf(Props(new MockedSuoritusActor()))
  val valintaTulosMock = system.actorOf(Props(new MockedValintaTulosActor()))
  val koodistoMock = system.actorOf(Props(new MockedKoodistoActor()))

  val resource = new KkHakijaResource(hakemusMock, tarjontaMock, hakuMock, koodistoMock, suoritusMock, valintaTulosMock) with TestSecurity
  addServlet(resource, "/")




  test("should return 200 OK") {
    get("/?hakukohde=1.11.1") {
      status should be (200)
    }
  }

  test("should return 400 Bad Request if no parameters given") {
    get("/") {
      status should be (400)
    }
  }

  test("should not return results if user not in hakukohde organization hierarchy") {
    val hakijat = Await.result(resource.getKkHakijat(KkHakijaQuery(None, None, None, None, Hakuehto.Kaikki, Some(testUser("test", "1.1")))), 15.seconds)

    hakijat.size should be (0)
  }

  test("should return two hakijas") {
    val hakijat = Await.result(resource.getKkHakijat(KkHakijaQuery(None, None, None, None, Hakuehto.Kaikki, Some(testUser("test", "1.2.246.562.10.00000000001")))), 15.seconds)

    hakijat.size should be (2)
  }

  test("should return one hyvaksytty hakija") {
    val hakijat = Await.result(resource.getKkHakijat(KkHakijaQuery(None, None, None, None, Hakuehto.Hyvaksytyt, Some(testUser("test", "1.2.246.562.10.00000000001")))), 15.seconds)

    hakijat.size should be (1)
  }

  test("should convert ilmoittautumiset into sequence in syksyn haku") {
    val haku = Haku(
      nimi = Kieliversiot(fi = Some("joo"), sv = None, en = None),
      oid = "1.2.3",
      aika = Ajanjakso(alkuPaiva = new LocalDate(), loppuPaiva = None),
      kausi = "kausi_s#1",
      vuosi = 2014,
      koulutuksenAlkamiskausi = Some("kausi_k#1"),
      koulutuksenAlkamisvuosi = Some(2015),
      kkHaku = true
    )
    val sijoitteluTulos = new SijoitteluTulos {
      override def ilmoittautumistila(hakemus: String, kohde: String): Option[Ilmoittautumistila] = Some(Ilmoittautumistila.EI_TEHTY)
      override def vastaanottotila(hakemus: String, kohde: String): Option[Vastaanottotila] = Some(Vastaanottotila.KESKEN)
      override def valintatila(hakemus: String, kohde: String): Option[Valintatila] = Some(Valintatila.KESKEN)
      override def pisteet(hakemus: String, kohde: String): Option[BigDecimal] = Some(BigDecimal(4.0))
    }
    val ilmoittautumiset: Seq[Lasnaolo] = Await.result(resource.getLasnaolot(sijoitteluTulos, "1.5.1", haku, ""), 15.seconds)

    ilmoittautumiset should (contain(Puuttuu(Syksy(2015))) and contain(Puuttuu(Kevat(2015))))
  }

  test("should convert ilmoittautumiset into sequence in kevään haku") {
    val haku = Haku(
      nimi = Kieliversiot(fi = Some("joo"), sv = None, en = None),
      oid = "1.2.3",
      aika = Ajanjakso(alkuPaiva = new LocalDate(), loppuPaiva = None),
      kausi = "kausi_k#1",
      vuosi = 2015,
      koulutuksenAlkamiskausi = Some("kausi_s#1"),
      koulutuksenAlkamisvuosi = Some(2015),
      kkHaku = true
    )
    val sijoitteluTulos = new SijoitteluTulos {
      override def ilmoittautumistila(hakemus: String, kohde: String): Option[Ilmoittautumistila] = Some(Ilmoittautumistila.LASNA_SYKSY)
      override def vastaanottotila(hakemus: String, kohde: String): Option[Vastaanottotila] = Some(Vastaanottotila.KESKEN)
      override def valintatila(hakemus: String, kohde: String): Option[Valintatila] = Some(Valintatila.KESKEN)
      override def pisteet(hakemus: String, kohde: String): Option[BigDecimal] = Some(BigDecimal(4.0))
    }
    val ilmoittautumiset = Await.result(resource.getLasnaolot(sijoitteluTulos, "1.5.1", haku, ""), 15.seconds)

    ilmoittautumiset should (contain(Lasna(Syksy(2015))) and contain(Poissa(Kevat(2016))))
  }

  test("should show turvakielto true from hakemus") {
    val hakijat = Await.result(resource.getKkHakijat(KkHakijaQuery(Some("1.24.1"), None, None, None, Hakuehto.Kaikki, Some(testUser("test", "1.2.246.562.10.00000000001")))), 15.seconds)

    hakijat.head.turvakielto should be (true)
  }

  test("should return turvakielto false from hakemus") {
    val hakijat = Await.result(resource.getKkHakijat(KkHakijaQuery(Some("1.24.2"), None, None, None, Hakuehto.Kaikki, Some(testUser("test", "1.2.246.562.10.00000000001")))), 15.seconds)

    hakijat.head.turvakielto should be (false)
  }

  test("should return empty hakukelpoisuus by default") {
    val hakijat = Await.result(resource.getKkHakijat(KkHakijaQuery(Some("1.24.2"), None, None, None, Hakuehto.Kaikki, Some(testUser("test", "1.2.246.562.10.00000000001")))), 15.seconds)

    hakijat.head.hakemukset.foreach(hak => hak.hKelpoisuus should be (""))
  }

  test("should return hakukelpoisuus from hakemus") {
    val hakijat = Await.result(resource.getKkHakijat(KkHakijaQuery(Some("1.24.1"), None, None, None, Hakuehto.Kaikki, Some(testUser("test", "1.2.246.562.10.00000000001")))), 15.seconds)

    hakijat.head.hakemukset.foreach(hak => hak.hKelpoisuus should be ("NOT_CHECKED"))
  }

  test("should return kotikunta 200 if it is not defined in hakemus") {
    val hakijat = Await.result(resource.getKkHakijat(KkHakijaQuery(Some("1.24.2"), None, None, None, Hakuehto.Kaikki, Some(testUser("test", "1.2.246.562.10.00000000001")))), 15.seconds)

    hakijat.head.kotikunta should be ("200")
  }

  test("should return kotikunta from hakemus") {
    val hakijat = Await.result(resource.getKkHakijat(KkHakijaQuery(Some("1.24.1"), None, None, None, Hakuehto.Kaikki, Some(testUser("test", "1.2.246.562.10.00000000001")))), 15.seconds)

    hakijat.head.kotikunta should be ("098")
  }



  def testUser(user: String, organisaatioOid: String) = new User {
    override val username: String = user
    override def orgsFor(action: String, resource: String): Set[String] = Set(organisaatioOid)
  }

  import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen._

  val haku1 = RestHaku(Some("1.2"), List(RestHakuAika(1L)), Map("fi" -> "testihaku"), "kausi_s#1", 2014, Some("kausi_k#1"), Some(2015), Some("haunkohdejoukko_12#1"), "JULKAISTU")
  val koulutus1 = Hakukohteenkoulutus("1.5.6", "123456", Some("AABB5tga"))
  val suoritus1 = VirallinenSuoritus(YTLXml.yotutkinto, YTLXml.YTL, "VALMIS", new LocalDate(), "1.2.3", Ei, "FI", None, true, "1")

  def seq2journal(s: Seq[FullHakemus]) = {
    val journal = new InMemJournal[FullHakemus, String]
    s.foreach((h: FullHakemus) => {
      journal.addModification(Updated[FullHakemus, String](h.identify(h.oid)))
    })
    journal
  }

  class MockedTarjontaActor extends Actor {
    override def receive: Actor.Receive = {
      case oid: HakukohdeOid =>  sender ! HakukohteenKoulutukset(oid.oid, Some("joku tunniste"), Seq(koulutus1))
    }
  }

  class MockedHakuActor extends Actor {
    override def receive: Actor.Receive = {
      case q: GetHaku =>  sender ! Haku(haku1)(InFuture)
    }
  }

  class MockedSuoritusActor extends Actor {
    override def receive: Actor.Receive = {
      case q: SuoritysTyyppiQuery => sender ! Seq(suoritus1)
    }
  }

  class MockedValintaTulosActor extends Actor {
    override def receive: Actor.Receive = {
      case q: ValintaTulosQuery if q.hakuOid == FullHakemus1.applicationSystemId =>
        sender ! new SijoitteluTulos {
          override def ilmoittautumistila(hakemus: String, kohde: String): Option[Ilmoittautumistila] = Some(Ilmoittautumistila.EI_TEHTY)
          override def vastaanottotila(hakemus: String, kohde: String): Option[Vastaanottotila] = Some(Vastaanottotila.KESKEN)
          override def valintatila(hakemus: String, kohde: String): Option[Valintatila] = Some(Valintatila.HYVAKSYTTY)
          override def pisteet(hakemus: String, kohde: String): Option[BigDecimal] = Some(BigDecimal(4.0))
        }
      case q: ValintaTulosQuery =>
        sender ! new SijoitteluTulos {
          override def ilmoittautumistila(hakemus: String, kohde: String): Option[Ilmoittautumistila] = None
          override def vastaanottotila(hakemus: String, kohde: String): Option[Vastaanottotila] = None
          override def valintatila(hakemus: String, kohde: String): Option[Valintatila] = None
          override def pisteet(hakemus: String, kohde: String): Option[BigDecimal] = None
        }
    }
  }

  class MockedKoodistoActor extends Actor {
    override def receive: Actor.Receive = {
      case q: GetRinnasteinenKoodiArvoQuery => sender ! "246"
      case q: GetKoodi =>  sender ! Some(Koodi(q.koodiUri.split("_").last.split("#").head.toUpperCase, q.koodiUri, Koodisto(q.koodistoUri), Seq(KoodiMetadata(q.koodiUri, "FI"))))
      case q: GetKoodistoKoodiArvot => q.koodistoUri match {
        case "oppiaineetyleissivistava" => sender ! KoodistoKoodiArvot(
          koodistoUri = "oppiaineetyleissivistava",
          arvot = Seq("AI", "A1", "A12", "A2", "A22", "B1", "B2", "B22", "B23", "B3", "B32", "B33", "BI", "FI","FY", "GE", "HI", "KE", "KO", "KS", "KT", "KU", "LI", "MA", "MU", "PS", "TE", "YH")
        )
      }
    }
  }

  override def stop(): Unit = {
    system.shutdown()
    system.awaitTermination(15.seconds)
    super.stop()
  }
}

