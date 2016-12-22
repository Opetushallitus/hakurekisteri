package fi.vm.sade.hakurekisteri.storage

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, Status}
import akka.event.Logging
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.rest.support.{Query, QueryWithPersonAliasesResolver, Resource}
import fi.vm.sade.hakurekisteri.storage.repository.Repository

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Try

abstract class ResourceActor[T <: Resource[I, T] : Manifest, I : Manifest] extends Actor with ActorLogging { this: Repository[T, I] with ResourceService[T, I] =>
  implicit val executionContext: ExecutionContext = context.dispatcher

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
    case q: QueryWithPersonAliasesResolver[T] =>
      findByWithPersonAliases(q) pipeTo sender

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

    case ids: Seq[_] if ids.isInstanceOf[Seq[I]] =>
      sender ! operationOrFailure(() => getAll(ids.asInstanceOf[Seq[I]]))

    case InsertResource(resource: T) =>
      sender ! operationOrFailure(() => insert(resource))

    case LogMessage(message, level) =>
      log.log(level, message)
  }
}

case class DeleteResource[I](id: I, source: String)
case class InsertResource[I, T <: Resource[I, T]](resource: T)
case class LogMessage(message: String, level: Logging.LogLevel)
