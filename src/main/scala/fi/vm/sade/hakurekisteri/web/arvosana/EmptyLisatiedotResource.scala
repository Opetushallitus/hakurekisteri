package fi.vm.sade.hakurekisteri.web.arvosana

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana.EmptyLisatiedot
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, User}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{
  QueryLogging,
  Security,
  SecuritySupport,
  UserNotAuthorized
}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{AsyncResult, FutureSupport}

import scala.compat.Platform
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, _}

class EmptyLisatiedotResource(arvosanaActor: ActorRef)(implicit
  val system: ActorSystem,
  val security: Security
) extends HakuJaValintarekisteriStack
    with HakurekisteriJsonSupport
    with JacksonJsonSupport
    with FutureSupport
    with SecuritySupport
    with QueryLogging {
  override protected implicit def executor: ExecutionContext = system.dispatcher
  override val logger: LoggingAdapter = Logging.getLogger(system, this)
  implicit val defaultTimeout: Timeout = 120.seconds
  before() {
    contentType = formats("json")
  }

  def getAdmin: User = {
    currentUser match {
      case Some(u) if u.isAdmin => u
      case None                 => throw UserNotAuthorized(s"anonymous access not allowed")
    }
  }

  get("/") {
    val t0 = Platform.currentTime
    implicit val user = getAdmin

    new AsyncResult() {
      override implicit def timeout: Duration = 120.seconds

      private val tiedotFuture = arvosanaActor ? EmptyLisatiedot()

      logQuery(EmptyLisatiedot(), t0, tiedotFuture)
      val is = tiedotFuture
    }
  }
}
