package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.Date


case class YtlStudents(students: List[Either[Throwable, Student]])

case class Student(ssn: String, lastname: String, firstnames: String, graduationPeriod: Option[Kausi],
                   graduationDate: Option[Date],
                   graduationSchoolOphOid: Option[String],
                   graduationSchoolYtlNumber: Option[String],
                   language: Option[String],
                   exams: Seq[Exam])

case class Exam(examId: String,examRole: String, period: Kausi, grade: String, points: Int, sections: Seq[Section])

case class Section(sectionId: String, sectionPoints: Int)

trait Kausi
case class Kevat(vuosi:Int) extends Kausi
case class Syksy(vuosi:Int) extends Kausi

object Kausi {
  private val kausi = """(\d\d\d\d)(K|S)""".r

  def apply(kausiSpec: String): Kausi = kausiSpec match {
    case kausi(year, "K") => Kevat(Integer.parseInt(year))
    case kausi(year, "S") => Syksy(Integer.parseInt(year))
    case kausi(year, c) => throw new IllegalArgumentException(s"Illegal last character '$c'. Valid values are 'K' and 'S'")
    case s => throw new IllegalArgumentException(s"Illegal kausi specification '$s'. Expected format YYYY(K|S), e.g. 2015K")
  }
}