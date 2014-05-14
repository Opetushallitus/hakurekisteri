package fi.vm.sade.hakurekisteri

import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository.{JournaledRepository, InMemRepository, Journal, InMemJournal}
import fi.vm.sade.hakurekisteri.rest.support.Resource

case class TestResource(name:String) extends fi.vm.sade.hakurekisteri.rest.support.Resource{
  override def identify[R <: TestResource](id: UUID): R with Identified = TestResource.identify(this, id).asInstanceOf[R with Identified]
}

object TestResource {

  def apply(id:UUID, name:String): TestResource with Identified = TestResource(name).identify(id)


  def identify(o:TestResource): TestResource with Identified = o match {
    case o: TestResource with Identified => o
    case _ => o.identify(UUID.randomUUID)
  }

  def identify(t:TestResource, identity:UUID) = {


    new TestResource(t.name) with Identified {
      val id: UUID = identity
    }
  }

}


case class TestJournal[T <: Resource](state: Seq[T with Identified] = Seq()) extends InMemJournal[T] {
  state foreach {(resource) => addModification(resource)}
}


case class TestRepo(journal: Journal[TestResource]) extends JournaledRepository[TestResource]{
  override def identify(o: TestResource): TestResource with Identified = TestResource.identify(o)
}

