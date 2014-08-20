package fi.vm.sade.hakurekisteri.integration

import fi.vm.sade.hakurekisteri.integration.koodisto.RestKoodistopalvelu
import org.scalatra.test.scalatest.ScalatraFunSuite
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import akka.actor.ActorSystem

class RestKoodistopalveluSpec extends ScalatraFunSuite {

  implicit val system = ActorSystem()
  implicit def executor: ExecutionContext = system.dispatcher

  val client = new RestKoodistopalvelu("https://itest-virkailija.oph.ware.fi/koodisto-service")

  ignore("koodisto-service should return rinnasteinen koodi") {
    val future: Future[String] = client.getRinnasteinenKoodiArvo("maatjavaltiot1_fin", "maatjavaltiot2")
    val koodiArvo: String = Await.result(future, Duration(length = 5, unit = TimeUnit.SECONDS))
    koodiArvo should equal ("246")
  }

}
