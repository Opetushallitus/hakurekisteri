package fi.vm.sade.hakurekisteri.integration.henkilo

import java.net.URLEncoder

import akka.actor.{ActorLogging, Actor}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.ExecutionContext

case class Kieli(kieliKoodi: String, kieliTyyppi: Option[String] = None)

case class Henkilo(id: Option[Int] = None,
                   oidHenkilo: Option[String],
                   hetu: Option[String],
                   henkiloTyyppi: String,
                   etunimet: Option[String],
                   kutsumanimi: Option[String],
                   sukunimi: Option[String],
                   kotikunta: Option[String],
                   aidinkieli: Option[Kieli])

case class SaveHenkilo(henkilo: Henkilo, tunniste: String)
case class SavedHenkilo(henkiloOid: String, tunniste: String)

case class HenkiloSearchResponse(totalCount: Int, results: Seq[Henkilo])
case class HenkiloSavedResponse(oidHenkilo: String)

case class FoundHenkilos(henkilot: Seq[Henkilo], tunniste: String)

class HenkiloActor(henkiloClient: VirkailijaRestClient) extends Actor with ActorLogging {
  val maxRetries = Config.httpClientMaxRetries

  implicit val ec: ExecutionContext = context.dispatcher

  val Hetu = "([0-9]{6}[-A][0-9]{3}[0123456789ABCDEFHJKLMNPRSTUVWXY])".r

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

    case SaveHenkilo(henkilo, tunniste) =>
      henkiloClient.postObject[Henkilo, HenkiloSavedResponse](s"/resources/s2s/tiedonsiirrot", 200, henkilo).
        map(saved => SavedHenkilo(saved.oidHenkilo, tunniste)) pipeTo sender
  }
}

case class HetuQuery(hetu: String)

case class HenkiloQuery(oppijanumero: Option[String] = None, hetu: Option[String] = None, tunniste: String)