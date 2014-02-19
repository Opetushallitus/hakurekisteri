package fi.vm.sade.hakurekisteri.henkilo

import fi.vm.sade.hakurekisteri.storage.repository.{InMemJournal, Journal, JournaledRepository}
import fi.vm.sade.hakurekisteri.storage.{ResourceService, ResourceActor, Identified}
import fi.vm.sade.hakurekisteri.rest.support.Query

trait HenkiloRepository extends JournaledRepository[Henkilo] {

  def identify(o: Henkilo): Henkilo with Identified = {
    Henkilo.identify(o)
  }

}


class HenkiloActor(val journal:Journal[Henkilo] = new InMemJournal[Henkilo]) extends ResourceActor[Henkilo] with ResourceService[Henkilo] with JournaledRepository[Henkilo] with HenkiloRepository {

  override val matcher: PartialFunction[Query[Henkilo], (Henkilo with Identified) => Boolean] = {
    case HenkiloQuery(oid) => (henkilo) => oid match
    {
      case None => true
      case Some(henkiloOid) => henkilo.oidHenkilo.equals(henkiloOid)
    }

  }

}


case class HenkiloQuery(oid:Option[String]) extends Query[Henkilo]

object HenkiloQuery {
  def apply(params: Map[String,String]):HenkiloQuery = {
    HenkiloQuery(params.get("oid"))

  }
}
