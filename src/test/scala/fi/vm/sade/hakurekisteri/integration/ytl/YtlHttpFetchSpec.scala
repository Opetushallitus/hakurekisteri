package fi.vm.sade.hakurekisteri.integration.ytl

import fi.vm.sade.scalaproperties.OphProperties
import org.scalatra.test.scalatest.ScalatraFunSuite


class YtlHttpFetchSpec extends ScalatraFunSuite with YtlMockFixture {
  val config = new OphProperties()
    .addDefault("ytl.http.host.bulk", bulkUrl)
    .addDefault("ytl.http.host.download", downloadUrl)
    .addDefault("ytl.http.host.fetchone", fetchOneUrl)
    .addDefault("ytl.http.host.status", statusUrl)
    .addDefault("ytl.http.username", username)
    .addDefault("ytl.http.password", password)
  val fileSystem = new YtlFileSystem(config)
  val ytlHttpFetch = new YtlHttpFetch(config,fileSystem)

  test("zip to students") {

    ytlHttpFetch.zipToStudents(getClass.getResource("/student-results.zip").openStream())
    "ok" should equal ("ok")
  }

  test("Fetch one with basic auth") {

    val student = ytlHttpFetch.fetchOne("050996-9574")
    student.lastname should equal ("Vasala")
    student.firstnames should equal ("Sampsa")
  }

  test("Fetch many as zip") {
    val students = ytlHttpFetch.fetch(List("050996-9574"))

    students.right.get.head.size should equal (7)
  }

}
