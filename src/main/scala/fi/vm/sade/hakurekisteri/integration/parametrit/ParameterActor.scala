package fi.vm.sade.hakurekisteri.integration.parametrit

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.batchimport.ImportBatch
import fi.vm.sade.hakurekisteri.integration.{FutureCache, VirkailijaRestClient}
import org.joda.time.DateTime

import scala.compat.Platform
import scala.concurrent.Future
import scala.concurrent.duration._

case class ParamsFailedException(haku: String, from: ActorRef, t: Throwable) extends Exception(s"call to parameter service failed for haku $haku", t)
case class NoParamFoundException(haku: String) extends Exception(s"no parameter found for haku $haku")

object ParameterActor {
  val opoUpdateGraduation = "opoUpdateGraduation"
}

abstract class ParameterActor extends Actor with ActorLogging {
  implicit val ec = context.dispatcher
  private val tiedonsiirtoSendingPeriodCache = new FutureCache[String, Boolean](2.minute.toMillis)
  protected val HTTP_OK = 200

  object ProcessNext

  override def receive: Actor.Receive = {
    case KierrosRequest(haku) =>
      getParams(haku).map(HakuParams) pipeTo sender

    case IsSendingEnabled(key) =>
      isSendingEnabled(key) pipeTo sender

      case IsRestrictionActive(restriction) =>
      isRestrictionActive(restriction) pipeTo sender

  }

  private def isSendingEnabled(key: String): Future[Boolean] = {
    if (tiedonsiirtoSendingPeriodCache.contains(key))
      tiedonsiirtoSendingPeriodCache.get(key)
    else {
      val future = isEnabledFromRest(key)
      tiedonsiirtoSendingPeriodCache + (key, future)
      future
    }
  }

  protected def isPeriodEffective(period: SendingPeriod): Boolean = {
    val now = Platform.currentTime
    period.dateStart <= now && period.dateEnd > now
  }

  protected def isAnyPeriodEffective(periods: List[SendingPeriod]): Boolean = {
    periods.exists(period => isPeriodEffective(period))
  }

  protected def getParams(hakuOid: String): Future[DateTime]

  protected def isEnabledFromRest(key: String): Future[Boolean]

  protected def isRestrictionActive(restriction: String): Future[Boolean]
}

class HttpParameterActor(restClient: VirkailijaRestClient) extends ParameterActor {
  private val allResponseCache = new FutureCache[String, Map[String, KierrosParams]](1.minute.toMillis)
  private val all = "ALL"

  private def getAll: Future[Map[String, KierrosParams]] = {
    if (allResponseCache.contains(all))
      allResponseCache.get(all)
    else {
      val allFuture = restClient.readObject[Map[String, KierrosParams]]("ohjausparametrit-service.all")(200, 2)
      allResponseCache + (all, allFuture)
      allFuture
    }
  }

  override def getParams(hakuOid: String): Future[DateTime] = {
    val allMap = getAll
    allMap.map(m => m.get(hakuOid) match {
      case Some(KierrosParams(Some(KierrosEndParams(date)))) => new DateTime(date)
      case _ => throw NoParamFoundException(hakuOid)
    })
  }

  override def isEnabledFromRest(key: String): Future[Boolean] =
    restClient.readObject[TiedonsiirtoSendingPeriods]("ohjausparametrit-service.parametri", "tiedonsiirtosendingperiods")(HTTP_OK).map(p => key match {
    case k if k == ImportBatch.batchTypePerustiedot => isPeriodEffective(p.perustiedot)
    case k if k == ImportBatch.batchTypeArvosanat => isPeriodEffective(p.arvosanat)
    case _ => false
  }).recoverWith {
    case t: Throwable =>
      log.error(t, "error retrieving parameter")
      Future.successful(false)
  }

  override def isRestrictionActive(restriction: String): Future[Boolean] =
    restClient.readObject[RestrictionPeriods]("ohjausparametrit-service.parametri", "restrictedperiods")(HTTP_OK).map(p => restriction match {
    case ParameterActor.opoUpdateGraduation =>  {
      isAnyPeriodEffective(p.opoUpdateGraduation)
    }
    case _ => false
  }).recoverWith {
    case t: Throwable =>
      log.error(t, "error retrieving parameter")
      Future.successful(false)
  }
}

class MockParameterActor(active: Boolean = false) extends ParameterActor {
  override protected def getParams(hakuOid: String) = Future { new DateTime().plusMonths(1) }

  override protected def isEnabledFromRest(key: String) = Future { true }

  override protected def isRestrictionActive(restriction: String) = Future.successful(active)
  def getActive() = active
}

case class KierrosRequest(haku: String)
case class KierrosEndParams(date: Long)
case class KierrosParams(PH_HKP: Option[KierrosEndParams])
case class HakuParams(end: DateTime)

case class IsSendingEnabled(key: String)
case class IsRestrictionActive(restriction: String)
case class SendingPeriod(dateStart: Long, dateEnd: Long)
case class TiedonsiirtoSendingPeriods(arvosanat: SendingPeriod, perustiedot: SendingPeriod)
case class RestrictionPeriods(opoUpdateGraduation: List[SendingPeriod])