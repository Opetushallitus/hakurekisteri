package fi.vm.sade.hakurekisteri.integration.organisaatio

import java.net.URLEncoder
import java.util.concurrent.ExecutionException

import akka.actor.Status.Failure
import akka.actor.{Cancellable, Actor, ActorLogging, ActorRef}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.mocks.OrganisaatioMock
import fi.vm.sade.hakurekisteri.integration.{FutureCache, PreconditionFailedException, VirkailijaRestClient}
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}


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
  var retryRefresh: Option[Cancellable] = None

  log.info(s"timeToLive: $timeToLive, reloadInterval: $reloadInterval")

  private val cache: FutureCache[String, Organisaatio] = new FutureCache[String, Organisaatio](timeToLive.toMillis)
  private var orgHierarchyCache: Map[String, Set[String]] = Map()
  private var oppilaitoskoodiIndex: Map[String, String] = Map()

  private def collectChildrenOids(parent: Organisaatio): List[(String, Set[String])] = {
    val l: List[(String, Set[String])] = parent.children.flatMap(x => {
      if (x.children.isEmpty) List((parent.oid, Set(parent.oid)))
      else collectChildrenOids(x)
    }).toList
    (parent.oid, l.flatMap(x => x._2).toSet) :: l
  }

  private def saveOrganisaatiot(s: Seq[Organisaatio]): Unit = {
    s.foreach(org => {
      cache + (org.oid, Future.successful(org))

      if (org.oppilaitosKoodi.isDefined)
        oppilaitoskoodiIndex = oppilaitoskoodiIndex + (org.oppilaitosKoodi.get -> org.oid)

      if (org.children.nonEmpty)
        saveOrganisaatiot(org.children)
    })
  }

  private def findAndCache(tunniste: String): Future[Option[Organisaatio]] = {
    def notFound(t: Throwable) = t match {
      case PreconditionFailedException(_, 204) => true
      case _ => false
    }

    val tulos = organisaatioClient.readObject[Organisaatio]("organisaatio-service.organisaatio", tunniste)(200, maxRetries).map(Option(_)).recoverWith {
      case p: ExecutionException if p.getCause != null && notFound(p.getCause) =>
        log.warning(s"organisaatio not found with tunniste $tunniste")
        Future.successful(None)
    }

    tulos.onSuccess {
      case Some(o) => self ! CacheOrganisaatiot(Seq(o))
    }

    tulos
  }

  private def findByOid(oid: String): Future[Option[Organisaatio]] = {
    if (cache.contains(oid))
      cache.get(oid).map(Some(_))
    else
      findAndCache(oid)
  }

  private def findByOppilaitoskoodi(koodi: String): Future[Option[Organisaatio]] = {
    oppilaitoskoodiIndex.get(koodi) match {
      case Some(oid) => findByOid(oid)
      case None => findAndCache(koodi)
    }
  }

  private def fetchAll(actor: ActorRef = ActorRef.noSender): Unit = {
    val all = organisaatioClient.readObject[OrganisaatioResponse]("organisaatio-service.hierarkia.hae")(200).recoverWith {
      case t: Throwable => Future.failed(OrganisaatioFetchFailedException(t))
    }

    all.map(r => CacheOrganisaatiot(r.organisaatiot)).pipeTo(self)(actor)
  }

  override def preStart(): Unit = {
    if (initDuringStartup) {
      fetchAll()
    }
  }

  override def postStop(): Unit = {
    refresh.cancel()
    retryRefresh.foreach(_.cancel())
  }
  
  case class CacheOrganisaatiot(o: Seq[Organisaatio])
  case class OrganizationHierarchy(oid: String)

  override def receive: Receive = {
    case RefreshOrganisaatioCache => fetchAll(sender())

    case CacheOrganisaatiot(o) =>
      saveOrganisaatiot(o)
      o.flatMap(org => {
        collectChildrenOids(org)
      }).foreach(parentToChildren => orgHierarchyCache + (parentToChildren._1 -> parentToChildren._2))
      log.info(s"${o.size} saved to cache: ${cache.size}, oppilaitoskoodiIndex: ${oppilaitoskoodiIndex.size}")
      if (sender() != ActorRef.noSender)
        sender ! true

    case Failure(t: OrganisaatioFetchFailedException) =>
      log.error(t.t, "organisaatio refresh failed, retrying in 1 minute")
      retryRefresh.foreach(_.cancel())
      retryRefresh = Some(context.system.scheduler.scheduleOnce(1.minute, self, RefreshOrganisaatioCache))

    case Failure(t: Throwable) =>
      log.error(t, "error in organisaatio actor")

    case oid: String =>
      findByOid(oid) pipeTo sender

    case Oppilaitos(koodi) =>
      findByOppilaitoskoodi(koodi).flatMap {
        case Some(oppilaitos) => Future.successful(OppilaitosResponse(koodi, oppilaitos))
        case None => Future.failed(OppilaitosNotFoundException(koodi))
      } pipeTo sender
    case OrganizationHierarchy(oid: String) =>
      sender ! orgHierarchyCache.get(oid).getOrElse(Set())
  }
}

class MockOrganisaatioActor(config: Config) extends Actor {
  implicit val formats = DefaultFormats
  implicit val ec: ExecutionContext = context.system.dispatcher

  def find(tunniste: String): Future[Option[Organisaatio]] =
    Future.successful(Some(parse(OrganisaatioMock.findByOid(tunniste)).extract[Organisaatio]))

  override def receive: Actor.Receive = {
    case oid: String =>
      find(oid) pipeTo sender

    case Oppilaitos(koodi) =>
      find(koodi) pipeTo sender
  }
}

case class OrganisaatioResponse(numHits: Option[Int], organisaatiot: Seq[Organisaatio])

case class Oppilaitos(koodi: String)

case class OppilaitosResponse(koodi: String, oppilaitos: Organisaatio)

case class OppilaitosNotFoundException(koodi: String) extends Exception(s"Oppilaitosta ei löytynyt oppilaitoskoodilla $koodi.")

case class OrganisaatioFetchFailedException(t: Throwable) extends Exception(t)
