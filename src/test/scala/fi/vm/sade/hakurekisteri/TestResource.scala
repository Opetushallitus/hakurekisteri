package fi.vm.sade.hakurekisteri

import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository._
import fi.vm.sade.hakurekisteri.rest.support.Resource

case class TestResource(name:String) extends fi.vm.sade.hakurekisteri.rest.support.Resource[UUID] {

  val source = "Test"
  override def identify(id: UUID): this.type with Identified[UUID] = TestResource.identify(this, id).asInstanceOf[this.type with Identified[UUID]]
}

object TestResource {

  def apply(id:UUID, name:String): TestResource with Identified[UUID] = TestResource(name).identify(id)


  def identify(o:TestResource): TestResource with Identified[UUID] = o match {
    case o: TestResource with Identified[UUID] => o
    case _ => o.identify(UUID.randomUUID)
  }

  def identify(t:TestResource, identity:UUID) = {


    new TestResource(t.name) with Identified[UUID] {
      val id: UUID = identity
    }
  }

}


case class TestJournal[T <: Resource[UUID]](state: Seq[T with Identified[UUID]] = Seq(), deleted:Seq[UUID] = Seq()) extends InMemJournal[T, UUID] {
  state foreach {(resource) => addModification(Updated(resource))}
  deleted foreach {(id) => addModification(Deleted(id, source = "Test"))}
}



case class TestRepo(journal: Journal[TestResource, UUID]) extends JournaledRepository[TestResource, UUID]{
  override def identify(o: TestResource): TestResource with Identified[UUID] = TestResource.identify(o)
}

