package fi.vm.sade.hakurekisteri

import fi.vm.sade.hakurekisteri.storage.{Identified, ResourceService, ResourceActor}
import fi.vm.sade.hakurekisteri.rest.support.Query
import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.repository._
import scala.compat.Platform



class TestActor(val journal: Journal[TestResource]) extends ResourceActor[TestResource]  with JournaledRepository[TestResource] with ResourceService[TestResource] {
  def this() = this(new InMemJournal[TestResource])

  override def identify(o: TestResource): TestResource with Identified = new TestResource(o.name) with Identified {
    val id = UUID.randomUUID()
  }

  override val matcher: PartialFunction[Query[TestResource], (TestResource with Identified) => Boolean] = {
    case _ => (_) => true
  }
}
