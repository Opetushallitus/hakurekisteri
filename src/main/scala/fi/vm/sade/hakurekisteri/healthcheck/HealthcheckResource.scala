package fi.vm.sade.hakurekisteri.healthcheck

import _root_.akka.util.Timeout
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import scala.concurrent.{Future, ExecutionContext}
import _root_.akka.actor.{Actor, ActorRef, ActorSystem}
import _root_.akka.pattern.{AskTimeoutException, ask}
import java.util.concurrent.TimeUnit
import org.scalatra.json._
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery}
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaQuery}
import fi.vm.sade.hakurekisteri.organization.AuthorizedQuery
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.format.{DateTimeFormatter, DateTimeFormat}
import java.util.{UUID, Locale}
import org.joda.time.DateTimeZone
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.healthcheck.Status.Status
import org.scalatra.{AsyncResult, CorsSupport, FutureSupport}
import fi.vm.sade.hakurekisteri.hakija.{Hakemus, HakemusQuery}

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

class HealthcheckActor(suoritusRekisteri: ActorRef, opiskelijaRekisteri: ActorRef, hakemukset: ActorRef)(implicit system: ActorSystem) extends Actor {
  protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout = Timeout(30, TimeUnit.SECONDS)
  val authorities = Seq("1.2.246.562.10.00000000001")
  var foundHakemukset = 0L


  override def preStart(): Unit = {
    hakemukset ! Health(self)
    super.preStart()
  }

  def receive = {
    case Hakemukset(count) => foundHakemukset = count
    case "healthcheck" => {
      val combinedFuture =
        for {
          suoritusCount <- getSuoritusCount
          opiskelijaCount <- getOpiskelijaCount
          hakemusCount <- getHakemusCount
        } yield (suoritusCount, opiskelijaCount, hakemusCount)

      combinedFuture map { case (suoritusCount, opiskelijaCount, hakemusCount) =>
        Healhcheck(System.currentTimeMillis(),
          "anonymousUser",
          "/suoritusrekisteri",
          Checks(Resources(suoritusCount.count, opiskelijaCount.count, hakemusCount.count, foundHakemukset)),
          resolveStatus(suoritusCount.status, opiskelijaCount.status, hakemusCount.status),
          "")} pipeTo sender
    }
  }

  def resolveStatus(suoritusStatus: Status, opiskelijaStatus: Status, hakemusStatus: Status) = {
    if (suoritusStatus == Status.TIMEOUT || opiskelijaStatus == Status.TIMEOUT || hakemusStatus == Status.TIMEOUT)
      Status.TIMEOUT
    else if (suoritusStatus == Status.FAILURE || opiskelijaStatus == Status.FAILURE || hakemusStatus == Status.FAILURE)
      Status.FAILURE
    else
      Status.OK
  }

  def getSuoritusCount: Future[ItemCount] = {
    val suoritusFuture = (suoritusRekisteri ? AuthorizedQuery(SuoritusQuery(None, None, None, None), authorities, "healthcheck"))
      .mapTo[Seq[Suoritus with Identified[UUID]]]
    suoritusFuture.map((s) => { new ItemCount(Status.OK, s.length.toLong) }).recover {
      case e: AskTimeoutException => new ItemCount(Status.TIMEOUT, 0)
      case e: Throwable => println("error getting suoritus count: " + e); new ItemCount(Status.FAILURE, 0)
    }
  }

  def getOpiskelijaCount: Future[ItemCount] = {
    val opiskelijaFuture = (opiskelijaRekisteri ? AuthorizedQuery(OpiskelijaQuery(None, None, None, None, None, None), authorities, "healthcheck"))
      .mapTo[Seq[Opiskelija with Identified[UUID]]]
    opiskelijaFuture.map((o) => { ItemCount(Status.OK, o.length.toLong) }).recover {
      case e: AskTimeoutException => ItemCount(Status.TIMEOUT, 0)
      case e: Throwable => println("error getting opiskelija count: " + e); ItemCount(Status.FAILURE, 0)
    }
  }

  def getHakemusCount: Future[ItemCount] = {
    val hakemusFuture = (hakemukset ? HakemusQuery(None, None, None))
      .mapTo[Seq[Hakemus with Identified[String]]]
    hakemusFuture.map((s) => { new ItemCount(Status.OK, s.length.toLong) }).recover {
      case e: AskTimeoutException => new ItemCount(Status.TIMEOUT, 0)
      case e: Throwable => println("error getting hakemus count: " + e); new ItemCount(Status.FAILURE, 0)
    }
  }
}

object Status extends Enumeration {
  type Status = Value
  val OK, TIMEOUT, FAILURE = Value
}

case class ItemCount(status: Status, count: Long)

case class Checks(resources: Resources)

case class Resources(suoritukset: Long, opiskelijat: Long, hakemukset: Long, foundHakemukset: Long)

case class Healhcheck(timestamp: Long, user: String, contextPath: String, checks: Checks, status: Status, info: String)


case class Hakemukset(count: Long)

case class Health(actor: ActorRef)