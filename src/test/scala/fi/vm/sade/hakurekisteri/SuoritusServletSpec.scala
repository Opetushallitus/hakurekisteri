package fi.vm.sade.hakurekisteri

import org.scalatra.test.scalatest.{ScalatraFunSuite, ScalatraFlatSpec}
import akka.actor.{Props, ActorSystem}
import java.util.Date
import fi.vm.sade.hakurekisteri.rest.{HakurekisteriSwagger, SuoritusServlet}
import fi.vm.sade.hakurekisteri.actor.SuoritusActor
import fi.vm.sade.hakurekisteri.domain.{Peruskoulu, Suoritus}

class SuoritusServletSpec extends ScalatraFunSuite {
  val suoritus = Peruskoulu("1.2.3", "KESKEN", "9", new Date(), "9D", "1.2.4")
  val system = ActorSystem()
  val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(Seq(suoritus))))
  implicit val swagger = new HakurekisteriSwagger

  addServlet(new SuoritusServlet(system, suoritusRekisteri), "/*")

  test("get root should return 200") {
    get("/") {
      status should equal (200)
    }
  }
}
