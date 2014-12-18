package fi.vm.sade.hakurekisteri.integration.parametrit

import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import akka.actor.Actor
import akka.pattern.pipe
import org.joda.time.DateTime

import scala.concurrent.Future
import scala.util.{Failure, Success}

class ParameterActor(restClient: VirkailijaRestClient) extends Actor {
  implicit val ec = context.dispatcher
  val maxRetries = Config.httpClientMaxRetries
  var calling = false

  import scala.concurrent.duration._

  override def receive: Actor.Receive = {
    case KierrosRequest(oid) if calling =>
      context.system.scheduler.scheduleOnce(100.milliseconds, self, KierrosRequest(oid))(ec, sender())

    case KierrosRequest(oid) if !calling =>
      calling = true
      val f = getParams(oid).map(HakuParams)
      f.onComplete(t => calling = false)
      f pipeTo sender
  }

  def getParams(hakuOid: String): Future[DateTime] =  {
    restClient.readObject[KierrosParams](s"/api/v1/rest/parametri/$hakuOid", 200, maxRetries).
      collect { case KierrosParams(Some(KierrosEndParams(date))) => new DateTime(date) }
  }
}

case class KierrosRequest(haku: String)
case class KierrosEndParams(date: Long)
case class KierrosParams(PH_HKP: Option[KierrosEndParams])
case class HakuParams(end: DateTime)
