package fi.vm.sade.hakurekisteri.storage

import akka.actor.{Cancellable, Actor}
import fi.vm.sade.hakurekisteri.rest.support.{Resource, Query}
import akka.pattern.pipe
import scala.concurrent.{Future, ExecutionContext}
import java.lang.RuntimeException
import akka.actor.Status.Failure
import scala.util.Try
import fi.vm.sade.hakurekisteri.storage.repository.{JournaledRepository, Repository}
import java.util.UUID
import akka.event.Logging


abstract class ResourceActor[T <: Resource : Manifest ] extends Actor { this: JournaledRepository[T] with ResourceService[T] =>

  val log = Logging(context.system, this)

  import scala.concurrent.duration._
  val reloadInterval = 10.seconds

  override def postStop(): Unit = reload.foreach((c) => if (!c.isCancelled) c.cancel())

  var reload:Option[Cancellable] = None


  override def preStart(): Unit = reload = Some(context.system.scheduler.schedule(reloadInterval, reloadInterval, self, Reload))

  implicit val executionContext: ExecutionContext = context.dispatcher

  def receive: Receive = {
    case q: Query[T] =>
      log.debug(s"received: $q from $sender")
      val result = findBy(q)
      val recipient = sender
      result pipeTo recipient
      result.onSuccess{
        case s => log.debug(s"answered query $q with ${s.size} results to $recipient")
      }
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
    case Reload  =>
      //log.debug(s"reloading from ${journal.latestReload}")
      loadJournal(journal.latestReload)

  }



}

case class DeleteResource(id:UUID)

object Reload