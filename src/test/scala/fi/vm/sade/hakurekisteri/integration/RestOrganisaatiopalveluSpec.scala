package fi.vm.sade.hakurekisteri.integration

import fi.vm.sade.hakurekisteri.integration.organisaatio.{RestOrganisaatiopalvelu, Organisaatio}
import org.scalatra.test.scalatest.ScalatraFunSuite
import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import akka.actor.ActorSystem

class RestOrganisaatiopalveluSpec extends ScalatraFunSuite {

   implicit val system = ActorSystem()
   implicit def executor: ExecutionContext = system.dispatcher

   val client = new RestOrganisaatiopalvelu("https://itest-virkailija.oph.ware.fi/organisaatio-service")

   ignore("organisaatio-service should return OPH") {
     val future: Future[Option[Organisaatio]] = client.get("1.2.246.562.10.00000000001")
     val organisaatioOption: Option[Organisaatio] = Await.result(future, Duration(length = 5, unit = TimeUnit.SECONDS))
     organisaatioOption should not equal (None)
   }

 }
