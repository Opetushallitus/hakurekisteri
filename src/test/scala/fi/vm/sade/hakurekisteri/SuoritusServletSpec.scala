package fi.vm.sade.hakurekisteri

import org.scalatra.test.scalatest.{ScalatraFunSuite, ScalatraFlatSpec}
import akka.actor.{Props, ActorSystem}
import java.util.Date
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriSwagger
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusActor, Peruskoulu, SuoritusServlet}
import org.joda.time.DateTime

class SuoritusServletSpec extends ScalatraFunSuite {
  val suoritus = Peruskoulu("1.2.3", "KESKEN",  DateTime.now,"1.2.4")
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
