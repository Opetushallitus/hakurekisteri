package fi.vm.sade.hakurekisteri.integration.virta

import java.util.concurrent.TimeUnit

import akka.actor.Status.Failure
import akka.actor.{ActorLogging, Actor, ActorRef}
import fi.vm.sade.hakurekisteri.integration.hakemus.Trigger
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus, yksilollistaminen}
import org.joda.time.{DateTime, LocalDateTime, LocalDate}

import scala.collection.mutable
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import fi.vm.sade.hakurekisteri.healthcheck.Status
import fi.vm.sade.hakurekisteri.healthcheck.Status.Status

import Virta.at

case class VirtaQuery(oppijanumero: String, hetu: Option[String])
case class VirtaQueuedQuery(q: VirtaQuery)
case class KomoNotFoundException(message: String) extends Exception(message)
case class VirtaData(opiskeluOikeudet: Seq[Opiskeluoikeus], suoritukset: Seq[Suoritus])
case class VirtaStatus(latestDequeueTime: Option[DateTime], queueLength: Long, status: Status)

object ConsumeOne
object ConsumeAll
object PrintStats
object VirtaHealth

class VirtaQueue(virtaActor: ActorRef, hakemusActor: ActorRef) extends Actor with ActorLogging {
  implicit val executionContext: ExecutionContext = context.dispatcher

  val virtaQueue: mutable.Queue[VirtaQuery] = new mutable.Queue()

  var latestDequeueTime: Option[DateTime] = None

  context.system.scheduler.schedule(at("04:00"), 24.hours, self, ConsumeAll)
  context.system.scheduler.schedule(5.minutes, 10.minutes, self, PrintStats)

  def consume() = {
    try {
      val q = virtaQueue.dequeue()
      virtaActor ! q
    } catch {
      case t: NoSuchElementException => log.error(s"trying to dequeue an empty queue: $t")
    }
  }

  def receive: Receive = {
    case VirtaQueuedQuery(q) if !virtaQueue.contains(q) => virtaQueue.enqueue(q)

    case ConsumeOne if virtaQueue.nonEmpty => consume()

    case ConsumeAll =>
      log.info("started to dequeue virta queries")
      while(virtaQueue.nonEmpty) {
        consume()
      }
      log.info(s"full dequeue done, virta queue length ${virtaQueue.length}")
      latestDequeueTime = Some(new DateTime())

    case PrintStats => log.info(s"virta queue length ${virtaQueue.length}")

    case VirtaHealth => sender ! VirtaStatus(latestDequeueTime, virtaQueue.length, Status.OK)
  }

  override def preStart(): Unit = {
    hakemusActor ! Trigger((oid, hetu) => if (!isYsiHetu(hetu)) self ! VirtaQueuedQuery(VirtaQuery(oid, Some(hetu))))
    super.preStart()
  }

  val ysiHetu = "\\d{6}[+-AB]9\\d{2}[0123456789ABCDEFHJKLMNPRSTUVWXY]"
  def isYsiHetu(hetu: String): Boolean = hetu.matches(ysiHetu)
}



class VirtaActor(virtaClient: VirtaClient, organisaatioActor: ActorRef, suoritusActor: ActorRef, opiskeluoikeusActor: ActorRef) extends Actor with ActorLogging {
  implicit val executionContext: ExecutionContext = context.dispatcher

  import akka.pattern.pipe

  def receive: Receive = {
    case VirtaQuery(o, h) => getOpiskelijanTiedot(o, h) pipeTo self
    case Some(r: VirtaResult) => save(r)
    case Failure(t: VirtaValidationError) => log.warning(s"virta validation error: $t")
    case Failure(t: Throwable) => log.error(t, "error occurred in virta query")
  }

  def getOpiskelijanTiedot(oppijanumero: String, hetu: Option[String]): Future[Option[VirtaResult]] =
    virtaClient.getOpiskelijanTiedot(oppijanumero = oppijanumero, hetu = hetu)

  def getKoulutusUri(koulutuskoodi: Option[String]): String =
    s"koulutus_${resolveKoulutusKoodiOrUnknown(koulutuskoodi)}"

  def resolveKoulutusKoodiOrUnknown(koulutuskoodi: Option[String]): String = {
    val tuntematon = "999999"
    koulutuskoodi.getOrElse(tuntematon)
  }

  def opiskeluoikeus(oppijanumero: String)(o: VirtaOpiskeluoikeus): Future[Opiskeluoikeus] =
    for (
      oppilaitosOid <- resolveOppilaitosOid(o.myontaja)
    ) yield Opiskeluoikeus(
          alkuPaiva = o.alkuPvm,
          loppuPaiva = o.loppuPvm,
          henkiloOid = oppijanumero,
          komo = getKoulutusUri(o.koulutuskoodit.headOption),
          myontaja = oppilaitosOid,
          source = Virta.CSC)

  def tutkinto(oppijanumero: String)(t: VirtaTutkinto): Future[Suoritus] =
    for (
      oppilaitosOid <- resolveOppilaitosOid(t.myontaja)
    ) yield VirallinenSuoritus(
          komo = getKoulutusUri(t.koulutuskoodi),
          myontaja = oppilaitosOid,
          valmistuminen = t.suoritusPvm,
          tila = tila(t.suoritusPvm),
          henkilo = oppijanumero,
          yksilollistaminen = yksilollistaminen.Ei,
          suoritusKieli = t.kieli,
          vahv = true,
          lahde = Virta.CSC)

  def tila(valmistuminen: LocalDate): String = valmistuminen match {
    case v: LocalDate if v.isBefore(new LocalDate()) => "VALMIS"
    case _ => "KESKEN"
  }

  def save(r: VirtaResult): Unit = {
    val opiskeluoikeudet: Future[Seq[Opiskeluoikeus]] = Future.sequence(r.opiskeluoikeudet.map(opiskeluoikeus(r.oppijanumero)))
    val suoritukset: Future[Seq[Suoritus]] = Future.sequence(r.tutkinnot.map(tutkinto(r.oppijanumero)))
    for {
      o <- opiskeluoikeudet
      s <- suoritukset
    } yield {
      o.foreach(opiskeluoikeusActor ! _)
      s.foreach(suoritusActor ! _)
    }
  }

  import akka.pattern.ask
  val tuntematon = "1.2.246.562.10.57118763579"

  def resolveOppilaitosOid(oppilaitosnumero: String): Future[String] = oppilaitosnumero match {
    case o if Seq("XX", "UK", "UM").contains(o) => Future.successful(tuntematon)
    case o =>
      (organisaatioActor ? o)(30.seconds).mapTo[Option[Organisaatio]] map {
          case Some(org) => org.oid
          case _ => log.error(s"oppilaitos not found with oppilaitosnumero $o"); throw OppilaitosNotFoundException(s"oppilaitos not found with oppilaitosnumero $o")
      }
  }
}

object Virta {
  val CSC = "1.2.246.562.10.2013112012294919827487"

  def at(time: String): FiniteDuration = {
    if (!time.matches("^[0-9]{2}:[0-9]{2}$")) throw new IllegalArgumentException("time format is not HH:mm")

    val t = time.split(":")
    val todayAtTime = new LocalDateTime().withTime(t(0).toInt, t(1).toInt, 0, 0)
    todayAtTime match {
      case d if d.isBefore(new LocalDateTime()) => new FiniteDuration(d.plusDays(1).toDate.getTime - Platform.currentTime, TimeUnit.MILLISECONDS)
      case d => new FiniteDuration(d.toDate.getTime - Platform.currentTime, TimeUnit.MILLISECONDS)
    }
  }
}