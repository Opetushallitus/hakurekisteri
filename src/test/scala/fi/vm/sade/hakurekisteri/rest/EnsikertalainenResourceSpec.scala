package fi.vm.sade.hakurekisteri.rest

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.ensikertalainen.{Ensikertalainen, EnsikertalainenActor, KkVastaanotto, Testihaku}
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, Hakemus, HakemusQuery, HakemusService}
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, HakuNotFoundException}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{GetKomoQuery, KomoResponse}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{EnsimmainenVastaanotto, ValintarekisteriQuery}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.OpiskeluoikeusHenkilotQuery
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.suoritus.SuoritusHenkilotQuery
import fi.vm.sade.hakurekisteri.web.ensikertalainen.EnsikertalainenResource
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.joda.time.DateTime
import org.json4s.jackson.Serialization._
import org.mockito.{Matchers, Mockito}
import org.scalatest.mock.MockitoSugar
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.{ExecutionContext, Future}

class EnsikertalainenResourceSpec extends ScalatraFunSuite with MockitoSugar {

  implicit val system = ActorSystem("ensikertalainen-resource-test-system")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val security = new TestSecurity
  implicit val swagger = new HakurekisteriSwagger
  implicit val formats = HakurekisteriJsonSupport.format

  val vastaanottohetki = new DateTime(2015, 1, 1, 0, 0, 0, 0)
  val hakemusServiceMock = mock[HakemusService]

  private val hakemusActor: ActorRef = system.actorOf(Props(new Actor {
    override def receive: Receive = {
      case q: HakemusQuery if q.haku.isDefined => sender ! Seq(
        Hakemus().setPersonOid("foo").build,
        Hakemus().setPersonOid("bar").build,
        Hakemus().setPersonOid("zap").build
      )
    }
  }))



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
    valintarekisterActor = system.actorOf(Props(new Actor {
      override def receive: Actor.Receive = {
        case q: ValintarekisteriQuery =>
          val map: Seq[EnsimmainenVastaanotto] = q.henkiloOids.toSeq.map(o => EnsimmainenVastaanotto(o, Some(vastaanottohetki)))
          sender ! map
      }
    })),
    tarjontaActor = system.actorOf(Props(new Actor {
      override def receive: Actor.Receive = {
        case q: GetKomoQuery => sender ! KomoResponse(q.oid, None)
      }
    })),
    config = Config.mockConfig,
    hakuActor = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case q: GetHaku if q.oid == "notfound" => Future.failed(HakuNotFoundException(s"haku not found with oid ${q.oid}")) pipeTo sender
        case q: GetHaku => sender ! Testihaku
      }
    })),
    hakemusService = hakemusServiceMock
  ))), hakemusRekisteri = hakemusActor), "/ensikertalainen")

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
    get("/ensikertalainen/haku/1.2.3") {
      val e = read[Seq[Ensikertalainen]](response.body)
      e.size should be (3)
    }
  }

  test("returns 404 if haku not found") {
    get("/ensikertalainen/haku/notfound") {
      response.status should be(404)
    }
  }

  protected override def beforeAll() = {
    Mockito.when(hakemusServiceMock.hakemuksetForHaku(Matchers.anyString())).thenReturn(Future.successful(Seq[FullHakemus]()))
    super.beforeAll()
  }

  override def stop(): Unit = {
    import scala.concurrent.duration._
    system.shutdown()
    system.awaitTermination(15.seconds)
    super.stop()
  }

}
