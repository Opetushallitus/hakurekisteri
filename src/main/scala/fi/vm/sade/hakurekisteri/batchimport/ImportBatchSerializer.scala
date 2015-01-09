package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.DateTime
import org.json4s.CustomSerializer
import org.json4s.JsonAST.{JField, JValue, JString, JObject}
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.read

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
      val importstatus = read[ImportStatus](compact(render(status)))

      val batch = ImportBatch(toXml(rawData).collectFirst{case e:Elem => e}.get, external, batchType, source, BatchState.withName(state), importstatus)

      id.map(i => batch.identify(i)).getOrElse(batch)
  },
  {
    case ib: ImportBatch with Identified[UUID] =>
      val status: JObject =
        ("sentTime" -> ib.status.sentTime.toString) ~
        ("messages" -> ib.status.messages)
      val statusWithProcessedTime: JObject = ib.status.processedTime.map((t: DateTime) => status ~ ("processedTime" -> t.toString)).getOrElse(status)
      val statusWithSuccessRows: JObject = ib.status.successRows.map((r: Int) => statusWithProcessedTime ~ ("successRows" -> r)).getOrElse(statusWithProcessedTime)
      val statusWithFailureRows: JObject = ib.status.failureRows.map((r: Int) => statusWithSuccessRows ~ ("failureRows" -> r)).getOrElse(statusWithSuccessRows)
      val statusWithTotalRows: JObject = ib.status.totalRows.map((r: Int) => statusWithFailureRows ~ ("totalRows" -> r)).getOrElse(statusWithFailureRows)
      val statusWithSavedReferences: JObject = ib.status.savedReferences.map((refs) => {
        val references = for (
          henkilo <- refs.toList
        ) yield {
          val saved = for (
            ref <- henkilo._2.toList
          ) yield (ref._1, JString(ref._2))
          JField(henkilo._1, JObject(saved))
        }
        statusWithTotalRows ~ ("savedReferences" -> JObject(references))
      }).getOrElse(statusWithTotalRows)

      val result =  ("id" -> ib.id.toString) ~
                    ("data" -> toJson(ib.data)) ~
                    ("batchType" -> ib.batchType) ~
                    ("source" -> ib.source) ~
                    ("state" -> ib.state.toString) ~
                    ("status" -> statusWithSavedReferences)

      ib.externalId.map(id => result ~ ("externalId" -> id)).getOrElse(result)
  }
  )
)

import BatchState.BatchState
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.simple._

object ImportBatchImplicits {
  implicit val batchStateColumnType = MappedColumnType.base[BatchState, String]({ c => c.toString }, { s => BatchState.withName(s)})
}