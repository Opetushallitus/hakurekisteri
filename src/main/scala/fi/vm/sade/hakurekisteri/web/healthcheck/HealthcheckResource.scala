package fi.vm.sade.hakurekisteri.web.healthcheck

import java.util.Locale

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import org.joda.time.DateTimeZone
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{AsyncResult, FutureSupport}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


class HealthcheckResource(healthcheckActor: ActorRef)(implicit system: ActorSystem) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport {
  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout: Timeout = 60.seconds
  override val logger: LoggingAdapter = Logging.getLogger(system, this)
  private def withLocaleTZ(format: DateTimeFormatter) = format withLocale Locale.US withZone DateTimeZone.UTC
  private def expiresHeader = "Expires"
  val RFC1123Date = withLocaleTZ(DateTimeFormat forPattern "EEE, dd MMM yyyy HH:mm:ss 'GMT'")
  val expiresTimeMillis = 60000

  before() {
    contentType = formats("json")
  }

  get("/") {
    response.setHeader(expiresHeader, RFC1123Date.print(System.currentTimeMillis() + expiresTimeMillis))
    new AsyncResult() {
      override def timeout: Duration = 60.seconds
      val is = healthcheckActor ? "healthcheck"
    }
  }
}
