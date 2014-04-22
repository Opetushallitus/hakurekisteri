package fi.vm.sade.hakurekisteri.hakija

import com.stackmob.newman._
import com.stackmob.newman.dsl._
import scala.concurrent._
import scala.concurrent.duration._
import java.net.URL
import com.stackmob.newman.response.{HttpResponseCode, HttpResponse}
import org.slf4j.LoggerFactory
import akka.actor.{Cancellable, Actor}
import akka.actor.Actor.Receive
import scala.compat.Platform


trait Organisaatiopalvelu {

  def get(str: String): Future[Option[Organisaatio]]

}

case class Organisaatio(oid: String, nimi: Map[String, String], toimipistekoodi: Option[String], oppilaitosKoodi: Option[String], parentOid: Option[String])

import akka.pattern.pipe

class OrganisaatioActor(palvelu: Organisaatiopalvelu) extends Actor {


  class Swipe

  private object swipe  extends Swipe

  case class Remove(oid:String)
  implicit val executionContext: ExecutionContext = context.dispatcher

  import scala.collection.mutable.Map
  private val cache:Map[String,(Long, Future[Option[Organisaatio]])] = Map()


  var cancellable: Option[Cancellable] = None

  override def preStart(): Unit = {
    cancellable = Some(context.system.scheduler.schedule(0 milliseconds,
      10 minutes,
      self,
      swipe)(context.dispatcher, self))
  }


  override def postStop(): Unit = {
    cancellable.foreach(_.cancel())

  }



  val timeToLive = 30 minutes

  override def receive: Receive = {
    case oid:String => find(oid)._2 pipeTo sender
    case Remove(oid) => cache.remove(oid)
    case swipe:Swipe => Future(cache.toSeq.filter(t => t._2._1 < Platform.currentTime).map(_._1).foreach(self ! Remove(_)))
  }


  def find(oid: String): (Long, Future[Option[Organisaatio]]) = {
    cache getOrElseUpdate(oid, (Platform.currentTime + timeToLive.toMillis, palvelu.get(oid)))
  }
}

class RestOrganisaatiopalvelu(serviceUrl: String = "https://itest-virkailija.oph.ware.fi/organisaatio-service")(implicit val ec: ExecutionContext) extends Organisaatiopalvelu {
  val logger = LoggerFactory.getLogger(getClass)

  implicit val httpClient = new ApacheHttpClient()()





  override def get(str: String): Future[Option[Organisaatio]] = {
    val url = new URL(serviceUrl + "/rest/organisaatio/" + str)
    logger.info("calling organisaatio-service [{}]", url)
    GET(url).apply.map(response => {
      if (response.code == HttpResponseCode.Ok) {
        val organisaatio = response.bodyAsCaseClass[Organisaatio].toOption
        logger.info("response received from {}", url)
        logger.debug("got response: [{}]", organisaatio)
        organisaatio
      } else {
        logger.error("call to organisaatio-service [{}] failed: {}", url, response.code)
        throw new RuntimeException("virhe kutsuttaessa organisaatiopalvelua: %s".format(response.code))
      }
    })
  }

}
