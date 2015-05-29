package fi.vm.sade.hakurekisteri.storage

import akka.actor.{ActorLogging, Cancellable, Actor}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.rest.support.{Resource, Query}
import akka.pattern.pipe
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import akka.actor.Status.Failure
import scala.util.Try
import fi.vm.sade.hakurekisteri.storage.repository.Repository

object GetCount

abstract class ResourceActor[T <: Resource[I, T] : Manifest, I : Manifest] extends Actor with ActorLogging { this: Repository[T, I] with ResourceService[T, I] =>
  implicit val executionContext: ExecutionContext = context.dispatcher
  val reloadInterval = 10.seconds

  override def postStop(): Unit = {
    reload.foreach((c) => c.cancel())
  }

  var reload: Option[Cancellable] = None

  override def preStart(): Unit = {
    reload = Some(context.system.scheduler.schedule(reloadInterval, reloadInterval, self, Reload))
  }

  private def operationOrFailure(operation: () => Any) = {
    val t = Try(operation())
    if (t.isFailure) {
      log.error(t.failed.get, "operation failed")
      Failure(t.failed.get)
    } else {
      t.get
    }
  }

  def receive: Receive = {
    case GetCount =>
      sender ! operationOrFailure(() => count)

    case q: Query[T] =>
      findBy(q) pipeTo sender

    case o: T =>
      sender ! operationOrFailure(() => save(o))

    case id: I =>
      sender ! operationOrFailure(() => get(id))

    case DeleteResource(id: I, user: String) =>
      sender ! operationOrFailure(() => {
        delete(id, user)
        id
      })

    case InsertResource(resource: T) =>
      sender ! operationOrFailure(() => insert(resource))

    case Reload  =>
      //log.debug(s"reloading from ${journal.latestReload}")
      //loadJournal(journal.latestReload)

    case LogMessage(message, level) =>
      log.log(level, message)
  }
}

case class DeleteResource[I](id: I, source: String)
case class InsertResource[I, T <: Resource[I, T]](resource: T)

object Reload

case class LogMessage(message: String, level: Logging.LogLevel)