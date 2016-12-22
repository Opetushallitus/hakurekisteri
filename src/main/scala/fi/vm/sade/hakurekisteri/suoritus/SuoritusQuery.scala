package fi.vm.sade.hakurekisteri.suoritus

import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.rest.support.Kausi.Kausi
import fi.vm.sade.hakurekisteri.rest.support.{Kausi, Query, QueryWithPersonAliasesResolver}
import org.joda.time.DateTime

import scala.concurrent.Future


case class SuoritusQuery(henkilo: Option[String] = None,
                         kausi: Option[Kausi] = None,
                         vuosi: Option[String] = None,
                         myontaja: Option[String] = None,
                         komo: Option[String] = None,
                         override val muokattuJalkeen: Option[DateTime] = None,
                         personOidAliasFetcher: (Set[String]) => Future[PersonOidsWithAliases] =
                           oids => Future.failed(new NotImplementedError("TODO: Add personOidAliasFetcher")))
  extends QueryWithPersonAliasesResolver[Suoritus] {
  override def fetchPersonAliases: Future[PersonOidsWithAliases] = personOidAliasFetcher(henkilo.toSet)

  override def createQueryWithAliases(personOidsWithAliases: PersonOidsWithAliases) = SuoritusQueryWithPersonAliases(this, personOidsWithAliases)
}

object SuoritusQuery {
  def apply(params: Map[String,String], personOidAliasFetcher: (Set[String]) => Future[PersonOidsWithAliases]): SuoritusQuery = {
    SuoritusQuery(params.get("henkilo"),
      params.get("kausi").map(Kausi.withName),
      params.get("vuosi"),
      params.get("myontaja"),
      params.get("komo"),
      params.get("muokattuJalkeen").map(DateTime.parse),
      personOidAliasFetcher)
  }
}

case class SuoritusQueryWithPersonAliases(wrappedQuery: SuoritusQuery, personOidsWithAliases: PersonOidsWithAliases) extends Query[Suoritus]
case class SuoritusHenkilotQuery(henkilot: PersonOidsWithAliases) extends Query[Suoritus]
case class SuoritysTyyppiQuery(henkilo: String, komo: String) extends Query[Suoritus]
case class AllForMatchinHenkiloSuoritusQuery(vuosi: Option[String] = None, myontaja: Option[String] = None) extends Query[Suoritus]
