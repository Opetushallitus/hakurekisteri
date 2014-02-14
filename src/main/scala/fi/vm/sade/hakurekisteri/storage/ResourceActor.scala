package fi.vm.sade.hakurekisteri.storage

import akka.actor.Actor
import fi.vm.sade.hakurekisteri.rest.support.Query
import akka.pattern.pipe
import scala.concurrent.ExecutionContext
import java.lang.RuntimeException
import akka.actor.Status.Failure
import scala.util.Try

abstract class ResourceActor[T: Manifest] extends Actor { this: Repository[T] with ResourceService[T] =>

  implicit val executionContext: ExecutionContext = context.dispatcher

  def receive: Receive = {
    case q: Query[T] =>
      println("received: " + q)
      findBy(q) pipeTo sender
    case o:T =>
      println("received: " + o)
      val saved = Try(save(o))
      println("saved: " + saved)
      sender ! saved.recover{ case e:Exception => Failure(e)}.get

  }

}
