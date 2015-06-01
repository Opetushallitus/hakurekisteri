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

abstract class ParameterActor extends Actor with ActorLogging {
  implicit val ec = context.dispatcher
  private val tiedonsiirtoSendingPeriodCache = new FutureCache[String, Boolean](2.minute.toMillis)
  object ProcessNext

  override def receive: Actor.Receive = {
    case KierrosRequest(haku) =>
      getParams(haku).map(HakuParams) pipeTo sender

    case IsSendingEnabled(key) =>
      isSendingEnabled(key) pipeTo sender
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

  protected def getParams(hakuOid: String): Future[DateTime]

  protected def isEnabledFromRest(key: String): Future[Boolean]
}

class HttpParameterActor(restClient: VirkailijaRestClient) extends ParameterActor {
  private val allResponseCache = new FutureCache[String, Map[String, KierrosParams]](1.minute.toMillis)
  private val all = "ALL"

  private def getAll: Future[Map[String, KierrosParams]] = {
    if (allResponseCache.contains(all))
      allResponseCache.get(all)
    else {
      val allFuture = restClient.readObject[Map[String, KierrosParams]](s"/api/v1/rest/parametri/ALL", 200, 2)
      allResponseCache + (all, allFuture)
      allFuture
    }
  }

  override def getParams(hakuOid: String): Future[DateTime] = {
    val allMap = getAll
    allMap.map(m => m.get(hakuOid) match {
      case Some(params) =>
        params.PH_HKP match {
          case Some(KierrosEndParams(date)) => new DateTime(date)
          case _ => throw NoParamFoundException(hakuOid)
        }
      case _ =>
        throw NoParamFoundException(hakuOid)
    })
  }

  override def isEnabledFromRest(key: String): Future[Boolean] = restClient.readObject[TiedonsiirtoSendingPeriods]("/api/v1/rest/parametri/tiedonsiirtosendingperiods", 200).map(p => key match {
    case k if k == ImportBatch.batchTypePerustiedot => isPeriodEffective(p.perustiedot)
    case k if k == ImportBatch.batchTypeArvosanat => isPeriodEffective(p.arvosanat)
    case _ => false
  }).recoverWith {
    case t: Throwable =>
      log.error(t, "error retrieving parameter")
      Future.successful(false)
  }
}

class MockParameterActor extends ParameterActor {
  override protected def getParams(hakuOid: String) = Future { new DateTime }

  override protected def isEnabledFromRest(key: String) = Future { true }
}

case class KierrosRequest(haku: String)
case class KierrosEndParams(date: Long)
case class KierrosParams(PH_HKP: Option[KierrosEndParams])
case class HakuParams(end: DateTime)

case class IsSendingEnabled(key: String)
case class SendingPeriod(dateStart: Long, dateEnd: Long)
case class TiedonsiirtoSendingPeriods(arvosanat: SendingPeriod, perustiedot: SendingPeriod)