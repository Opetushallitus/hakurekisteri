package fi.vm.sade.hakurekisteri.healthcheck

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor._
import akka.pattern.{AskTimeoutException, ask, pipe}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.ensikertalainen.{QueriesRunning, QueryCount}
import fi.vm.sade.hakurekisteri.healthcheck.Status.Status
import fi.vm.sade.hakurekisteri.integration.virta.{VirtaHealth, VirtaStatus}
import fi.vm.sade.hakurekisteri.integration.ytl.{Batch, Report, YtlReport}
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.storage.GetCount
import fi.vm.sade.hakurekisteri.{Config, Oids}
import org.joda.time.DateTime

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}


case class RefreshingResource(amount:Long, reloading: Boolean = false, time:DateTime = DateTime.now)
case class RefreshingState(max: RefreshingResource, latest: RefreshingResource)


class HealthcheckActor(arvosanaRekisteri: ActorRef,
                       opiskelijaRekisteri: ActorRef,
                       opiskeluoikeusRekisteri: ActorRef,
                       suoritusRekisteri: ActorRef,
                       eraRekisteri: ActorRef,
                       ytl: ActorRef,
                       ensikertalainenActor: ActorRef,
                       virtaQueue: ActorRef,
                       config: Config)(implicit system: ActorSystem) extends Actor with ActorLogging {
  protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout = Timeout(30, TimeUnit.SECONDS)

  val resources = Set("Arvosana", "Suoritus", "Opiskeluoikeus", "Opiskelija", "ImportBatch")

  val healthCheckUser = BasicUser("healthcheck", resources.map(ReadRole( _, Oids.ophOrganisaatioOid)))
  var foundHakemukset:Map[String, RefreshingState] = Map()

  var selfChecks: Map[UUID, Long] = Map()

  val writer = context.actorOf(Props(new HealthCheckWriter(self)))

  case class SelfCheck(id: UUID = UUID.randomUUID())
  case class Measure(id:UUID)

  context.system.scheduler.schedule(5.minutes, 5.minutes, self, SelfCheck())

  override def preStart(): Unit = {
    super.preStart()
  }

  def receive = {
    case SelfCheck(id) =>
      selfChecks = selfChecks + (id -> Platform.currentTime)
      self ! Measure(id)

    case Measure(id) =>
      val arrival = Platform.currentTime
      val roundTrip = selfChecks.get(id).map(arrival - _)
      if (!(roundTrip.getOrElse(0L) < 30000)) log.warning(s"Healthcheck is too slow. Measured roundtrip over 30s: ${roundTrip}ms")
      selfChecks = selfChecks - id

    case Hakemukset(oid, count) =>
      val curState = foundHakemukset.get(oid).map{
        case RefreshingState(max, latest) if  max.amount <= count.amount => RefreshingState(count, count)
        case RefreshingState(max, latest) => RefreshingState(max, count)
      }.getOrElse(RefreshingState(count, count))
      foundHakemukset = foundHakemukset + (oid -> curState)

    case "healthcheck" =>
      checkState pipeTo sender
  }

  def checkState: Future[Healhcheck] = {
    val startTime = Platform.currentTime
    for {
      arvosanaCount <- getArvosanaCount
      opiskelijaCount <- getOpiskelijaCount
      opiskeluoikeusCount <- getOpiskeluoikeusCount
      suoritusCount <- getSuoritusCount
      eraCount <- getEraCount
      ytlReport <- getYtlReport
      ensikertalaiset <- getEnsikertalainenReport
      virtaStatus <- getVirtaStatus
    } yield Healhcheck(startTime,
      "anonymousUser",
      "/suoritusrekisteri",
      Checks(Resources(
        arvosanat = arvosanaCount,
        opiskelijat = opiskelijaCount,
        opiskeluoikeudet = opiskeluoikeusCount,
        suoritukset = suoritusCount,
        erat = eraCount,
        foundHakemukset = foundHakemukset,
        ensikertalaiset,
        ytl = ytlReport,
        virta = virtaStatus
      )), "")
  }

  def getVirtaStatus: Future[VirtaStatus] = (virtaQueue ? VirtaHealth).mapTo[VirtaStatus].recover {
    case e: AskTimeoutException => VirtaStatus(queueLength = 0, status = Status.TIMEOUT)
    case e: Throwable => VirtaStatus(queueLength = 0, status = Status.FAILURE)
  }

  def getEnsikertalainenReport: Future[QueryReport] = (ensikertalainenActor ? QueryCount).map{
    case QueriesRunning(count, time) => QueryReport(Status.OK, count,time)}.recover {
    case e: AskTimeoutException => new QueryReport(Status.TIMEOUT, Map())
    case e: Throwable => log.error(e, "error getting ensikertalainen status"); QueryReport(Status.FAILURE, Map())
  }

  def getYtlReport: Future[YtlStatus] = {
    val ytlFuture = (ytl ? Report).mapTo[YtlReport]

    ytlFuture.map((yr) => { YtlOk(
      waitingForAnswer = yr.waitingforAnswers.map(BatchReport(_)),
      yr.nextSend
    ) }).recover {
      case e: AskTimeoutException => new YtlFailure(Status.TIMEOUT)
      case e: Throwable => log.error(e, "error getting ytl status"); new YtlFailure(Status.FAILURE)
    }
  }

  def getEraCount: Future[ItemCount] = {
    val batchFuture = (eraRekisteri ? GetCount).mapTo[Int]
    batchFuture.map((b) => { new ItemCount(Status.OK, b.toLong) }).recover  {
      case e: AskTimeoutException => new ItemCount(Status.TIMEOUT, 0)
      case e: Throwable => log.error(e, "error getting era count"); new ItemCount(Status.FAILURE, 0)
    }
  }

  def getArvosanaCount: Future[ItemCount] = {
    val arvosanaFuture = (arvosanaRekisteri ? GetCount).mapTo[Int]
    arvosanaFuture.map((a) => { new ItemCount(Status.OK, a.toLong) }).recover {
      case e: AskTimeoutException => new ItemCount(Status.TIMEOUT, 0)
      case e: Throwable => log.error(e,"error getting arvosana count"); new ItemCount(Status.FAILURE, 0)
    }
  }

  def getSuoritusCount: Future[ItemCount] = {
    val suoritusFuture = (suoritusRekisteri ? GetCount).mapTo[Int]
    suoritusFuture.map((s) => { new ItemCount(Status.OK, s.toLong) }).recover {
      case e: AskTimeoutException => new ItemCount(Status.TIMEOUT, 0)
      case e: Throwable => log.error(e,"error getting suoritus count"); new ItemCount(Status.FAILURE, 0)
    }
  }

  def getOpiskelijaCount: Future[ItemCount] = {
    val opiskelijaFuture = (opiskelijaRekisteri ? GetCount).mapTo[Int]
    opiskelijaFuture.map((o) => { ItemCount(Status.OK, o.toLong) }).recover {
      case e: AskTimeoutException => ItemCount(Status.TIMEOUT, 0)
      case e: Throwable => log.error(e,"error getting opiskelija count"); ItemCount(Status.FAILURE, 0)
    }
  }

  def getOpiskeluoikeusCount: Future[ItemCount] = {
    val opiskeluoikeusFuture = (opiskeluoikeusRekisteri ? GetCount).mapTo[Int]
    opiskeluoikeusFuture.map((o) => { ItemCount(Status.OK, o.toLong) }).recover {
      case e: AskTimeoutException => ItemCount(Status.TIMEOUT, 0)
      case e: Throwable => log.error(e,"error getting opiskeluoikeus count"); ItemCount(Status.FAILURE, 0)
    }
  }
}

