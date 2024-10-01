package fi.vm.sade.hakurekisteri.rest.support

import org.json4s.CustomSerializer
import java.util.UUID
import org.json4s.JsonAST.JString

class UUIDSerializer
    extends CustomSerializer[UUID](format =>
      (
        { case JString(identifier) =>
          UUID.fromString(identifier)

        },
        { case x: UUID =>
          JString(x.toString)
        }
      )
    )
