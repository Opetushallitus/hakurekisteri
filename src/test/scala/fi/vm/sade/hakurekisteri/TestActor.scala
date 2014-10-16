package fi.vm.sade.hakurekisteri

import akka.event.Logging
import fi.vm.sade.hakurekisteri.storage.{InMemQueryingResourceService, Identified, ResourceService, ResourceActor}
import fi.vm.sade.hakurekisteri.rest.support.Query
import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.repository._



class TestActor(val journal: Journal[TestResource, UUID]) extends ResourceActor[TestResource, UUID]  with JournaledRepository[TestResource, UUID] with InMemQueryingResourceService[TestResource ,UUID] {
  def this() = this(new InMemJournal[TestResource, UUID])

  override val logger = Logging(context.system, this)


  override val matcher: PartialFunction[Query[TestResource], (TestResource with Identified[UUID]) => Boolean] = {
    case _ => (_) => true
  }
}
