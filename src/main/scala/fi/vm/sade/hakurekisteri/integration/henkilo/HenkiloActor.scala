package fi.vm.sade.hakurekisteri.integration.henkilo

import java.net.URLEncoder

import akka.actor.Actor
import akka.pattern.pipe
import com.stackmob.newman.response.HttpResponseCode
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.ExecutionContext

case class HenkiloResponse(oidHenkilo: String, hetu: Option[String])


class HenkiloActor(henkiloClient: VirkailijaRestClient)(implicit val ec: ExecutionContext) extends Actor {
  override def receive: Receive = {
    case henkiloOid: String => henkiloClient.readObject[HenkiloResponse](s"/resources/henkilo/${URLEncoder.encode(henkiloOid, "UTF-8")}", HttpResponseCode.Ok) pipeTo sender
  }
}
