package fi.vm.sade.hakurekisteri.integration.parametrit

import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import akka.actor.Actor
import akka.pattern.pipe
import org.joda.time.DateTime
import com.stackmob.newman.response.HttpResponseCode


class ParameterActor(restClient:VirkailijaRestClient) extends Actor {

  implicit val ec = context.dispatcher


  override def receive: Actor.Receive = {

    case KierrosRequest(oid) => getParams(oid).map(HakuParams) pipeTo sender
  }


  def getParams(hakuOid: String) =  {
    restClient.readObject[KierrosParams](s"/api/v1/rest/parametri/$hakuOid", HttpResponseCode.Ok).
      collect { case KierrosParams(KierrosEndParams(date)) => new DateTime(date)}

  }


}


case class KierrosRequest(haku: String)



case class KierrosEndParams(date: Long)
case class KierrosParams(PH_HKP:KierrosEndParams)

case class HakuParams(end:DateTime)