object Status extends Enumeration {
  type Status = Value
  val OK, TIMEOUT, FAILURE = Value
}

case class ItemCount(status: Status, count: Long, endTime: Long = Platform.currentTime)

case class QueryReport(status: Status, count: Map[String, Int], endTime: Long = Platform.currentTime)


sealed abstract class YtlStatus {
  val status: Status
}

case class YtlOk(waitingForAnswer: Seq[BatchReport], nextSendTime: Option[DateTime]) extends YtlStatus  {
  val status = Status.OK
}

case class YtlFailure(status: Status)  extends YtlStatus

case class Checks(resources: Resources)

case class Resources(arvosanat: ItemCount,
                     opiskelijat: ItemCount,
                     opiskeluoikeudet: ItemCount,
                     suoritukset: ItemCount,
                     erat: ItemCount,
                     foundHakemukset: Map[String, RefreshingState],
                     ensikertalainenQueries: QueryReport,
                     ytl: YtlStatus,
                     virta: VirtaStatus)

case class Healhcheck(start: Long, user: String, contextPath: String, checks: Checks, info: String, end: Long = Platform.currentTime) {

  val status = resolveStatus(checks.resources.arvosanat.status, checks.resources.opiskelijat.status, checks.resources.opiskeluoikeudet.status, checks.resources.suoritukset.status, checks.resources.ytl.status)

  def resolveStatus(statuses: Status*) = {
    if (statuses.contains(Status.TIMEOUT)) Status.TIMEOUT
    else if (statuses.contains(Status.FAILURE)) Status.FAILURE
    else Status.OK
  }

}

case class Hakemukset(oid: String, count: RefreshingResource)

case class Health(actor: ActorRef)

case class BatchReport(id: UUID, count: Int)

object BatchReport {

  def apply(batch: Batch[_]):BatchReport = BatchReport(batch.id, batch.items.size)

}