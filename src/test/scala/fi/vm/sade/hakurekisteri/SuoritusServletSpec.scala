package fi.vm.sade.hakurekisteri

import org.scalatra.test.scalatest.{ScalatraFunSuite, ScalatraFlatSpec}
import akka.actor.{Props, ActorSystem}
import java.util.Date
import fi.vm.sade.hakurekisteri.rest.SuoritusServlet
import fi.vm.sade.hakurekisteri.actor.SuoritusActor
import fi.vm.sade.hakurekisteri.domain.{Peruskoulu, Suoritus}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriSwagger

class SuoritusServletSpec extends ScalatraFunSuite {
  val suoritus = Peruskoulu("1.2.3", "KESKEN",  new Date(),"1.2.4")
  implicit val system = ActorSystem()
  val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(Seq(suoritus))))
  implicit val swagger = new HakurekisteriSwagger

  addServlet(new SuoritusServlet(suoritusRekisteri), "/*")

  test("get root should return 200") {
    get("/") {
      status should equal (200)
    }
  }
}
