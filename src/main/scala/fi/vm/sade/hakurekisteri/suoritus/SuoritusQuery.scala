package fi.vm.sade.hakurekisteri.suoritus

import fi.vm.sade.hakurekisteri.rest.support.{Kausi, Query}
import Kausi.Kausi



case class SuoritusQuery(henkilo: Option[String] = None, kausi: Option[Kausi] = None, vuosi: Option[String] = None, myontaja: Option[String] = None) extends Query[Suoritus]

object SuoritusQuery{
  def apply(params: Map[String,String]): SuoritusQuery = {
    SuoritusQuery(params.get("henkilo"), params.get("kausi").map(Kausi.withName), params.get("vuosi"), params.get("myontaja"))
  }
}

case class SuoritysTyyppiQuery(henkilo: String, komo: String)  extends Query[Suoritus]
