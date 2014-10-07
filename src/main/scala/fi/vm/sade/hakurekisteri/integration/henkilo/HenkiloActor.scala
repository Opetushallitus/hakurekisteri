package fi.vm.sade.hakurekisteri.integration.henkilo

import java.net.URLEncoder

import akka.actor.Actor
import akka.event.Logging
import akka.pattern.pipe
import com.stackmob.newman.response.HttpResponseCode
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.ExecutionContext

case class HenkiloResponse(oidHenkilo: String, hetu: Option[String])

class HenkiloActor(henkiloClient: VirkailijaRestClient)(implicit val ec: ExecutionContext) extends Actor {
  val logger = Logging(context.system, this)
  val maxRetries = 5

  val Hetu = "([0-9]{6}[-A][0-9]{3}[0123456789ABCDEFHJKLMNPRSTUVWXY])".r

  override def receive: Receive = {
    case henkiloOid: String =>
      logger.debug(s"received henkiloOid: $henkiloOid")
      henkiloClient.readObject[HenkiloResponse](s"/resources/henkilo/${URLEncoder.encode(henkiloOid, "UTF-8")}", maxRetries, HttpResponseCode.Ok) pipeTo sender

    case HetuQuery(Hetu(hetu)) =>
      logger.debug(s"received HetuQuery: ${hetu.substring(0, 6)}XXXX")
      henkiloClient.readObject[HenkiloResponse](s"/resources/s2s/byHetu/${URLEncoder.encode(hetu, "UTF-8")}", maxRetries, HttpResponseCode.Ok) pipeTo sender
  }
}

case class HetuQuery(hetu:String)