package fi.vm.sade.hakurekisteri.rest.support

import org.json4s.{FieldSerializer, DefaultFormats, Formats}
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen
import fi.vm.sade.hakurekisteri.storage.Identified
import org.json4s.ext.DateTimeSerializer
import java.util.UUID

trait HakurekisteriJsonSupport {

  protected implicit def jsonFormats: Formats = DefaultFormats.lossless.withBigDecimal + new org.json4s.ext.EnumNameSerializer(yksilollistaminen) +
    FieldSerializer[Identified[UUID]]() + new UUIDSerializer + new IdentitySerializer + DateTimeSerializer + new LocalDateSerializer() + new ArvioSerializer + new AjanjaksoSerializer

}
