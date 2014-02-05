package fi.vm.sade.hakurekisteri.rest.support

import org.json4s.CustomSerializer
import fi.vm.sade.hakurekisteri.opiskelija.Identified
import org.json4s.JsonAST.{JString, JValue}
import java.util.UUID


class IdentitySerializer extends CustomSerializer[Identified](format => (
  IdentitySerializer.deserialize,
  Map.empty
  )) {

}

object IdentitySerializer {
  val deserialize: PartialFunction[JValue, Identified] = (json:JValue) => json \ "id" match {
    case JString(identifier)  => new Identified {
      val id: UUID = UUID.fromString(identifier)
    }
  }
}