package fi.vm.sade.hakurekisteri.test.tools

import akka.actor.Actor
import fi.vm.sade.hakurekisteri.rest.support.Query

class MockedResourceActor[T](save: (T) => Unit = {(r: T) => }, query: (Query[T]) => Seq[T] = {(q: Query[T]) => Seq()}) extends Actor {
  override def receive: Receive = {
    case q: Query[T] =>
      sender ! query(q)
    case r: T =>
      save(r)
      sender ! r
    case a: Any => println(s"got unrecognised message $a")
  }
}
