package fi.vm.sade.hakurekisteri.integration.haku

import akka.actor.{Actor, ActorLogging, Cancellable}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.dates.InFuture
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.integration.koski.IKoskiService
import fi.vm.sade.hakurekisteri.integration.parametrit.{
  HakuParams,
  KierrosRequest,
  ParametritActorRef
}
import fi.vm.sade.hakurekisteri.integration.tarjonta._
import fi.vm.sade.hakurekisteri.integration.ytl.YtlIntegration
import org.joda.time.ReadableInstant

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions
import scala.util.Failure

class HakuActor(
  hakuAggregator: HakuAggregatorActorRef,
  koskiService: IKoskiService,
  parametrit: ParametritActorRef,
  ytlIntegration: YtlIntegration,
  config: Config
) extends Actor
    with ActorLogging {
  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(
    config.integrations.asyncOperationThreadPoolSize,
    getClass.getSimpleName
  )

  var storedHakus: Seq[Haku] = Seq()
  val hakuRefreshTime = config.integrations.hakuRefreshTimeHours.hours
  val hakemusRefreshTime = config.integrations.hakemusRefreshTimeHours.hours

  val update = context.system.scheduler.schedule(1.second, hakuRefreshTime, self, Update)
  var retryUpdate: Option[Cancellable] = None

  import FutureList._

  def getHakuOption(q: GetHakuOption): Future[Option[Haku]] = Future {
    storedHakus.find(_.oid == q.oid)
  }

  def getHaku(q: GetHaku): Future[Haku] = Future {
    storedHakus.find(_.oid == q.oid) match {
      case None    => throw HakuNotFoundException(s"no stored haku found with oid ${q.oid}")
      case Some(h) => h
    }
  }

  log.info(
    s"starting haku actor $self (hakuRefreshTime: $hakuRefreshTime, hakemusRefreshTime: $hakemusRefreshTime)"
  )

  override def receive: Actor.Receive = {
    case Update =>
      log.info(s"updating all hakus for $self from ${sender()}")
      hakuAggregator.actor ! GetHautQuery

    case HakuRequest => sender ! AllHaut(storedHakus)

    case q: GetHaku => getHaku(q) pipeTo sender

    case q: GetHakuOption => getHakuOption(q) pipeTo sender

    case RestHakuResult(hakus: List[RestHaku]) => enrich(hakus).waitForAll pipeTo self

    case sq: Seq[_] =>
      storedHakus = sq.collect { case h: Haku => h }
      val activeHakus: Seq[Haku] = storedHakus.filter(_.isActive)
      val ytlHakus = activeHakus.filter(_.kkHaku)
      val activeYhteisHakus: Seq[Haku] = activeHakus.filter(_.hakutapaUri.startsWith("hakutapa_01"))
      val activeKKYhteisHakus = activeYhteisHakus.filter(_.kkHaku)
      val active2AsteYhteisHakus = activeYhteisHakus.filter(_.toisenAsteenHaku)
      val ytlHakuOidsWithNames =
        ytlHakus.map(haku => haku.oid -> haku.nimi.fi.getOrElse("haulla ei nimeä")).toMap
      val ytlHakuOids: Set[String] = ytlHakus.map(_.oid).toSet
      val active2AsteYhteisHakuOids: Set[String] = active2AsteYhteisHakus.map(_.oid).toSet
      val activeKKYhteisHakuOids: Set[String] = activeKKYhteisHakus.map(_.oid).toSet
      log.info(s"Asetetaan aktiiviset YTL-haut: ${ytlHakuOidsWithNames.toString()} ")
      ytlIntegration.setAktiivisetKKHaut(ytlHakuOids)
      koskiService.setAktiiviset2AsteYhteisHaut(active2AsteYhteisHakuOids)
      koskiService.setAktiivisetKKYhteisHaut(activeKKYhteisHakuOids)
      log.info(s"size of stored application system set: [${storedHakus.size}]")
      log.info(s"active application systems: [${activeHakus.size}]")
      log.info(s"active ytl application systems: [${ytlHakuOids.size}]")
      log.info(s"active 2.aste-yhteishakus: [${active2AsteYhteisHakuOids.size}]")
      log.info(s"active korkeakoulu-yhteishakus: [${activeKKYhteisHakuOids.size}]")

    case Failure(t: GetHautQueryFailedException) =>
      log.error(s"${t.getMessage}, retrying in a minute")
      retryUpdate.foreach(_.cancel())
      retryUpdate = Some(context.system.scheduler.scheduleOnce(1.minute, self, Update))

    case Failure(t) =>
      log.error(t, s"got failure from ${sender()}")

  }

  def enrich(hakus: List[RestHaku]): List[Future[Haku]] = {
    for (
      haku <- hakus
      if haku.oid.isDefined && haku.hakuaikas.nonEmpty
    ) yield getKierrosEnd(haku.oid.get).map(Haku(haku))
  }

  def getKierrosEnd(hakuOid: String): Future[ReadableInstant] = {
    import akka.pattern.ask
    import akka.util.Timeout

    import scala.concurrent.duration._
    implicit val to: Timeout = 2.minutes
    (parametrit.actor ? KierrosRequest(hakuOid)).mapTo[HakuParams].map(_.end).recover { case _ =>
      InFuture
    }
  }

  override def postStop(): Unit = {
    update.cancel()
    retryUpdate.foreach(_.cancel())
    super.postStop()
  }
}

object Update

object HakuRequest

case class AllHaut(haut: Seq[Haku])

case class HakuNotFoundException(m: String) extends Exception(m)

case class GetHaku(oid: String)

case class GetHakuOption(oid: String)

case class Kieliversiot(fi: Option[String], sv: Option[String], en: Option[String])

class FutureList[A](futures: Seq[Future[A]]) {
  def waitForAll(implicit ec: ExecutionContext): Future[Seq[A]] = Future.sequence(futures)
}

object FutureList {
  implicit def futures2FutureList[A](futures: Seq[Future[A]]): FutureList[A] = new FutureList(
    futures
  )
}
