package fi.vm.sade.hakurekisteri.rest

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.ensikertalainen.{Ensikertalainen, EnsikertalainenActor, KkVastaanotto, Testihaku}
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, HakuNotFoundException}
import fi.vm.sade.hakurekisteri.integration.henkilo.MockOppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.integration.tarjonta.{GetKomoQuery, KomoResponse, TarjontaActorRef}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{EnsimmainenVastaanotto, ValintarekisteriActorRef, ValintarekisteriQuery}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.OpiskeluoikeusHenkilotQuery
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.suoritus.SuoritusHenkilotQuery
import fi.vm.sade.hakurekisteri.web.ensikertalainen.EnsikertalainenResource
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.joda.time.DateTime
import org.json4s.jackson.Serialization._
import org.mockito.{ArgumentMatchers, Matchers, Mockito}
import org.scalatest.mockito.MockitoSugar
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.{Await, ExecutionContext, Future}

class EnsikertalainenResourceSpec extends ScalatraFunSuite with MockitoSugar {

  implicit val system = ActorSystem("ensikertalainen-resource-test-system")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val security = new TestSecurity
  implicit val swagger = new HakurekisteriSwagger
  implicit val formats = HakurekisteriJsonSupport.format

  val vastaanottohetki = new DateTime(2015, 1, 1, 0, 0, 0, 0)
  val hakemusServiceMock = mock[IHakemusService]

  addServlet(new EnsikertalainenResource(ensikertalainenActor = system.actorOf(Props(new EnsikertalainenActor(
    suoritusActor = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case q: SuoritusHenkilotQuery =>
          sender ! Seq()
      }
    })),
    opiskeluoikeusActor = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case q: OpiskeluoikeusHenkilotQuery =>
          sender ! Seq()
      }
    })),
    valintarekisterActor = new ValintarekisteriActorRef(system.actorOf(Props(new Actor {
      override def receive: Actor.Receive = {
        case q: ValintarekisteriQuery =>
          val map: Seq[EnsimmainenVastaanotto] = q.personOidsWithAliases.henkiloOids.toSeq.map(o => EnsimmainenVastaanotto(o, Some(vastaanottohetki)))
          sender ! map
      }
    }))),
    tarjontaActor = new TarjontaActorRef(system.actorOf(Props(new Actor {
      override def receive: Actor.Receive = {
        case q: GetKomoQuery => sender ! KomoResponse(q.oid, None)
      }
    }))),
    config = new MockConfig,
    hakuActor = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case q: GetHaku if q.oid == "notfound" => Future.failed(HakuNotFoundException(s"haku not found with oid ${q.oid}")) pipeTo sender
        case q: GetHaku => sender ! Testihaku
      }
    })),
    hakemusService = hakemusServiceMock,
    oppijaNumeroRekisteri = MockOppijaNumeroRekisteri
  ))), hakemusService = hakemusServiceMock), "/ensikertalainen")

  test("returns 200 ok") {
    get("/ensikertalainen?henkilo=foo&haku=1.2.3.4") {
      response.status should be (200)
    }
  }

  test("returns ensikertalainen false") {
    get("/ensikertalainen?henkilo=foo&haku=1.2.3.4") {
      read[Ensikertalainen](response.body).ensikertalainen should be (false)
    }
  }

  test("returns ensikertalaisuus lost by KkVastaanotto") {
    get("/ensikertalainen?henkilo=foo&haku=1.2.3.4") {
      val e = read[Ensikertalainen](response.body)
      e.menettamisenPeruste.map(_.peruste) should be (Some("KkVastaanotto"))
      e.menettamisenPeruste.map(_.asInstanceOf[KkVastaanotto].paivamaara.toString) should be (Some(vastaanottohetki.toString))
    }
  }

  test("returns a sequence of ensikertalaisuus") {
    post("/ensikertalainen?haku=1.2.3.4", """["foo", "bar", "foo"]""") {
      val e = read[Seq[Ensikertalainen]](response.body)
      e.size should be (2)
    }
  }

  test("returns ensikertalaisuus for all hakijas in haku") {
    Mockito.when(hakemusServiceMock.suoritusoikeudenTaiAiemmanTutkinnonVuosi(ArgumentMatchers.anyString, ArgumentMatchers.any[Option[String]]))
      .thenReturn(Future.successful(Seq[FullHakemus](
        FullHakemus("1", Some("1"), "1.2.3", None, None, Seq(), Seq()),
        FullHakemus("2", Some("2"), "1.2.3", None, None, Seq(), Seq()),
        FullHakemus("3", Some("3"), "1.2.3", None, None, Seq(), Seq())
      )))
    get("/ensikertalainen/haku/1.2.3") {
      val e = read[Seq[Ensikertalainen]](response.body)
      e.size should be (3)
    }
  }

  test("returns 404 if haku not found") {
    Mockito.when(hakemusServiceMock.suoritusoikeudenTaiAiemmanTutkinnonVuosi(ArgumentMatchers.anyString, ArgumentMatchers.any[Option[String]]))
      .thenReturn(Future.successful(Seq[FullHakemus]()))
    get("/ensikertalainen/haku/notfound") {
      response.status should be(404)
    }
  }

  protected override def beforeAll() = {
    Mockito.when(hakemusServiceMock.hakemuksetForPersonsInHaku(ArgumentMatchers.any[Set[String]], ArgumentMatchers.anyString()))
      .thenReturn(Future.successful(Seq[FullHakemus]()))
    super.beforeAll()
  }

  override def stop(): Unit = {
    import scala.concurrent.duration._
    Await.result(system.terminate(), 15.seconds)
    super.stop()
  }

}
