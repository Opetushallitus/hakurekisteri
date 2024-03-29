package fi.vm.sade.hakurekisteri

import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository._
import fi.vm.sade.hakurekisteri.rest.support.Resource

case class TestResource(name: String, value: Option[String] = None)
    extends fi.vm.sade.hakurekisteri.rest.support.UUIDResource[TestResource] {

  val source = "Test"
  override def identify(id: UUID): TestResource with Identified[UUID] =
    TestResource.identify(this, id)

  override val core: AnyRef = TestCore(name)
}

object TestResource {

  def apply(id: UUID, name: String, value: Option[String]): TestResource with Identified[UUID] =
    TestResource(name, value).identify(id)

  def identify(o: TestResource): TestResource with Identified[UUID] = o match {
    case o: TestResource with Identified[_] if o.id.isInstanceOf[UUID] =>
      o.asInstanceOf[TestResource with Identified[UUID]]
    case _ => o.identify(UUID.randomUUID)
  }

  def identify(t: TestResource, identity: UUID) = {

    new TestResource(t.name, t.value) with Identified[UUID] {
      val id: UUID = identity
    }
  }

}

case class TestJournal[T <: Resource[UUID, T]](
  state: Seq[T with Identified[UUID]] = Seq(),
  deleted: Seq[UUID] = Seq()
) extends InMemJournal[T, UUID] {
  state foreach { (resource) => addModification(Updated(resource)) }
  deleted foreach { (id) => addModification(Deleted(id, source = "Test")) }
}

case class TestRepo(journal: Journal[TestResource, UUID])
    extends JournaledRepository[TestResource, UUID] {}

case class TestCore(name: String)
