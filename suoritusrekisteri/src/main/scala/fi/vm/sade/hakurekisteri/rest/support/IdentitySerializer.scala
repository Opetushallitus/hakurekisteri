package fi.vm.sade.hakurekisteri.rest.support

import org.json4s.CustomSerializer
import org.json4s.JsonAST.{JString, JValue}
import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.Identified

class IdentitySerializer
    extends CustomSerializer[Identified[UUID]](format =>
      (
        IdentitySerializer.deserialize,
        Map.empty
      )
    ) {}

object IdentitySerializer {
  val deserialize: PartialFunction[JValue, Identified[UUID]] = (json: JValue) =>
    json \ "id" match {
      case JString(identifier) =>
        new Identified[UUID] {
          val id: UUID = UUID.fromString(identifier)
        }
    }
}
