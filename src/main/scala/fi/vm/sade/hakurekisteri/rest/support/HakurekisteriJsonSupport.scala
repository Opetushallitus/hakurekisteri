package fi.vm.sade.hakurekisteri.rest.support

import org.json4s.{FieldSerializer, DefaultFormats, Formats}
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen
import fi.vm.sade.hakurekisteri.storage.Identified

trait HakurekisteriJsonSupport {

  protected implicit def jsonFormats: Formats = DefaultFormats.lossless +
    new org.json4s.ext.EnumNameSerializer(yksilollistaminen) +
    FieldSerializer[Identified]()  + new UUIDSerializer  + new IdentitySerializer + org.json4s.ext.DateTimeSerializer
    }
