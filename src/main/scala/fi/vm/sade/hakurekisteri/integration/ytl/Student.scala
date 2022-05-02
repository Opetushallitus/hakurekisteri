package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.Date

import fi.vm.sade.hakurekisteri.arvosana.{ArvioOsakoe, ArvioYo}
import fi.vm.sade.hakurekisteri.suoritus.{VirallinenSuoritus}
import jawn._
import jawn.ast.{JValue, JParser}
import org.joda.time.{MonthDay, LocalDate}
import org.joda.time.format.DateTimeFormat
import org.json4s.jackson.JsonMethods._
import org.json4s.{CustomSerializer}
import org.json4s.JsonAST.{JObject, JString}
import org.json4s.jackson.Serialization

import scala.util.{Try}
case class Operation(operationUuid: String)

trait Status {}

case class InProgress() extends Status
case class Finished() extends Status
case class Failed() extends Status

object Student {
  val kevat = "(\\d{4})K".r
  val syksy = "(\\d{4})S".r
  val suoritettu = "suor".r
  val kevaanAlku = new MonthDay(6, 1)
  val syys = new MonthDay(12, 21)
  def parseKausi(kausi: String) = kausi match {
    case kevat(vuosi) => Some(kevaanAlku.toLocalDate(vuosi.toInt))
    case syksy(vuosi) => Some(syys.toLocalDate(vuosi.toInt))
    case _            => None
  }
  def nextKausi: String = MonthDay.now() match {
    case d if d.isBefore(kevaanAlku) => s"${LocalDate.now.getYear}K"
    case _                           => s"${LocalDate.now.getYear}S"
  }
  private val dtf = DateTimeFormat.forPattern("yyyy-MM-dd")

  case object DateSerializer
      extends CustomSerializer[LocalDate](format =>
        (
          { case JString(s) =>
            dtf.parseLocalDate(s)
          },
          { case d: Date =>
            throw new UnsupportedOperationException("Serialization is unsupported")
          }
        )
      )

  val formatsStudent =
    Serialization.formats(org.json4s.NoTypeHints) + KausiDeserializer + DateSerializer

  private def jvalueToStudent(jvalue: String): Student = {
    implicit val formats = formatsStudent
    // this is slow
    parse(jvalue).extract[Student]
  }

  case class StudentAsyncParser() {
    val p: AsyncParser[JValue] = JParser.async(mode = AsyncParser.UnwrapArray)

    def feedChunk(c: Array[Byte]): Seq[(String, Try[Student])] = {
      p.absorb(c) match {
        case Right(js: Seq[JValue]) => js.map(_.render()).map(v => (v, Try(jvalueToStudent(v))))
        case Left(e)                => throw e
      }
    }

  }
}

case object KausiDeserializer
    extends CustomSerializer[fi.vm.sade.hakurekisteri.integration.ytl.Kausi](format =>
      (
        { case JString(arvosana) =>
          fi.vm.sade.hakurekisteri.integration.ytl.Kausi(arvosana)
        },
        { case x: fi.vm.sade.hakurekisteri.integration.ytl.Kausi =>
          throw new UnsupportedOperationException("Serialization is unsupported")
        }
      )
    )

case object StatusDeserializer
    extends CustomSerializer[Status](format =>
      (
        {
          case JObject(fields) if fields.exists {
                case ("finished", value: JString) => true
                case _                            => false
              } =>
            Finished()
          case JObject(fields) if fields.exists {
                case ("failure", value: JString) => true
                case _                           => false
              } =>
            Failed()

          case JObject(e) => InProgress()
        },
        { case x: Status =>
          throw new UnsupportedOperationException("Serialization is unsupported")
        }
      )
    )

case class Student(
  ssn: String,
  lastname: String,
  firstnames: String,
  graduationPeriod: Option[Kausi] = None,
  graduationDate: Option[LocalDate] = None,
  language: String,
  exams: Seq[Exam]
)

case class Exam(
  examId: String,
  period: Kausi,
  grade: String,
  points: Option[Int]
)

case class Section(sectionId: String, sectionPoints: Option[String])

trait Kausi {
  def toLocalDate: LocalDate
}
case class Kevat(vuosi: Int) extends Kausi {
  override def toLocalDate = Student.kevaanAlku.toLocalDate(vuosi.toInt)
}
case class Syksy(vuosi: Int) extends Kausi {
  override def toLocalDate = Student.syys.toLocalDate(vuosi.toInt)
}

object Kausi {
  private val kausi = """(\d\d\d\d)(K|S)""".r

  def apply(kausiSpec: String): Kausi = kausiSpec match {
    case kausi(year, "K") => Kevat(Integer.parseInt(year))
    case kausi(year, "S") => Syksy(Integer.parseInt(year))
    case kausi(year, c) =>
      throw new IllegalArgumentException(
        s"Illegal last character '$c'. Valid values are 'K' and 'S'"
      )
    case s =>
      throw new IllegalArgumentException(
        s"Illegal kausi specification '$s'. Expected format YYYY(K|S), e.g. 2015K"
      )
  }
}

object StudentToKokelas {

  def convert(oid: String, s: Student): Kokelas = {
    val suoritus: VirallinenSuoritus = toYoTutkinto(oid, s)
    val yoTodistus = s.exams.map(exam =>
      YoKoe(
        ArvioYo(exam.grade, exam.points),
        exam.examId,
        exam.period.toLocalDate,
        oid
      )
    )
    Kokelas(oid, suoritus, yoTodistus)
  }

  def toYoTutkinto(oid: String, s: Student): VirallinenSuoritus = {
    val valmistuminen: LocalDate =
      s.graduationPeriod.map(_.toLocalDate).getOrElse(Student.parseKausi(Student.nextKausi).get)
    val päättötodistus = s.graduationPeriod.isDefined
    val suoritus = YoTutkinto(
      suorittaja = oid,
      valmistuminen = valmistuminen,
      kieli = s.language.toUpperCase,
      valmis = päättötodistus,
      Map.empty
    )
    suoritus
  }
}
