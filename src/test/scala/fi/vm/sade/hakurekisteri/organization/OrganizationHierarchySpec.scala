package fi.vm.sade.hakurekisteri.organization

import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatra.test.scalatest.ScalatraFunSuite

class OrganizationHierarchySpec extends ScalatraFunSuite {
  implicit val formats = DefaultFormats

  val x = scala.io.Source.fromFile("src/test/resources/test-aktiiviset-organisaatiot.json").mkString
  val hakutulos: OrganisaatioHakutulos = parse(x).extract[OrganisaatioHakutulos]
  val hierarchy: Map[String, Set[String]] = FutureOrganizationHierarchy.parseOrganizationHierarchy(hakutulos).ancestors
  test("organization oid has only and all the parent oids as key value") {
    hierarchy("1.2.246.562.10.39644336305") should be(Set("1.2.246.562.10.39644336305", "1.2.246.562.10.80381044462", "1.2.246.562.10.00000000001"))
  }
}
