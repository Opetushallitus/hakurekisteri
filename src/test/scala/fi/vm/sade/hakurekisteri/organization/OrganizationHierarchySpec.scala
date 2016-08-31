package fi.vm.sade.hakurekisteri.organization

import java.util.UUID

import akka.actor.ActorSystem
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.{Config, MockDevConfig}
import fi.vm.sade.hakurekisteri.arvosana.Arvosana
import fi.vm.sade.hakurekisteri.integration.{ServiceConfig, VirkailijaRestClient}
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.concurrent.duration._
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class OrganizationHierarchySpec extends ScalatraFunSuite {
  implicit val system = ActorSystem("organization-hierarchy-test-system")

  implicit val formats = DefaultFormats
  implicit val timeout: Timeout = 15.seconds

  val x = scala.io.Source.fromFile("src/test/resources/test-aktiiviset-organisaatiot.json").mkString
  val json: JValue = parse(x)
  val hakutulos: OrganisaatioHakutulos = json.extract[OrganisaatioHakutulos]
  val resolve = (arvosana: Arvosana) => Future((Set[String](), Some("")))
  val organisaatioClient: VirkailijaRestClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = ""), None)
  val authorizer = new OrganizationHierarchyAuthorization[Arvosana, UUID](resolve, ServiceConfig(serviceUrl = ""), organisaatioClient)
  val hierarchy: Map[String, Set[String]] = Await.result(authorizer.parseOrganizationHierarchy(Future(hakutulos)), 2.seconds)
  test("organization oid has only and all the parent oids as key value") {
    hierarchy.get("1.2.246.562.10.39644336305").get should be(Set("1.2.246.562.10.39644336305", "1.2.246.562.10.80381044462", "1.2.246.562.10.00000000001"))
  }
}
