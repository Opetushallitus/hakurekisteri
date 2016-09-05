package fi.vm.sade.hakurekisteri.rest.support

import java.text.SimpleDateFormat
import java.util.Date

import fi.vm.sade.hakurekisteri.integration.ytl._
import org.json4s.JsonAST.{JField, JArray, JObject, JString}
import org.json4s.{CustomSerializer, DefaultFormats, MappingException}

import scala.util.{Failure, Success, Try}

class KausiDeserializer extends CustomSerializer[fi.vm.sade.hakurekisteri.integration.ytl.Kausi](format => ({
  case JString(arvosana) =>
    fi.vm.sade.hakurekisteri.integration.ytl.Kausi(arvosana)
},
  {
    case x: fi.vm.sade.hakurekisteri.integration.ytl.Kausi =>
      throw new UnsupportedOperationException("Serialization is unsupported")
  }
))

class StatusDeserializer extends CustomSerializer[Status](format => ({
  case JObject(fields) if(fields.exists {
    case ("finished",value: JString)=> true
    case _ => false
    }) => Finished()
  case JObject(fields) if(fields.exists {
    case ("failure",value: JString)=> true
    case _ => false
  }) => Failed()

  case JObject(e) => {
    println(e)
    println(e.getClass)
    InProgress()
  }
}, {
case x: Status => throw new UnsupportedOperationException("Serialization is unsupported")
}))

class StudentDeserializer extends CustomSerializer[YtlStudents](format => ({
  case JObject(List(("students", JArray(o)))) => {
    implicit val formats = new DefaultFormats {
      override def dateFormatter = new SimpleDateFormat("yyyy-MM-dd")
    } + new KausiDeserializer

    val students: List[Either[Throwable, Student]] = o.map(s => Try(s.extract[Student]) match {
      case Success(student : Student) => Right(student)
      case Failure(e : MappingException) => Left(e.cause)
      case Failure(e) => Left(e)
    })
    YtlStudents(students)
  }
},
  {
    case x: Student =>
      throw new UnsupportedOperationException("Serialization is unsupported")
  }
  ))
