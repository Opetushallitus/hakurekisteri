package fi.vm.sade.hakurekisteri

import fi.vm.sade.hakurekisteri.storage.{Identified, ResourceService, ResourceActor}
import fi.vm.sade.hakurekisteri.rest.support.Query
import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.repository._
import scala.compat.Platform



class TestActor(val journal: Journal[TestResource, UUID]) extends ResourceActor[TestResource, UUID]  with JournaledRepository[TestResource, UUID] with ResourceService[TestResource ,UUID] {
  def this() = this(new InMemJournal[TestResource, UUID])

  override def identify(o: TestResource): TestResource with Identified[UUID] = new TestResource(o.name) with Identified[UUID] {
    val id = UUID.randomUUID()
  }

  override val matcher: PartialFunction[Query[TestResource], (TestResource with Identified[UUID]) => Boolean] = {
    case _ => (_) => true
  }
}
