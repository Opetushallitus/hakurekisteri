package fi.vm.sade.hakurekisteri.integration.henkilo

import java.net.URLEncoder

import akka.actor.{Actor, ActorLogging}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.mocks.HenkiloMock
import fi.vm.sade.hakurekisteri.integration.organisaatio.OrganisaatioResponse
import org.json4s.{DefaultFormats, _}
import org.json4s.jackson.JsonMethods._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

abstract class HenkiloActor(config: Config) extends Actor with ActorLogging {
  implicit val ec: ExecutionContext = context.dispatcher
  val maxRetries = config.integrations.henkiloConfig.httpClientMaxRetries
  var savingHenkilo = false

  def createOrganisaatioHenkilo(oidHenkilo: String, organisaatioHenkilo: OrganisaatioHenkilo)

  def findExistingOrganisaatiohenkilo(oidHenkilo: String, organisaatioHenkilo: OrganisaatioHenkilo)

  def receive: Receive
}

class HttpHenkiloActor(virkailijaClient: VirkailijaRestClient, config: Config) extends HenkiloActor(config) {

  import fi.vm.sade.hakurekisteri.integration.henkilo.HetuUtil.Hetu

  override def createOrganisaatioHenkilo(oidHenkilo: String, organisaatioHenkilo: OrganisaatioHenkilo) = {
    virkailijaClient.postObject[OrganisaatioHenkilo, OrganisaatioHenkilo](s"/resources/henkilo/$oidHenkilo/organisaatiohenkilo", 200, organisaatioHenkilo)
  }

  override def findExistingOrganisaatiohenkilo(oidHenkilo: String, organisaatioHenkilo: OrganisaatioHenkilo) = {
    virkailijaClient.readObject[Seq[OrganisaatioHenkilo]](s"/resources/henkilo/$oidHenkilo/organisaatiohenkilo", 200)
  }

  override def receive: Receive = {
    case henkiloOid: String =>
      log.debug(s"received henkiloOid: $henkiloOid")
      virkailijaClient.readObject[Henkilo](s"/resources/henkilo/${URLEncoder.encode(henkiloOid, "UTF-8")}", 200, maxRetries) pipeTo sender

    case HetuQuery(Hetu(hetu)) =>
      log.debug(s"received HetuQuery: ${hetu.substring(0, 6)}XXXX")
      virkailijaClient.readObject[Henkilo](s"/resources/s2s/byHetu/${URLEncoder.encode(hetu, "UTF-8")}", 200, maxRetries) pipeTo sender

    case q: HenkiloQuery =>
      log.debug(s"received HenkiloQuery: $q")
      if (q.oppijanumero.isEmpty && q.hetu.isEmpty) {
        sender ! FoundHenkilos(Seq(), q.tunniste)
      } else {
        virkailijaClient.readObject[HenkiloSearchResponse](s"/resources/henkilo?q=${URLEncoder.encode(q.oppijanumero.getOrElse(q.hetu.get), "UTF-8")}&index=0&count=2&no=true&s=true", 200, maxRetries).
          map(r => FoundHenkilos(r.results, q.tunniste)) pipeTo sender
      }

    case s: SavedHenkilo =>
      savingHenkilo = false
      sender ! s

    case t: HenkiloSaveFailed =>
      savingHenkilo = false
      Future.failed(t) pipeTo sender

    case SaveHenkilo(henkilo, tunniste) if !savingHenkilo =>
      savingHenkilo = true
      virkailijaClient.postObject[CreateHenkilo, String](s"/resources/s2s/tiedonsiirrot", 200, henkilo).map(saved => SavedHenkilo(saved, tunniste)).recoverWith {
        case t: Throwable => Future.successful(HenkiloSaveFailed(tunniste, t))
      }.pipeTo(self)(sender())

    case s: SaveHenkilo if savingHenkilo =>
      context.system.scheduler.scheduleOnce(50.milliseconds, self, s)(ec, sender())

  }
}

class MockHenkiloActor(config: Config) extends HenkiloActor(config) {
  implicit val formats = DefaultFormats

  import fi.vm.sade.hakurekisteri.integration.henkilo.HetuUtil.Hetu

  override def receive: Receive = {
    case henkiloOid: String =>
      log.debug(s"received henkiloOid: $henkiloOid")
      val json = parse(HenkiloMock.getHenkiloByOid("1.2.246.562.24.71944845619"))
      sender ! json.extract[Henkilo]

    case HetuQuery(Hetu(hetu)) =>
      val json = parse(HenkiloMock.getHenkiloByOid("1.2.246.562.24.71944845619"))
      sender ! json.extract[Henkilo]

    case q: HenkiloQuery =>
      throw new UnsupportedOperationException("Not implemented")

    case s: SavedHenkilo =>
      savingHenkilo = false
      sender ! s

    case t: HenkiloSaveFailed =>
      savingHenkilo = false
      Future.failed(t) pipeTo sender

    case SaveHenkilo(henkilo, tunniste) if !savingHenkilo =>
      throw new UnsupportedOperationException("Not implemented")

    case s: SaveHenkilo if savingHenkilo =>
      throw new UnsupportedOperationException("Not implemented")
  }

  override def createOrganisaatioHenkilo(oidHenkilo: String, organisaatioHenkilo: OrganisaatioHenkilo) = {
    throw new UnsupportedOperationException("Not implemented")
  }

  override def findExistingOrganisaatiohenkilo(oidHenkilo: String, organisaatioHenkilo: OrganisaatioHenkilo) = {
    val json = parse(HenkiloMock.getHenkiloByOid("1.2.246.562.24.71944845619"))
    json.extract[OrganisaatioResponse]
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

case class Kieli(kieliKoodi: String, kieliTyyppi: Option[String] = None)

case class OrganisaatioHenkilo(organisaatioOid: String,
                               organisaatioHenkiloTyyppi: Option[String] = None,
                               voimassaAlkuPvm: Option[String] = None,
                               voimassaLoppuPvm: Option[String] = None,
                               tehtavanimike: Option[String] = None)

case class CreateHenkilo(etunimet: String,
                         kutsumanimi: String,
                         sukunimi: String,
                         hetu: Option[String] = None,
                         oidHenkilo: Option[String] = None,
                         externalId: Option[String] = None,
                         syntymaaika: Option[String] = None,
                         sukupuoli: Option[String] = None,
                         aidinkieli: Option[Kieli] = None,
                         henkiloTyyppi: String,
                         kasittelijaOid: String,
                         organisaatioHenkilo: Seq[OrganisaatioHenkilo] = Seq())

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

case class HenkiloSaveFailed(tunniste: String, t: Throwable) extends Exception(s"henkilo save failed for tunniste $tunniste", t)

case class HenkiloSearchResponse(totalCount: Int, results: Seq[Henkilo])

case class FoundHenkilos(henkilot: Seq[Henkilo], tunniste: String)