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
      log.debug("deduplicating: %s from %s" format(o,sender))
      val dedupcursor = cursor
      findBy(DeduplicationQuery(o)).map((s) => {log.debug("deduplicated: " + s);s.headOption match { case None => DeduplicatedResource(o,dedupcursor) case Some(duplicate) => DuplicateResource(duplicate)}}).pipeTo(self)(sender)
    case DuplicateResource(o:T with Identified) =>
      sender ! o
    case DeduplicatedResource(o:T, dedupcursor) if dedupcursor == cursor =>
      log.debug("received: " + o)
      val saved = Try(save(o))
      log.debug("saved: " + saved)
      sender ! saved.recover{ case e:Exception => Failure(e)}.get
    case DeduplicatedResource(o:T, dedupcursor) if dedupcursor != cursor =>
      log.debug("obosolete dedup result for %s with cursor %s. current cursor %s" format (o,dedupcursor,cursor) )
      self.forward(o)
    case id:UUID =>
      sender ! get(id)

  }

  sealed abstract class DeduplicationResult[T]

  case class DeduplicatedResource[T](resource:T, cursor:Any) extends  DeduplicationResult[T]
  case class DuplicateResource[T](resource:T with Identified) extends  DeduplicationResult[T]





}
