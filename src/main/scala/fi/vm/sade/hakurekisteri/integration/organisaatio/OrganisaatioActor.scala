package fi.vm.sade.hakurekisteri.integration.organisaatio

import java.net.URLEncoder

import akka.actor.{ActorLogging, Actor, Cancellable}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.{FutureCache, PreconditionFailedException, VirkailijaRestClient}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.util.Try

case class OrganisaatioResponse(numHits: Option[Int], organisaatiot: Seq[Organisaatio])
case class Oppilaitos(koodi: String)
case class OppilaitosResponse(koodi: String, oppilaitos: Organisaatio)
case class OppilaitosNotFoundException(koodi: String) extends Exception(s"Oppilaitosta ei löytynyt oppilaitoskoodilla $koodi.")

class OrganisaatioActor(organisaatioClient: VirkailijaRestClient) extends Actor with ActorLogging {
  implicit val executionContext: ExecutionContext = context.dispatcher

  val maxRetries = Config.httpClientMaxRetries
  val timeToLive = Config.organisaatioCacheHours.hours
  private val cache: FutureCache[String, Organisaatio] = new FutureCache[String, Organisaatio](timeToLive.toMillis)
  private var oppilaitoskoodiIndex: Map[String, String] = Map()

  object Refresh
  val refresh = context.system.scheduler.schedule(timeToLive.minus(15.minutes), timeToLive.minus(15.minutes), self, Refresh)

  def fetchAll(): Unit = {
    organisaatioClient.readObject[OrganisaatioResponse](s"/rest/organisaatio/hae?OrganisaatioSearchCriteria=${URLEncoder.encode("{}", "UTF-8")}", 200, maxRetries).onSuccess {
      case s: OrganisaatioResponse =>
        saveOrganisaatiot(s.organisaatiot)
        log.info(s"all saved to cache: ${cache.size}")
    }
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
    if (!refresh.isCancelled) refresh.cancel()
  }

  override def receive: Receive = {
    case Refresh => fetchAll()

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
