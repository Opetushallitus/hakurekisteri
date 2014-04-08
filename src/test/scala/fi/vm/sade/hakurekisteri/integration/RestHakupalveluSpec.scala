package fi.vm.sade.hakurekisteri.integration

import org.scalatra.test.scalatest.ScalatraFunSuite
import scala.concurrent.{Future, ExecutionContext, Await}
import java.util.concurrent.TimeUnit
import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.hakija.HakijaQuery
import fi.vm.sade.hakurekisteri.rest.support.User
import scala.Some
import scala.concurrent.duration.Duration


class RestHakupalveluSpec extends ScalatraFunSuite {

   implicit val system = ActorSystem()
   implicit def executor: ExecutionContext = system.dispatcher

   val client = new RestHakupalvelu("https://itest-virkailija.oph.ware.fi/koodisto-service")

   ignore("koodisto-service should return rinnasteinen koodi") {
     val future: Future[Seq[SmallHakemus]] = client.find(HakijaQuery(None, None, None, Hakuehto.Kaikki, Tyyppi.Json, None, Some(User("testi", Seq(), None))))

     // TODO improve this to also test successful query
     val t = intercept[RuntimeException] {
       val list: Seq[SmallHakemus] = Await.result(future, Duration(length = 5, unit = TimeUnit.SECONDS))
     }
     t.getMessage should equal ("virhe kutsuttaessa hakupalvelua: Unauthorized")
   }

 }
