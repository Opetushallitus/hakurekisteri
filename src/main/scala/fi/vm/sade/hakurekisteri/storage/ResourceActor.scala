package fi.vm.sade.hakurekisteri.storage

import akka.actor.Actor
import fi.vm.sade.hakurekisteri.rest.support.{Resource, Query}
import akka.pattern.pipe
import scala.concurrent.{Future, ExecutionContext}
import java.lang.RuntimeException
import akka.actor.Status.Failure
import scala.util.Try
import fi.vm.sade.hakurekisteri.storage.repository.Repository
import java.util.UUID
import akka.event.Logging


abstract class ResourceActor[T: Manifest] extends Actor { this: Repository[T] with ResourceService[T] =>

  val log = Logging(context.system, this)

  implicit val executionContext: ExecutionContext = context.dispatcher

  def receive: Receive = {
    case q: Query[T] =>
      log.debug("received: " + q)
      findBy(q) pipeTo sender
    case o:T =>
      val saved = Try(save(o))
      log.debug("saved: " + saved)
      sender ! saved.recover{ case e:Exception => Failure(e)}.get
    case id:UUID =>
      sender ! get(id)
    case DeleteResource(id) =>
      log.debug(s"received delete request for resource: $id from $sender")
      sender ! delete(id)
      log.debug(s"deleted $id answered to $sender")

  }



}

case class DeleteResource(id:UUID)

