package fi.vm.sade.hakurekisteri.henkilo

import fi.vm.sade.hakurekisteri.storage.repository.{InMemJournal, Journal, JournaledRepository}
import fi.vm.sade.hakurekisteri.storage.{ResourceService, ResourceActor, Identified}
import fi.vm.sade.hakurekisteri.rest.support.Query
import java.util.UUID

trait HenkiloRepository extends JournaledRepository[Henkilo, UUID] {

  def identify(o: Henkilo): Henkilo with Identified[UUID] = {
    Henkilo.identify(o)
  }

}


class HenkiloActor(val journal:Journal[Henkilo, UUID] = new InMemJournal[Henkilo, UUID]) extends ResourceActor[Henkilo, UUID] with ResourceService[Henkilo, UUID] with JournaledRepository[Henkilo, UUID] with HenkiloRepository {

  override val matcher: PartialFunction[Query[Henkilo], (Henkilo with Identified[UUID]) => Boolean] = {
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
