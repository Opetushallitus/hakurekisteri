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
    val student = parse(ylioppilaatJson).extract[Student]

    val kokelasFromJson: Kokelas = StudentToKokelas.convert(oid, student)

    kokelasFromJson.yoTodistus should not be empty
    kokelasFromJson.osakokeet should not be empty
  }

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
      |          "examRoleShort": "mother-tongue",
      |          "examRoleLegacy": "11",
      |          "period": "2015K",
      |          "grade": "M",
      |          "points": 80,
      |          "sections": [{"sectionId":"T002","sectionPoints":10},{"sectionId":"T001","sectionPoints":35}]
      |        }
      |      ]
      |    }
    """.stripMargin
}
