package fi.vm.sade.hakurekisteri.opiskelija

import fi.vm.sade.hakurekisteri.rest.support.{Kausi, Query}
import fi.vm.sade.hakurekisteri.rest.support.Kausi.Kausi
import fi.vm.sade.hakurekisteri.rest.support.Kausi.Kausi

case class OpiskelijaQuery(henkilo: Option[String], kausi: Option[Kausi], vuosi: Option[String]) extends Query[Opiskelija]

object OpiskelijaQuery{
  def apply(params: Map[String,String]): OpiskelijaQuery = {
    OpiskelijaQuery(params.get("henkilo"), params.get("kausi").map(Kausi.withName), params.get("vuosi"))
  }
}
