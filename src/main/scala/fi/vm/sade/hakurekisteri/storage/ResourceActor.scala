package fi.vm.sade.hakurekisteri.storage

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging}
import akka.event.Logging
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.rest.support.{Query, QueryWithPersonOid, Resource}
import fi.vm.sade.hakurekisteri.storage.repository.Repository

import scala.concurrent.ExecutionContext
import scala.util.Try

abstract class ResourceActor[T <: Resource[I, T]: Manifest, I: Manifest](config: Config)
    extends Actor
    with ActorLogging { this: Repository[T, I] with ResourceService[T, I] =>
  implicit val executionContext: ExecutionContext = ExecutorUtil.createExecutor(
    config.integrations.asyncOperationThreadPoolSize,
    getClass.getSimpleName
  )

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
    case q: QueryWithPersonOid[T] =>
      findByWithPersonAliases(q) pipeTo sender

    case q: Query[T] =>
      findBy(q) pipeTo sender

    case o: T =>
      save(o) pipeTo sender

    case id: I =>
      sender ! operationOrFailure(() => get(id))

    case DeleteResource(id: I, user: String) =>
      sender ! operationOrFailure(() => {
        delete(id, user)
        id
      })

    case ids: Seq[_] if ids.isInstanceOf[Seq[I]] =>
      sender ! operationOrFailure(() => getAll(ids.asInstanceOf[Seq[I]]))

    case InsertResource(resource: T, personOidsWithAliases: PersonOidsWithAliases) =>
      if (personOidsWithAliases.henkiloOids.size > 1) {
        sender ! Failure(
          new IllegalArgumentException(
            s"Got ${personOidsWithAliases.henkiloOids.size} person aliases " +
              s"for inserting a single resource $resource . This would make the deduplication query unneccessarily heavy."
          )
        )
      } else {
        sender ! operationOrFailure(() => insert(resource, personOidsWithAliases))
      }

    case UpsertResource(resource: T, personOidsWithAliases: PersonOidsWithAliases) =>
      if (personOidsWithAliases.henkiloOids.size > 1) {
        sender ! Failure(
          new IllegalArgumentException(
            s"Got ${personOidsWithAliases.henkiloOids.size} person aliases " +
              s"for inserting a single resource $resource . This would make the deduplication query unneccessarily heavy."
          )
        )
      } else {
        save(resource, personOidsWithAliases) pipeTo sender
      }

    case LogMessage(message, level) =>
      log.log(level, message)
  }
}

case class DeleteResource[I](id: I, source: String)
case class InsertResource[I, T <: Resource[I, T]](
  resource: T,
  personOidsWithAliases: PersonOidsWithAliases
)
case class UpsertResource[I, T <: Resource[I, T]](
  resource: T,
  personOidsWithAliases: PersonOidsWithAliases
)
case class LogMessage(message: String, level: Logging.LogLevel)
