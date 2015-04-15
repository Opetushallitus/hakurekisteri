package fi.vm.sade.hakurekisteri.test.tools

import java.util.UUID

import akka.actor.Actor
import fi.vm.sade.hakurekisteri.rest.support.{UUIDResource, Query}

class MockedResourceActor[T <: UUIDResource[_]](save: (T) => Unit = {(r: T) => }, query: (Query[T]) => Seq[T] = {(q: Query[T]) => Seq()}) extends Actor {
  override def receive: Receive = {
    case q: Query[T] =>
      sender ! query(q)
    case r: T =>
      save(r)
      sender ! r.identify(UUID.randomUUID())
    case a: Any => println(s"got unrecognised message $a")
  }
}
