package fi.vm.sade.hakurekisteri.test.tools

import java.util.UUID

import akka.actor.Actor
import fi.vm.sade.hakurekisteri.rest.support.{Resource, UUIDResource, Query}
import fi.vm.sade.hakurekisteri.storage.Identified

class MockedResourceActor[T <: Resource[I, T]: Manifest, I](
  save: (T with Identified[I]) => Unit = { (r: T) => },
  query: (Query[T]) => Seq[T] = { (q: Query[T]) => Seq() }
) extends Actor {
  override def receive: Receive = {
    case q: Query[T] =>
      sender ! query(q)
    case r: T =>
      val ri = r.identify
      save(ri)
      sender ! ri
    case a: Any => println(s"got unrecognised message $a")
  }
}
