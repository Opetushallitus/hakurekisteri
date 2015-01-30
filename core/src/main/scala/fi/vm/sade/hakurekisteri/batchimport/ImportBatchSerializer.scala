package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.storage.Identified
import org.json4s.{Extraction, CustomSerializer}
import org.json4s.JsonAST.{JValue, JString, JObject}
import org.json4s.JsonDSL._

import org.json4s.Xml.{toJson, toXml}

import scala.xml.Elem


class ImportBatchSerializer extends CustomSerializer[ImportBatch] (format => (
  {
    case json: JObject =>
      val id = json.findField(jf => jf._1 == "id").map(_._2).collect { case JString(i) => UUID.fromString(i)}
      val rawData = json \ "data"
      val external = json.findField(jf => jf._1 == "externalId").map(_._2).collect { case JString(eid) => eid}
      val JString(batchType) = json \ "batchType"
      val JString(source) = json \ "source"
      val JString(state) = json \ "state"
      val status: JValue = json \ "status"

      implicit val formats = HakurekisteriJsonSupport.format
      val importstatus = Extraction.extract[ImportStatus](status)

      val batch = ImportBatch(toXml(rawData).collectFirst{case e:Elem => e}.get, external, batchType, source, BatchState.withName(state), importstatus)

      id.map(i => batch.identify(i)).getOrElse(batch)
  },
  {
    case ib: ImportBatch with Identified[UUID] =>
      implicit val formats = HakurekisteriJsonSupport.format
      val s: JObject = Extraction.decompose(ib.status) match {
        case o: JObject => o
        case _ => JObject(List())
      }

      val result =  ("id" -> ib.id.toString) ~
                    ("data" -> toJson(ib.data)) ~
                    ("batchType" -> ib.batchType) ~
                    ("source" -> ib.source) ~
                    ("state" -> ib.state.toString) ~
                    ("status" -> s)

      ib.externalId.map(id => result ~ ("externalId" -> id)).getOrElse(result)
  }
  )
)

import BatchState.BatchState
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.simple._

object ImportBatchImplicits {
  implicit val batchStateColumnType = MappedColumnType.base[BatchState, String]({ c => c.toString }, { s => BatchState.withName(s)})
}