package fi.vm.sade.hakurekisteri.storage

import akka.actor.{Cancellable, Actor}
import fi.vm.sade.hakurekisteri.rest.support.{Resource, Query}
import akka.pattern.pipe
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import akka.actor.Status.Failure
import scala.util.Try
import fi.vm.sade.hakurekisteri.storage.repository.{Repository, JournaledRepository}
import akka.event.Logging


abstract class ResourceActor[T <: Resource[I, T] : Manifest, I : Manifest] extends Actor { this: Repository[T, I] with ResourceService[T, I] =>
  implicit val executionContext: ExecutionContext = context.dispatcher
  val log = Logging(context.system, this)
  val reloadInterval = 10.seconds

  override def postStop(): Unit = {
    report.foreach((c) => if (!c.isCancelled) c.cancel())
    reload.foreach((c) => if (!c.isCancelled) c.cancel())
  }

  object Report

  var report: Option[Cancellable] = None
  var reload: Option[Cancellable] = None
  var saved = 0

  override def preStart(): Unit = {
    report = Some(context.system.scheduler.schedule(1.minute, 1.minute, self, Report))
    reload = Some(context.system.scheduler.schedule(reloadInterval, reloadInterval, self, Reload))
  }

  def receive: Receive = {
    case q: Query[T] =>
      log.debug(s"received: $q from $sender")
      val result = findBy(q)
      val recipient = sender
      result pipeTo recipient
      result.onSuccess{
        case s => log.debug(s"answered query $q with ${s.size} results to $recipient")
      }
    case o: T =>
      saved = saved + 1
      val saveTry = Try(save(o))
      if (saveTry.isFailure)
        log.error(saveTry.failed.get, "save failed")
      sender ! saveTry.recover{ case e: Exception => Failure(e)}.get
    case id: I =>
      sender ! get(id)
    case DeleteResource(id: I, user: String) =>
      log.debug(s"received delete request for resource: $id from $sender")
      sender ! delete(id, user)
      log.debug(s"deleted $id answered to $sender")
    case Report =>
      log.debug(s"saved: $saved")
    case Reload  =>
      //log.debug(s"reloading from ${journal.latestReload}")
      //loadJournal(journal.latestReload)
  }
}

case class DeleteResource[I](id: I, source: String)

object Reload