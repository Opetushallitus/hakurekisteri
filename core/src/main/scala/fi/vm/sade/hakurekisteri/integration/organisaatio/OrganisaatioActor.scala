package fi.vm.sade.hakurekisteri.integration.organisaatio

import java.net.URLEncoder

import akka.actor.Status.Failure
import akka.actor.{ActorLogging, Actor}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.{FutureCache, PreconditionFailedException, VirkailijaRestClient}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class OrganisaatioResponse(numHits: Option[Int], organisaatiot: Seq[Organisaatio])
case class Oppilaitos(koodi: String)
case class OppilaitosResponse(koodi: String, oppilaitos: Organisaatio)
case class OppilaitosNotFoundException(koodi: String) extends Exception(s"Oppilaitosta ei löytynyt oppilaitoskoodilla $koodi.")
case class OrganisaatioFetchFailedException(t: Throwable) extends Exception(t)

class OrganisaatioActor(organisaatioClient: VirkailijaRestClient) extends Actor with ActorLogging {
  implicit val executionContext: ExecutionContext = context.dispatcher

  val maxRetries = Config.httpClientMaxRetries
  val timeToLive = Config.organisaatioCacheHours.hours
  private val cache: FutureCache[String, Organisaatio] = new FutureCache[String, Organisaatio](timeToLive.toMillis)
  private var oppilaitoskoodiIndex: Map[String, String] = Map()

  object Refresh
  val refresh = context.system.scheduler.schedule(timeToLive.minus(15.minutes), timeToLive.minus(15.minutes), self, Refresh)

  def fetchAll(): Unit = {
    val f = organisaatioClient.readObject[OrganisaatioResponse](s"/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true", 200).recover {
      case t: Throwable => OrganisaatioFetchFailedException(t)
    }
    f pipeTo self
  }

  def saveOrganisaatiot(s: Seq[Organisaatio]): Unit = {
    s.foreach(org => {
      cache + (org.oid, Future.successful(org))
      if (org.oppilaitosKoodi.isDefined) oppilaitoskoodiIndex = oppilaitoskoodiIndex + (org.oppilaitosKoodi.get -> org.oid)
      if (org.children.nonEmpty) saveOrganisaatiot(org.children)
    })
  }

  override def preStart(): Unit = {
    fetchAll()
  }

  override def postStop(): Unit = {
    refresh.cancel()
  }

  override def receive: Receive = {
    case Refresh => fetchAll()

    case s: OrganisaatioResponse =>
      saveOrganisaatiot(s.organisaatiot)
      log.info(s"all saved to cache: ${cache.size}")

    case Failure(t: OrganisaatioFetchFailedException) =>
      log.error("organisaatio refresh failed, retrying in 1 minute", t.t)
      context.system.scheduler.scheduleOnce(1.minute, self, Refresh)

    case Failure(t: Throwable) =>
      log.error("error in organisaatio actor", t)

    case oid: String => find(oid) pipeTo sender

    case Oppilaitos(koodi) =>
      findByOppilaitoskoodi(koodi).flatMap {
        case Some(oppilaitos) => Future.successful(OppilaitosResponse(koodi, oppilaitos))
        case None => Future.failed(OppilaitosNotFoundException(koodi))
      } pipeTo sender
  }

  def find(oid: String): Future[Option[Organisaatio]] = {
    if (cache.contains(oid)) cache.get(oid).map(Some(_))
    else findDirect(oid)
  }

  def findByOppilaitoskoodi(koodi: String): Future[Option[Organisaatio]] = {
    oppilaitoskoodiIndex.get(koodi) match {
      case Some(oid) => find(oid)
      case None => findDirect(koodi)
    }
  }

  def findDirect(tunniste: String): Future[Option[Organisaatio]] = {
    organisaatioClient.readObject[Organisaatio](s"/rest/organisaatio/${URLEncoder.encode(tunniste, "UTF-8")}", 200, maxRetries).map(Option(_)).recoverWith {
      case p: PreconditionFailedException if p.responseCode == 204 => log.warning(s"organisaatio not found with tunniste $tunniste"); Future.successful(None)
    }
  }
}
