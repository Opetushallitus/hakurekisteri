package fi.vm.sade.hakurekisteri.suoritus

import fi.vm.sade.hakurekisteri.rest.support.{Kausi, Query}
import Kausi.Kausi



case class SuoritusQuery(henkilo: Option[String], kausi: Option[Kausi], vuosi: Option[String]) extends Query[Suoritus]

object SuoritusQuery{
  def apply(params: Map[String,String]): SuoritusQuery = {
    SuoritusQuery(params.get("henkilo"), params.get("kausi").map(Kausi.withName), params.get("vuosi"))
  }

}
