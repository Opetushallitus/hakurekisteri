package fi.vm.sade.hakurekisteri.storage

import akka.actor.Actor
import fi.vm.sade.hakurekisteri.rest.support.Query
import scala.reflect.runtime.universe._

abstract class ResourceActor[T: Manifest] extends Actor { this: Repository[T] with ResourceService[T] =>

  def receive: Receive = {
    case q: Query[T] =>
      println("received: " + q)
      val by = findBy(q)
      println("found: " + by)
      sender ! by
    case o:T =>
      println("received: " + o)
      val saved = save(o)
      println("saved: " + saved)
      sender ! saved
  }

}
