package fi.vm.sade.hakurekisteri.integration.organisaatio

import java.net.URLEncoder

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.mocks.OrganisaatioMock
import fi.vm.sade.hakurekisteri.integration.{FutureCache, PreconditionFailedException, VirkailijaRestClient}
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success


object RefreshOrganisaatioCache


class HttpOrganisaatioActor(organisaatioClient: VirkailijaRestClient,
                            config: Config,
                            initDuringStartup: Boolean = true,
                            ttl: Option[FiniteDuration] = None) extends Actor with ActorLogging {
  implicit val ec: ExecutionContext = context.system.dispatcher
  val maxRetries: Int = config.integrations.organisaatioConfig.httpClientMaxRetries
  val timeToLive: FiniteDuration = ttl.getOrElse(config.integrations.organisaatioCacheHours.hours)
  val reloadInterval = timeToLive / 2
  val refresh = context.system.scheduler.schedule(reloadInterval, reloadInterval, self, RefreshOrganisaatioCache)

  log.info(s"timeToLive: $timeToLive, reloadInterval: $reloadInterval")

  private val cache: FutureCache[String, Organisaatio] = new FutureCache[String, Organisaatio](timeToLive.toMillis)
  private var oppilaitoskoodiIndex: Map[String, String] = Map()
  private var directRequests: Map[String, Future[Option[Organisaatio]]] = Map()

  private def saveOrganisaatiot(s: Seq[Organisaatio]): Unit = {
    s.foreach(org => {
      cache + (org.oid, Future.successful(org))
      if (org.oppilaitosKoodi.isDefined) oppilaitoskoodiIndex = oppilaitoskoodiIndex + (org.oppilaitosKoodi.get -> org.oid)
      if (org.children.nonEmpty) saveOrganisaatiot(org.children)
    })
  }

  private def find(oid: String): Future[Option[Organisaatio]] = {
    if (cache.contains(oid)) cache.get(oid).map(Some(_))
    else waitForDirectResult(oid)
  }

  private def findByOppilaitoskoodi(koodi: String): Future[Option[Organisaatio]] = {
    oppilaitoskoodiIndex.get(koodi) match {
      case Some(oid) => find(oid)
      case None => waitForDirectResult(koodi)
    }
  }

  private def fetchAll(actor: ActorRef = ActorRef.noSender): Unit = {
    val f = organisaatioClient.readObject[OrganisaatioResponse](s"/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true", 200).recoverWith {
      case t: Throwable => Future.failed(OrganisaatioFetchFailedException(t))
    }
    f.map(r => CacheOrganisaatiot(r.organisaatiot)).pipeTo(self)(actor)
  }

  private def waitForDirectResult(tunniste: String): Future[Option[Organisaatio]] = {
    if (directRequests.contains(tunniste))
      directRequests(tunniste)
    else {
      val result = findDirect(tunniste)
      directRequests = directRequests + (tunniste -> result)
      result.onComplete {
        case Success(res) =>
          if (res.isDefined) {
            self ! CacheOrganisaatiot(Seq(res.get))
          }
          self ! DirectDone(tunniste)

        case scala.util.Failure(t) =>
          self ! DirectDone(tunniste)

      }
      result
    }
  }

  private def findDirect(tunniste: String): Future[Option[Organisaatio]] = {
    val org = organisaatioClient.readObject[Organisaatio](s"/rest/organisaatio/${URLEncoder.encode(tunniste, "UTF-8")}", 200, maxRetries).map(Option(_)).recoverWith {
      case p: PreconditionFailedException if p.responseCode == 204 => log.warning(s"organisaatio not found with tunniste $tunniste"); Future.successful(None)
    }
    org
  }

  override def preStart(): Unit = {
    if (initDuringStartup) {
      fetchAll()
    }
  }

  override def postStop(): Unit = {
    refresh.cancel()
  }
  
  case class CacheOrganisaatiot(o: Seq[Organisaatio])
  case class DirectDone(tunniste: String)

  override def receive: Receive = {
    case RefreshOrganisaatioCache => fetchAll(sender())

    case CacheOrganisaatiot(o) =>
      saveOrganisaatiot(o)
      log.info(s"${o.size} saved to cache: ${cache.size}, oppilaitoskoodiIndex: ${oppilaitoskoodiIndex.size}")
      sender ! true

    case Failure(t: OrganisaatioFetchFailedException) =>
      log.error("organisaatio refresh failed, retrying in 1 minute", t.t)
      context.system.scheduler.scheduleOnce(1.minute, self, RefreshOrganisaatioCache)

    case Failure(t: Throwable) =>
      log.error("error in organisaatio actor", t)

    case oid: String =>
      find(oid) pipeTo sender

    case Oppilaitos(koodi) =>
      findByOppilaitoskoodi(koodi).flatMap {
        case Some(oppilaitos) => Future.successful(OppilaitosResponse(koodi, oppilaitos))
        case None => Future.failed(OppilaitosNotFoundException(koodi))
      } pipeTo sender

    case DirectDone(tunniste) =>
      directRequests = directRequests - tunniste

  }
}

class MockOrganisaatioActor(config: Config) extends Actor {
  implicit val formats = DefaultFormats
  implicit val ec: ExecutionContext = context.system.dispatcher

  def find(tunniste: String): Future[Option[Organisaatio]] = {
    Future.successful(Some(parse(OrganisaatioMock.findByOid(tunniste)).extract[Organisaatio]))
  }

  override def receive: Actor.Receive = {
    case oid: String => find(oid) pipeTo sender
    case Oppilaitos(koodi) => find(koodi) pipeTo sender
  }
}

case class OrganisaatioResponse(numHits: Option[Int], organisaatiot: Seq[Organisaatio])

case class Oppilaitos(koodi: String)

case class OppilaitosResponse(koodi: String, oppilaitos: Organisaatio)

case class OppilaitosNotFoundException(koodi: String) extends Exception(s"Oppilaitosta ei löytynyt oppilaitoskoodilla $koodi.")

case class OrganisaatioFetchFailedException(t: Throwable) extends Exception(t)
