package fi.vm.sade.hakurekisteri.integration.ytl

import java.text.SimpleDateFormat

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.integration.ytl.Student.StudentAsyncParser
import org.joda.time.format.DateTimeFormat
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import org.scalatra.test.scalatest.ScalatraFunSuite
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.Elem


class YtlJsonSpec extends ScalatraFunSuite {
  implicit val formats = Student.formatsStudent


  test("Parse async YTL json") {
    val (a,b) = ylioppilaatJson.splitAt(ylioppilaatJson.length/2)
    val async = StudentAsyncParser()
    async.feedChunk(a).size should equal(0)
    async.feedChunk(b).size should equal(1)
  }

  test("Parse YTL json") {
    val json = scala.io.Source.fromFile(getClass.getResource("/ytl-students.json").getFile).getLines.mkString
    val ytlstudents = parse(json).extract[List[Student]]
    ytlstudents.size should equal (1)

    ytlstudents.head.graduationDate should equal(Some(DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate("2016-06-04")))
    ytlstudents.head.graduationPeriod should equal (Some(Kevat(2015)))
    ytlstudents.head.exams.size should equal (1)
    ytlstudents.head.exams.head.sections.isEmpty should equal (true)
  }

  test("Kokelas from json should equal kokelas from XML") {
    val oid = "1.2.246.562.24.71944845619"
    val oidFinder: String => Future[String] = hetu => Future.successful(oid)
    val kokelas = fetchKokelasFromXml(ylioppilaatXml, oidFinder)
    val student = parse(ylioppilaatJson).extract[Student]

    val kokelasFromJson: Kokelas = StudentToKokelas.convert(oid, student)

    kokelasFromJson.yo should equal (kokelas.yo)
    kokelasFromJson.lukio should equal (kokelas.lukio)
    kokelasFromJson.yoTodistus should equal (kokelas.yoTodistus)
    kokelasFromJson.osakokeet should equal (kokelas.osakokeet)
    kokelasFromJson should equal (kokelas)
  }

  private def fetchKokelasFromXml(xml: Elem, oidFinder: String => Future[String]): Kokelas = {
    implicit val system = ActorSystem()
    implicit val clientEc = ExecutorUtil.createExecutor(1, "ytl-test-pool")
    val oidFinder: String => Future[String] = hetu => Future.successful("1.2.246.562.24.71944845619")
    val future: Future[Option[Kokelas]] = YTLXml.findKokelaat(scala.io.Source.fromString(ylioppilaatXml.toString), oidFinder).head
    Await.result(future, 5000.milliseconds);
    future.value.get.get.get
  }

val ylioppilaatXml = <YLIOPPILAAT><YLIOPPILAS>
  <HENKILOTUNNUS>200495-9177</HENKILOTUNNUS>
  <SUKUNIMI>Testinen</SUKUNIMI>
  <ETUNIMET>Jussi Johannes</ETUNIMET>
  <TUTKINTOAIKA>2015K</TUTKINTOAIKA>
  <YLIOPPILAAKSITULOAIKA>suor</YLIOPPILAAKSITULOAIKA>
  <LUKIONUMERO>1234</LUKIONUMERO>
  <TUTKINTOKIELI>FI</TUTKINTOKIELI>
  <KOKEET>
    <KOE>
      <KOETUNNUS>A</KOETUNNUS>
      <AINEYHDISTELMAROOLI>11</AINEYHDISTELMAROOLI>
      <KOERYHMA>91</KOERYHMA>
      <PAKOLLISUUS>P</PAKOLLISUUS>
      <TUTKINTOKERTA>2015K</TUTKINTOKERTA>
      <ARVOSANA>M</ARVOSANA>
      <ARVOSANAN_PISTEET>5</ARVOSANAN_PISTEET>
      <YHTEISPISTEMAARA>80</YHTEISPISTEMAARA>
      <ARVOSANAN_ALARAJA>0</ARVOSANAN_ALARAJA>
      <ARVOSANAN_YLARAJA>0</ARVOSANAN_YLARAJA>
      <KOKEEN_MAKSIMIPISTEET>65</KOKEEN_MAKSIMIPISTEET>
      <OSAKOKEET>
        <OSAKOE>
          <OSAKOETUNNUS>T002</OSAKOETUNNUS>
          <OSAKOEPISTEET>10</OSAKOEPISTEET>
        </OSAKOE>
        <OSAKOE>
          <OSAKOETUNNUS>T001</OSAKOETUNNUS>
          <OSAKOEPISTEET>35</OSAKOEPISTEET>
        </OSAKOE>
      </OSAKOKEET>
    </KOE>
  </KOKEET>
</YLIOPPILAS>
</YLIOPPILAAT>
  val ylioppilaatJson =
    """
      |{
      |      "ssn": "200495-9177",
      |      "unexpectedfield": 1234,
      |      "lastname": "Testinen",
      |      "firstnames": "Jussi Johannes",
      |      "graduationPeriod": "2015K",
      |      "graduationDate": "2016-06-04",
      |      "graduationSchoolOphOid": "1.2.33.44444",
      |      "graduationSchoolYtlNumber": "1234",
      |      "language": "fi",
      |      "exams": [
      |        {
      |          "examId": "A",
      |          "examRole": 11,
      |          "period": "2015K",
      |          "grade": "M",
      |          "points": 80,
      |          "sections": [{"sectionId":"T002","sectionPoints":10},{"sectionId":"T001","sectionPoints":35}]
      |        }
      |      ]
      |    }
    """.stripMargin
}
