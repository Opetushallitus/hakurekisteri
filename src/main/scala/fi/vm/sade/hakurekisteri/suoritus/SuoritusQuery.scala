package fi.vm.sade.hakurekisteri.suoritus

import fi.vm.sade.hakurekisteri.rest.support.{Kausi, Query}
import Kausi.Kausi
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import org.joda.time.DateTime


case class SuoritusQuery(henkilo: Option[String] = None,
                         kausi: Option[Kausi] = None,
                         vuosi: Option[String] = None,
                         myontaja: Option[String] = None,
                         komo: Option[String] = None,
                         override val muokattuJalkeen: Option[DateTime] = None) extends Query[Suoritus]

object SuoritusQuery{
  def apply(params: Map[String,String]): SuoritusQuery = {
    SuoritusQuery(params.get("henkilo"),
      params.get("kausi").map(Kausi.withName),
      params.get("vuosi"),
      params.get("myontaja"),
      params.get("komo"),
      params.get("muokattuJalkeen").map(DateTime.parse))
  }
}

case class SuoritusHenkilotQuery(henkilot: PersonOidsWithAliases) extends Query[Suoritus]
case class SuoritysTyyppiQuery(henkilo: String, komo: String) extends Query[Suoritus]
case class AllForMatchinHenkiloSuoritusQuery(vuosi: Option[String] = None, myontaja: Option[String] = None) extends Query[Suoritus]
