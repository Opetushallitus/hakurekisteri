package fi.vm.sade.hakurekisteri

import org.scalatra.test.scalatest.{ScalatraFunSuite, ScalatraFlatSpec}
import akka.actor.{Props, ActorSystem}

class SuoritusServletSpec extends ScalatraFunSuite {
  val suoritus = new Suoritus("1.2.3", "KESKEN", "9", "2014", "K", "9D", "1.2.4")
  val system = ActorSystem()
  val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(Seq(suoritus))))
  addServlet(new SuoritusServlet(system, suoritusRekisteri), "/*")

  test("get root should return 200") {
    get("/") {
      status should equal (200)
    }
  }
}
