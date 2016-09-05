package fi.vm.sade.hakurekisteri.integration.ytl

import fi.vm.sade.scalaproperties.OphProperties
import org.scalatra.test.scalatest.ScalatraFunSuite


class YtlHttpFetchSpec extends ScalatraFunSuite with YtlMockFixture {



  test("Fetch with basic auth") {
    val v = new OphProperties()
    v.addDefault("ytl.http.host", url)
    val ytlHttpFetch = new YtlHttpFetch(v)
    val student = ytlHttpFetch.fetchOne("050996-9574")
    student.lastname should equal ("Vasala")
    student.firstnames should equal ("Sampsa")
  }

}
