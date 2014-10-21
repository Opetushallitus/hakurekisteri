package fi.vm.sade.hakurekisteri.integration.sijoittelu

import akka.actor.{ActorLogging, Actor}
import akka.pattern.pipe
import com.stackmob.newman.response.HttpResponseCode
import fi.vm.sade.hakurekisteri.integration.{FutureCache, VirkailijaRestClient}
import scala.concurrent.duration._
import scala.concurrent.Future

class SijoitteluActor(sijoitteluClient: VirkailijaRestClient) extends Actor with ActorLogging {

  implicit val ec = context.dispatcher

  private val cache = new FutureCache[String, SijoitteluTulos](4.hours.toMillis)
  private val refetch: FiniteDuration = 2.hours
  private val retry: FiniteDuration = 60.seconds

  override def receive: Receive = {
    case SijoitteluQuery(haku) =>
      getSijoittelu(haku) pipeTo sender

    case Update(haku) if !cache.inUse(haku) =>
      cache - haku

    case Update(haku) =>
      val result = sijoitteluTulos(haku)
      result.onFailure {
        case t =>
          log.error(t, s"failed to fetch sijoittelu for haku $haku")
          rescheduleHaku(haku, retry)
      }
      result map (Sijoittelu(haku, _)) pipeTo self

    case Sijoittelu(haku, st) =>
      cache + (haku, Future.successful(st))
      rescheduleHaku(haku)
  }

  def getSijoittelu(haku: String): Future[SijoitteluTulos] = {
    if (cache.contains(haku)) cache.get(haku)
    else updateCacheFor(haku)
  }

  def updateCacheFor(haku: String): Future[SijoitteluTulos] = {
    val tulos: Future[SijoitteluTulos] = sijoitteluTulos(haku)
    cache + (haku, tulos)
    tulos.onFailure {
      case t =>
        log.error(t, s"failed to fetch sijoittelu for haku $haku")
        rescheduleHaku(haku, retry)
    }
    tulos.onSuccess {
      case _ => rescheduleHaku(haku)
    }
    tulos
  }

  def sijoitteluTulos(haku: String): Future[SijoitteluTulos] = {
    sijoitteluClient.readObject[SijoitteluPagination](s"/resources/sijoittelu/$haku/sijoitteluajo/latest/hakemukset", HttpResponseCode.Ok).
      map(_.results).
      map(SijoitteluTulos(_))
  }

  def rescheduleHaku(haku: String, time: FiniteDuration = refetch) {
    log.debug(s"rescheduling haku $haku in $time")
    context.system.scheduler.scheduleOnce(time, self, Update(haku))
  }

  case class Update(haku: String)
  case class Sijoittelu(haku: String, st: SijoitteluTulos)
}
