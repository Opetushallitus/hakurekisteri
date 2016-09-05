package fi.vm.sade.hakurekisteri.integration.ytl

import java.text.SimpleDateFormat

import fi.vm.sade.hakurekisteri.rest.support.{KausiDeserializer, StudentDeserializer}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import org.scalatra.test.scalatest.ScalatraFunSuite


class YtlJsonSpec extends ScalatraFunSuite {
  def dateFormat = new SimpleDateFormat("yyyy-MM-dd")
  implicit val formats = Serialization.formats(NoTypeHints) + new StudentDeserializer

  test("Parse YTL json") {
    val json = scala.io.Source.fromFile(getClass.getResource("/ytl-students.json").getFile).getLines.mkString
    val ytlstudents = parse(json).extract[YtlStudents]
    ytlstudents.students.size should equal (3)
    val validStudents = ytlstudents.students.filter(_.isRight).map(s => s.right.get)

    validStudents.size should equal (1)
    validStudents.head.graduationDate should equal(dateFormat.parse("2016-06-04"))
    validStudents.head.graduationPeriod should equal (Kevat(2015))
    validStudents.head.exams.size should equal (1)
    validStudents.head.exams.head.sections.isEmpty should equal (true)
  }

}
