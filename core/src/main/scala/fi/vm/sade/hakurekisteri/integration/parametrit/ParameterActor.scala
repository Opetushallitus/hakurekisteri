package fi.vm.sade.hakurekisteri.integration.parametrit

import akka.actor.Status.Failure
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import akka.actor.{ActorRef, Actor}
import akka.pattern.pipe
import org.joda.time.DateTime

import scala.concurrent.Future

case class ParamsFailedException(haku: String, from: ActorRef, t: Throwable) extends Exception(s"call to parameter service failed for haku $haku", t)
case class NoParamFoundException(haku: String) extends Exception(s"no parameter found for haku $haku")

class ParameterActor(restClient: VirkailijaRestClient) extends Actor {
  implicit val ec = context.dispatcher
  val maxRetries = Config.httpClientMaxRetries
  var calling = false

  import scala.concurrent.duration._

  override def receive: Actor.Receive = {
    case r: KierrosRequest if calling =>
      val from = sender()
      context.system.scheduler.scheduleOnce(100.milliseconds, self, r)(ec, from)

    case r: KierrosRequest if !calling =>
      calling = true
      val from = sender()
      getParams(r.haku).map(HakuParams).pipeTo(self)(from)

    case h: HakuParams =>
      calling = false
      sender ! h

    case Failure(t: Throwable) =>
      calling = false
      Future.failed(t) pipeTo sender

  }

  def getParams(hakuOid: String): Future[DateTime] = {
    restClient.readObject[KierrosParams](s"/api/v1/rest/parametri/$hakuOid", 200).map {
      case KierrosParams(Some(KierrosEndParams(date))) => new DateTime(date)
      case _ => throw NoParamFoundException(hakuOid)
    }
  }
}

case class KierrosRequest(haku: String)
case class KierrosEndParams(date: Long)
case class KierrosParams(PH_HKP: Option[KierrosEndParams])
case class HakuParams(end: DateTime)
