package fi.vm.sade.hakurekisteri.integration.henkilo

import java.net.URLEncoder

import akka.actor.{ActorLogging, Actor}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.ExecutionContext

case class HenkiloResponse(oidHenkilo: String, hetu: Option[String])

class HenkiloActor(henkiloClient: VirkailijaRestClient) extends Actor with ActorLogging {
  val maxRetries = Config.httpClientMaxRetries

  implicit val ec: ExecutionContext = context.dispatcher

  val Hetu = "([0-9]{6}[-A][0-9]{3}[0123456789ABCDEFHJKLMNPRSTUVWXY])".r

  override def receive: Receive = {
    case henkiloOid: String =>
      log.debug(s"received henkiloOid: $henkiloOid")
      henkiloClient.readObject[HenkiloResponse](s"/resources/henkilo/${URLEncoder.encode(henkiloOid, "UTF-8")}", 200, maxRetries) pipeTo sender

    case HetuQuery(Hetu(hetu)) =>
      log.debug(s"received HetuQuery: ${hetu.substring(0, 6)}XXXX")
      henkiloClient.readObject[HenkiloResponse](s"/resources/s2s/byHetu/${URLEncoder.encode(hetu, "UTF-8")}", 200, maxRetries) pipeTo sender
  }
}

case class HetuQuery(hetu:String)