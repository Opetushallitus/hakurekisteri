package fi.vm.sade.hakurekisteri.test.tools

import java.util.UUID

import akka.actor.Actor
import fi.vm.sade.hakurekisteri.rest.support.{UUIDResource, Query}
import fi.vm.sade.hakurekisteri.storage.Identified

class MockedResourceActor[T <: UUIDResource[_]](save: (T with Identified[UUID]) => Unit = {(r: T) => }, query: (Query[T]) => Seq[T] = {(q: Query[T]) => Seq()}) extends Actor {
  override def receive: Receive = {
    case q: Query[T] =>
      sender ! query(q)
    case r: T =>
      val ri = r.identify(UUID.randomUUID()).asInstanceOf[T with Identified[UUID]]
      save(ri)
      sender ! ri
    case a: Any => println(s"got unrecognised message $a")
  }
}
