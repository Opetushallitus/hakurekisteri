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

  override val matcher: PartialFunction[Query[Henkilo], (Henkilo with Identified) => Boolean] = Map()

}