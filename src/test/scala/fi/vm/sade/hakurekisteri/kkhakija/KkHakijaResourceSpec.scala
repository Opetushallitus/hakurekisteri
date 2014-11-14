package fi.vm.sade.hakurekisteri.kkhakija

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, Props}
import fi.vm.sade.hakurekisteri.acceptance.tools.{TestSecurity, HakeneetSupport}
import fi.vm.sade.hakurekisteri.dates.{Ajanjakso, InFuture}
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusQuery
import fi.vm.sade.hakurekisteri.integration.haku.{Kieliversiot, Haku, GetHaku}
import fi.vm.sade.hakurekisteri.integration.koodisto._
import fi.vm.sade.hakurekisteri.integration.tarjonta._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Ilmoittautumistila.Ilmoittautumistila
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila.Valintatila
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila.Vastaanottotila
import fi.vm.sade.hakurekisteri.integration.valintatulos._
import fi.vm.sade.hakurekisteri.integration.ytl.YTLXml
import fi.vm.sade.hakurekisteri.rest.support.{User, HakurekisteriSwagger}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritysTyyppiQuery, VirallinenSuoritus}
import org.joda.time.LocalDate
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class KkHakijaResourceSpec extends ScalatraFunSuite with HakeneetSupport {
  implicit val swagger: Swagger = new HakurekisteriSwagger

  val hakemusMock = system.actorOf(Props(new MockedHakemusActor()))
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

  test("should not return hakijas if user not in hakukohde organization hierarchy") {
    object TestUser extends User {
      override val username: String = "test"
      override def orgsFor(action: String, resource: String): Set[String] = Set("1.1")
    }
    val q = KkHakijaQuery(None, None, None, None, Hakuehto.Kaikki, Some(TestUser))
    val res: Future[Seq[Hakija]] = resource.getKkHakijat(q)
    val hakijat = Await.result(res, Duration(10, TimeUnit.SECONDS))
    hakijat.size should be (0)
  }

  test("should return two hakijas") {
    object TestUser extends User {
      override val username: String = "test"
      override def orgsFor(action: String, resource: String): Set[String] = Set("1.2.246.562.10.00000000001")
    }
    val q = KkHakijaQuery(None, None, None, None, Hakuehto.Kaikki, Some(TestUser))
    val res: Future[Seq[Hakija]] = resource.getKkHakijat(q)
    val hakijat = Await.result(res, Duration(10, TimeUnit.SECONDS))
    hakijat.size should be (2)
  }

  test("should return one hyvaksytty hakija") {
    object TestUser extends User {
      override val username: String = "test"
      override def orgsFor(action: String, resource: String): Set[String] = Set("1.2.246.562.10.00000000001")
    }
    val q = KkHakijaQuery(None, None, None, None, Hakuehto.Hyvaksytyt, Some(TestUser))
    val res: Future[Seq[Hakija]] = resource.getKkHakijat(q)
    val hakijat = Await.result(res, Duration(10, TimeUnit.SECONDS))
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
    val f = resource.getLasnaolot(sijoitteluTulos, "1.5.1", haku, "")

    val ilmoittautumiset: Seq[Lasnaolo] = Await.result(f, Duration(10, TimeUnit.SECONDS))

    ilmoittautumiset should (contain(Puuttuu(Syksy(2014))) and contain(Puuttuu(Kevat(2015))))
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

    val f = resource.getLasnaolot(sijoitteluTulos, "1.5.1", haku, "")

    val ilmoittautumiset = Await.result(f, Duration(10, TimeUnit.SECONDS))

    ilmoittautumiset should (contain(Lasna(Syksy(2015))) and contain(Poissa(Kevat(2015))))
  }

  import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen._

  val haku1 = RestHaku(Some("1.2"), List(RestHakuAika(1L)), Map("fi" -> "testihaku"), "kausi_s#1", 2014, Some("kausi_k#1"), Some(2015), Some("kohdejoukko_12#1"), "JULKAISTU")
  val koulutus1 = Hakukohteenkoulutus("1.5.6", "123456", Some("AABB5tga"))
  val suoritus1 = VirallinenSuoritus(YTLXml.yotutkinto, YTLXml.YTL, "VALMIS", new LocalDate(), "1.2.3", Ei, "FI", None, true, "1")

  class MockedHakemusActor extends Actor {
    override def receive: Receive = {
      case q: HakemusQuery =>  sender ! Seq(FullHakemus1, FullHakemus2)
    }
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
    }
  }
}

