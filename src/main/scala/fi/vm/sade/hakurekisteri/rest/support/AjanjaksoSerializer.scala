package fi.vm.sade.hakurekisteri.rest.support

import org.json4s.CustomSerializer
import fi.vm.sade.hakurekisteri.arvosana.{ArvioYo, Arvio410, Arvio}
import org.json4s.JsonAST.{JField, JInt, JString, JObject}
import org.joda.time.DateTime
import fi.vm.sade.hakurekisteri.dates.{InFuture, Ajanjakso}


class AjanjaksoSerializer extends CustomSerializer[Ajanjakso](format => (
  {
    case ajanjakso:JObject  =>
      val JString(serializedAlku) = ajanjakso \ "alku"
      val alku = DateTime.parse(serializedAlku)
      val loppu = (ajanjakso \ "loppu").toOption.collect{case JString(loppu) => DateTime.parse(loppu)}
      Ajanjakso(alku, loppu)
  },
  {
    case Ajanjakso(alku, InFuture) =>
      JObject(JField("alku", JString(alku.toString)) :: Nil)
    case Ajanjakso(alku, loppu) =>
      JObject(JField("alku", JString(alku.toString)) :: JField("loppu", JString(loppu.toString)) :: Nil)


  }
  )

)