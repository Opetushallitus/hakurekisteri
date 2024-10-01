package fi.vm.sade.hakurekisteri.arvosana

import java.util.UUID

import fi.vm.sade.hakurekisteri.rest.support.Query

case class ArvosanaQuery(suoritus: UUID) extends Query[Arvosana]
case class ArvosanatQuery(suoritukset: Set[UUID]) extends Query[Arvosana]

object ArvosanaQuery {
  def apply(params: Map[String, String]): ArvosanaQuery = {
    params
      .get("suoritus")
      .map(UUID.fromString)
      .fold(throw new IllegalArgumentException("Arvosana query without suoritus is not supported"))(
        ArvosanaQuery(_)
      )
  }
}
