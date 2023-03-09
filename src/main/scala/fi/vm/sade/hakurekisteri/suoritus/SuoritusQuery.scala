package fi.vm.sade.hakurekisteri.suoritus

import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.rest.support.Kausi.Kausi
import fi.vm.sade.hakurekisteri.rest.support.{Kausi, Query, QueryWithPersonOid}
import fi.vm.sade.utils.slf4j.Logging
import org.joda.time.DateTime

case class SuoritusQuery(
  henkilo: Option[String] = None,
  kausi: Option[Kausi] = None,
  vuosi: Option[String] = None,
  myontaja: Option[String] = None,
  komo: Option[String] = None,
  override val muokattuJalkeen: Option[DateTime] = None,
  muokattuEnnen: Option[DateTime] = None
) extends QueryWithPersonOid[Suoritus] {
  override def createQueryWithAliases(personOidsWithAliases: PersonOidsWithAliases) =
    SuoritusQueryWithPersonAliases(this, personOidsWithAliases)
}

object SuoritusQuery extends Logging {
  def apply(params: Map[String, String]): SuoritusQuery = {
    if (params.isEmpty) {
      logger.error(
        s"All parameters were empty, throwing an exception to avoid a too large query."
      )
      throw new IllegalArgumentException("Vähintään yksi hakuehto on pakollinen")
    }
    SuoritusQuery(
      params.get("henkilo"),
      params.get("kausi").map(Kausi.withName),
      params.get("vuosi"),
      params.get("myontaja"),
      params.get("komo"),
      params.get("muokattuJalkeen").map(DateTime.parse),
      params.get("muokattuEnnen").map(DateTime.parse)
    )
  }
}

case class SuoritusQueryWithPersonAliases(
  wrappedQuery: SuoritusQuery,
  fullPersonOidsWithAliases: PersonOidsWithAliases
) extends Query[Suoritus] {
  val personOidsWithAliases: PersonOidsWithAliases =
    fullPersonOidsWithAliases.intersect(wrappedQuery.henkilo.toSet)
}
case class SuoritusHenkilotQuery(henkilot: PersonOidsWithAliases) extends Query[Suoritus]
case class SuoritysTyyppiQuery(henkilo: String, komo: String) extends Query[Suoritus]
case class AllForMatchinHenkiloSuoritusQuery(
  vuosi: Option[String] = None,
  myontaja: Option[String] = None
) extends Query[Suoritus]
