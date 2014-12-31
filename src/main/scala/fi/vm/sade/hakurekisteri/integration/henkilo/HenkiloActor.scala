package fi.vm.sade.hakurekisteri.integration.henkilo

import java.net.URLEncoder
import java.util.concurrent.ExecutionException

import akka.actor.{ActorLogging, Actor}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.{PreconditionFailedException, VirkailijaRestClient}

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._

case class Kieli(kieliKoodi: String, kieliTyyppi: Option[String] = None)

case class OrganisaatioHenkilo(organisaatioOid: String)

case class CreateHenkilo(etunimet: String,
                         kutsumanimi: String,
                         sukunimi: String,
                         hetu: Option[String],
                         syntymaaika: Option[String],
                         sukupuoli: Option[String],
                         asiointiKieli: Kieli,
                         henkiloTyyppi: String,
                         kasittelijaOid: String,
                         organisaatioHenkilo: Seq[OrganisaatioHenkilo])

case class Henkilo(oidHenkilo: String,
                   hetu: Option[String],
                   henkiloTyyppi: String,
                   etunimet: Option[String],
                   kutsumanimi: Option[String],
                   sukunimi: Option[String],
                   kotikunta: Option[String],
                   aidinkieli: Option[Kieli])

case class SaveHenkilo(henkilo: CreateHenkilo, tunniste: String)
case class SavedHenkilo(henkiloOid: String, tunniste: String)
case class HenkiloSaveFailed(tunniste: String, t: Throwable)
case class CheckHenkilo(henkiloOid: String)

case class HenkiloSearchResponse(totalCount: Int, results: Seq[Henkilo])

case class FoundHenkilos(henkilot: Seq[Henkilo], tunniste: String)

case class HenkiloNotFoundException(oid: String) extends Exception(s"henkilo not found with oid $oid")

class HenkiloActor(henkiloClient: VirkailijaRestClient) extends Actor with ActorLogging {
  implicit val ec: ExecutionContext = context.dispatcher
  val maxRetries = Config.httpClientMaxRetries
  var savingHenkilo = false

  import HetuUtil.Hetu

  override def receive: Receive = {
    case henkiloOid: String =>
      log.debug(s"received henkiloOid: $henkiloOid")
      henkiloClient.readObject[Henkilo](s"/resources/henkilo/${URLEncoder.encode(henkiloOid, "UTF-8")}", 200, maxRetries) pipeTo sender

    case HetuQuery(Hetu(hetu)) =>
      log.debug(s"received HetuQuery: ${hetu.substring(0, 6)}XXXX")
      henkiloClient.readObject[Henkilo](s"/resources/s2s/byHetu/${URLEncoder.encode(hetu, "UTF-8")}", 200, maxRetries) pipeTo sender

    case q: HenkiloQuery =>
      log.debug(s"received HenkiloQuery: $q")
      if (q.oppijanumero.isEmpty && q.hetu.isEmpty) {
        sender ! FoundHenkilos(Seq(), q.tunniste)
      } else {
        henkiloClient.readObject[HenkiloSearchResponse](s"/resources/henkilo?q=${URLEncoder.encode(q.oppijanumero.getOrElse(q.hetu.get), "UTF-8")}&index=0&count=2&no=true&s=true", 200, maxRetries).
          map(r => FoundHenkilos(r.results, q.tunniste)) pipeTo sender
      }

    case s: SavedHenkilo =>
      savingHenkilo = false
      sender ! s

    case s: HenkiloSaveFailed =>
      savingHenkilo = false
      sender ! s

    case SaveHenkilo(henkilo: CreateHenkilo, tunniste) if !savingHenkilo =>
      savingHenkilo = true
      henkiloClient.postObject[CreateHenkilo, String](s"/resources/s2s/tiedonsiirrot", 200, henkilo).map(saved => SavedHenkilo(saved, tunniste)).recoverWith {
        case t: Throwable => Future.successful(HenkiloSaveFailed(tunniste, t))
      }.pipeTo(self)(sender())

    case s: SaveHenkilo if savingHenkilo =>
      context.system.scheduler.scheduleOnce(500.milliseconds, self, s)(ec, sender())

    case CheckHenkilo(henkiloOid) =>
      def notFound(t: Throwable) = t match {
        case PreconditionFailedException(_, 500) => true
        case _ => false
      }
      henkiloClient.readObject[Henkilo](s"/resources/s2s/${URLEncoder.encode(henkiloOid, "UTF-8")}", 200).map(h => SavedHenkilo(h.oidHenkilo, henkiloOid)).recoverWith {
        case t: ExecutionException if t.getCause != null && notFound(t.getCause) => Future.failed(HenkiloNotFoundException(henkiloOid))
      } pipeTo sender
  }
}

object HetuUtil {
  val Hetu = "([0-9]{6}[-A][0-9]{3}[0123456789ABCDEFHJKLMNPRSTUVWXY])".r

  def toSyntymaAika(hetu: String): Option[String] = hetu match {
    case Hetu(h) =>
      val vuosisata = h.charAt(6) match {
        case '-' => "19"
        case 'A' => "20"
      }
      Some(s"$vuosisata${h.substring(4, 6)}-${h.substring(2, 4)}-${h.substring(0, 2)}")
   case _ => None
  }
}

case class HetuQuery(hetu: String)

case class HenkiloQuery(oppijanumero: Option[String] = None, hetu: Option[String] = None, tunniste: String)