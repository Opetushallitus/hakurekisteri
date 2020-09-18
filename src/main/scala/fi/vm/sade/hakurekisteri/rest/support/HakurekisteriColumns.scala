package fi.vm.sade.hakurekisteri.rest.support

import java.util.UUID

import fi.vm.sade.hakurekisteri.batchimport.BatchState._
import fi.vm.sade.hakurekisteri.batchimport.{BatchState, ImportStatus}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.Yksilollistetty
import fi.vm.sade.hakurekisteri.tools.SafeXML
import org.joda.time.{DateTime, LocalDate}
import org.json4s.Extraction._
import org.json4s.Formats
import org.json4s.JsonAST.JValue
import org.json4s.jackson.JsonMethods._

import scala.xml.Elem

trait HakurekisteriColumns {

  implicit val formats: Formats = HakurekisteriJsonSupport.format

  implicit def datetimeLong = MappedColumnType.base[DateTime, Long](_.getMillis, new DateTime(_))

  implicit def localDateString =
    MappedColumnType.base[LocalDate, String](_.toString, LocalDate.parse)

  implicit def yksilollistaminenString =
    MappedColumnType.base[Yksilollistetty, String](_.toString, yksilollistaminen.withName)

  implicit def jsonMap = MappedColumnType.base[Map[String, String], String](
    data => compact(decompose(data)),
    x => extract[Map[String, String]](parse(x))
  )

  implicit def batchStateColumnType =
    MappedColumnType.base[BatchState, String](_.toString, BatchState.withName)

  implicit def importstatusType = MappedColumnType.base[ImportStatus, String](
    data => compact(decompose(data)),
    (x: String) => extract[ImportStatus](parse(x))
  )

  implicit def uuidType = MappedColumnType.base[UUID, String](
    _.toString,
    x => {
      UUID.fromString(x)
    }
  )

  implicit def jvalueType =
    MappedColumnType.base[JValue, String](data => compact(render(data)), parse(_))

  implicit def elemType = MappedColumnType.base[Elem, String](_.toString, SafeXML.loadString)

}
