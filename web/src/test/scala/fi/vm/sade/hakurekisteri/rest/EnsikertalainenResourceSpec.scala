package fi.vm.sade.hakurekisteri.rest

import akka.actor.{Actor, Props, ActorSystem}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.ensikertalainen.EnsikertalainenActor
import fi.vm.sade.hakurekisteri.integration.tarjonta.{KomoResponse, GetKomoQuery}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.ValintarekisteriQuery
import fi.vm.sade.hakurekisteri.suoritus.SuoritusQuery
import fi.vm.sade.hakurekisteri.web.ensikertalainen.EnsikertalainenResource
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.joda.time.DateTime
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.ExecutionContext

class EnsikertalainenResourceSpec extends ScalatraFunSuite {

  implicit val system = ActorSystem("ensikertalainen-resource-test-system")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val security = new TestSecurity
  implicit val swagger = new HakurekisteriSwagger

  addServlet(new EnsikertalainenResource(system.actorOf(Props(new EnsikertalainenActor(
    suoritusActor = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case q: SuoritusQuery =>
          sender ! Seq()
      }
    })),
    valintarekisterActor = system.actorOf(Props(new Actor {
      override def receive: Actor.Receive = {
        case q: ValintarekisteriQuery => sender ! Some(new DateTime(2015, 1, 1, 0, 0, 0, 0))
      }
    })),
    tarjontaActor = system.actorOf(Props(new Actor {
      override def receive: Actor.Receive = {
        case q: GetKomoQuery => sender ! KomoResponse(q.oid, None)
      }
    })),
    config = Config.mockConfig
  )))), "/")

  test("returns 200 ok") {
    get("/?henkilo=foo") {
      response.status should be (200)
    }
  }

  test("returns ensikertalainen false") {
    get("/?henkilo=foo") {
      response.body should include ("false")
    }
  }

  test("returns ensikertalaisuus lost by KkVastaanotto") {
    get("/?henkilo=foo") {
      response.body should include ("KkVastaanotto")
    }
  }

  override def stop(): Unit = {
    import scala.concurrent.duration._
    system.shutdown()
    system.awaitTermination(15.seconds)
    super.stop()
  }

}
