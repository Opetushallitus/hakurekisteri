package fi.vm.sade.hakurekisteri.healthcheck

import _root_.akka.pattern
import _root_.akka.util.Timeout
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import org.scalatra._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import scala.concurrent.{Await, Future, ExecutionContext}
import _root_.akka.actor.{Actor, ActorRef, ActorSystem}
import _root_.akka.pattern.ask
import java.util.concurrent.TimeUnit
import org.scalatra.json._
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery}
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaQuery}
import fi.vm.sade.hakurekisteri.organization.AuthorizedQuery
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.format.{DateTimeFormatter, DateTimeFormat}
import java.util.Locale
import org.joda.time.DateTimeZone
import scala.concurrent.duration.Duration
import ExecutionContext.Implicits.global

class HealthcheckResource(healthcheckActor: ActorRef)(implicit system: ActorSystem) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport {
  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout = Timeout(60, TimeUnit.SECONDS)
  private def withLocaleTZ(format: DateTimeFormatter) = format withLocale Locale.US withZone DateTimeZone.UTC
  private def expiresHeader = "Expires"
  val RFC1123Date = withLocaleTZ(DateTimeFormat forPattern "EEE, dd MMM yyyy HH:mm:ss 'GMT'")
  val expiresTimeMillis = 60000

  before() {
    contentType = formats("json")
  }

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  get("/") {
    response.setHeader(expiresHeader, RFC1123Date.print(System.currentTimeMillis() + expiresTimeMillis))
    new AsyncResult() {
      val is = healthcheckActor ? "healthcheck"
    }
  }

}

case class FutureHelper[T](f: Future[T]) extends AnyVal {
  def orDefault(t: Timeout, default: => T)(implicit system: ActorSystem): Future[T] = {
    val delayed = pattern.after(t.duration, system.scheduler)(Future.successful(default))
    Future firstCompletedOf Seq(f, delayed)
  }
}

class HealthcheckActor(suoritusRekisteri: ActorRef, opiskelijaRekisteri: ActorRef)(implicit system: ActorSystem) extends Actor {
  protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout = Timeout(60, TimeUnit.SECONDS)
  val countTimeout = Timeout(30, TimeUnit.SECONDS)
  val countDuration = Duration(45, TimeUnit.SECONDS)
  val authorities = Seq("1.2.246.562.10.00000000001")

  def receive = {
    case "healthcheck" => {
      val combinedFuture =
        for {
          suoritusCount <- FutureHelper(getSuoritusCount).orDefault(countTimeout, -1)
          opiskelijaCount <- FutureHelper(getOpiskelijaCount).orDefault(countTimeout, -1)
        } yield (suoritusCount, opiskelijaCount)

      val (suoritusCount, opiskelijaCount) = Await.result(combinedFuture, countDuration)

      sender ! Healhcheck(System.currentTimeMillis(), "anonymousUser", "/suoritusrekisteri", Checks(Resources(suoritusCount, opiskelijaCount)), "OK", "")
    }
  }

  def getSuoritusCount: Future[Long] = {
    val suoritusFuture = (suoritusRekisteri ? AuthorizedQuery(SuoritusQuery(None, None, None), authorities)).mapTo[Seq[Suoritus with Identified]]
    suoritusFuture.map(_.length.toLong).
      recover {
      case e: Throwable => -1
    }
  }

  def getOpiskelijaCount: Future[Long] = {
    val opiskelijaFuture = (opiskelijaRekisteri ? AuthorizedQuery(OpiskelijaQuery(None, None, None, None, None, None), authorities)).mapTo[Seq[Opiskelija with Identified]]
    opiskelijaFuture.map(_.length.toLong).
      recover {
      case e: Throwable => -1
    }
  }

}

case class Checks(resources: Resources)

case class Resources(suoritukset: Long, opiskelijat: Long)

case class Healhcheck(timestamp: Long, user: String, contextPath: String, checks: Checks, status: String, info: String)

