package fi.vm.sade.hakurekisteri

import fi.vm.sade.hakurekisteri.storage.{Identified, ResourceService, ResourceActor}
import fi.vm.sade.hakurekisteri.rest.support.Query
import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.repository.Repository
import scala.compat.Platform


class TestActor extends ResourceActor[TestResource]  with Repository[TestResource] with ResourceService[TestResource] {

  var store:Seq[TestResource] = Seq()


  def save(t: TestResource): TestResource with Identified = {
    println("saving: " + t)
    store = t +: store
    currentCursor = Platform.currentTime
    identify(t)
  }


  def identify(t: TestResource): TestResource with Identified {val id: UUID} = {
    new TestResource(t.name) with Identified {
      val id = UUID.randomUUID()
    }
  }

  def listAll(): Seq[TestResource with Identified] = store.map(identify)

  val matcher: PartialFunction[Query[TestResource], (TestResource with Identified) => Boolean] = { case _ => (_) => true}

  override def get(id: UUID): Option[TestResource with Identified] = None.asInstanceOf[Option[TestResource with Identified]]

  var currentCursor = Platform.currentTime

  override def cursor: Any = currentCursor

  override def delete(id:UUID) = ???
}
