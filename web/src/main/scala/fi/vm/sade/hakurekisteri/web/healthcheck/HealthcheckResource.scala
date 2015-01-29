package fi.vm.sade.hakurekisteri.web.healthcheck

import akka.actor.{ActorSystem, ActorRef}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{AsyncResult, CorsSupport, FutureSupport}
import scala.concurrent.ExecutionContext
import akka.util.Timeout
import akka.event.{Logging, LoggingAdapter}
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import java.util.Locale
import org.joda.time.DateTimeZone
import scala.concurrent.duration._
import akka.pattern.ask


class HealthcheckResource(healthcheckActor: ActorRef)(implicit system: ActorSystem) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport {
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

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  get("/") {
    response.setHeader(expiresHeader, RFC1123Date.print(System.currentTimeMillis() + expiresTimeMillis))
    new AsyncResult() {
      override def timeout: Duration = 60.seconds
      val is = healthcheckActor ? "healthcheck"
    }
  }
}
