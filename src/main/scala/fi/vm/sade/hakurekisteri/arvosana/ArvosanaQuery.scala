package fi.vm.sade.hakurekisteri.arvosana

import fi.vm.sade.hakurekisteri.rest.support.{Kausi, Query}
import Kausi.Kausi
import java.util.UUID


case class ArvosanaQuery(suoritus: Option[UUID]) extends Query[Arvosana]

object ArvosanaQuery{
  def apply(params: Map[String,String]): ArvosanaQuery = {
    ArvosanaQuery(params.get("suoritus").map(UUID.fromString(_)))
  }
}
