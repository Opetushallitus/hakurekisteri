package fi.vm.sade.hakurekisteri.rest

import org.json4s.{DefaultFormats, Formats}
import fi.vm.sade.hakurekisteri.domain.yksilollistaminen

trait HakurekisteriJsonSupport {

  protected implicit def jsonFormats: Formats = DefaultFormats + new org.json4s.ext.EnumNameSerializer(yksilollistaminen)

}
