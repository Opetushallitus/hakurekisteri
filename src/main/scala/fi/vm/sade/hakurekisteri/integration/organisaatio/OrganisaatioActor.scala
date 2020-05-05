package fi.vm.sade.hakurekisteri.integration.organisaatio

import java.util.concurrent.ExecutionException

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory
import fi.vm.sade.hakurekisteri.integration.mocks.OrganisaatioMock
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, PreconditionFailedException, VirkailijaRestClient}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import support.TypedActorRef

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Success, Try}


object RefreshOrganisaatioCache


class HttpOrganisaatioActor(organisaatioClient: VirkailijaRestClient,
                            config: Config,
                            cacheFactory: CacheFactory,
                            initDuringStartup: Boolean = true,
                            ttl: Option[FiniteDuration] = None) extends Actor with ActorLogging {
  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(config.integrations.asyncOperationThreadPoolSize, getClass.getSimpleName)
  val maxRetries: Int = config.integrations.organisaatioConfig.httpClientMaxRetries
  val timeToLive: FiniteDuration = ttl.getOrElse(config.integrations.organisaatioCacheHours.hours)
  val reloadInterval = timeToLive / 2
  val refresh = context.system.scheduler.schedule(reloadInterval, reloadInterval, self, RefreshOrganisaatioCache)
  var retryRefresh: Option[Cancellable] = None

  log.info(s"timeToLive: $timeToLive, reloadInterval: $reloadInterval")

  private val cache = cacheFactory.getInstance[String, Organisaatio](timeToLive.toMillis, this.getClass, classOf[Organisaatio], "organisaatio")
  private val childOidCache = cacheFactory.getInstance[String, ChildOids](timeToLive.toMillis, this.getClass, classOf[ChildOids], "child-oids")
  private var oppilaitoskoodiIndex: Map[String, String] = Map()

  private def saveOrganisaatiot(s: Seq[Organisaatio]): Future[_] = {
    s.foreach { org =>
      if (org.oppilaitosKoodi.isDefined)
        oppilaitoskoodiIndex = oppilaitoskoodiIndex + (org.oppilaitosKoodi.get -> org.oid)
    }
    val recursiveSaveFuture = Future.sequence(s.map { org: Organisaatio =>
      cache.contains(org.oid).flatMap {
        case false => (cache + (org.oid, org)).map(_ => org)
        case true => Future.successful(org)
      }
    }).flatMap { orgs =>
      Future.sequence(orgs.map { org =>
        if (org.children.nonEmpty) {
          saveOrganisaatiot(org.children)
        } else {
          Future.successful(org)
        }
      })
    }
    recursiveSaveFuture
  }
  private def saveChildOids(parentOid: String, childOids: ChildOids): Unit = {
    childOidCache + (parentOid, childOids)
  }

  private def findAndCacheChildOids(parentOid: String): Future[Option[ChildOids]] = {
    val tulos = organisaatioClient.readObject[ChildOids]("organisaatio-service.organisaatio.childoids", parentOid)(200, maxRetries).map(Option(_)).recoverWith {
      case p: ExecutionException if p.getCause != null && notFound(p.getCause) =>
        log.warning(s"organisaatios child OIDs not found with parent OID $parentOid")
        Future.successful(None)
    }

    tulos.foreach {
      oids => self ! CacheChildOids(parentOid, oids.get)
    }

    tulos
  }
  private def notFound(t: Throwable) = t match {
    case PreconditionFailedException(_, 204) => true
    case _ => false
  }

  private def isCausedBy403(t: Throwable): Boolean = {
    try {
      t.getCause match {
        case PreconditionFailedException(_, 403) => true
        case e: Exception => isCausedBy403(e)
        case _ => false
      }
    } catch {
      case e: Exception =>
        log.error(e, s"Unexpected error in isCausedBy403, defaulting to false")
        false
    }
  }

  private def findAndCache(tunniste: String): Future[Option[Organisaatio]] = {
    if (tunniste.isEmpty) {
      val errorMessage = "findAndCache error: string tunniste must not be empty"
      log.error(errorMessage)
      Future.failed(new IllegalArgumentException(errorMessage))
    } else {
      val organisationWithoutChildren: Future[Option[Organisaatio]] = organisaatioClient
        .readObject[Organisaatio]("organisaatio-service.organisaatio", tunniste)(200, maxRetries)
        .map(Option(_))
        .recoverWith {
        case p: ExecutionException if p.getCause != null && notFound(p.getCause) =>
          log.warning(s"organisaatio not found with tunniste $tunniste")
          Future.successful(None)
        case e: Exception if isCausedBy403(e) =>
          log.warning(s"Organisaatio forbidden with tunniste $tunniste. Ignoring the organization.")
          Future.successful(None)
        case o: Exception =>
          log.error(o, s"Unforeseen error occurred while fetching organisaatio with tunniste $tunniste")
          throw o
      }

      val organisationWithChildren: Future[Option[Organisaatio]] = organisationWithoutChildren.flatMap {
        case Some(organisaatio) =>
          findAndCacheChildOids(organisaatio.oid).
            map(_.toSeq.flatMap(_.oids)).
            flatMap(childOids => Future.sequence(childOids.map(findAndCache))).
            map(_.flatten).
            map(childOrganisations => Some(organisaatio.copy(children = childOrganisations)))
        case None => Future.successful(None)
      }

      organisationWithChildren.foreach {
        _.foreach { organisaatio =>
          self ! CacheOrganisaatiot(Seq(organisaatio))
        }
      }

      organisationWithChildren
    }
  }

  private def findByOid(oid: String): Future[Option[Organisaatio]] = {
    cache.get(oid, o => findAndCache(o))
  }

  private def findChildOids(parentOid: String): Future[Option[ChildOids]] = {
    childOidCache.get(parentOid, _ => findAndCacheChildOids(parentOid))
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
  case class CacheChildOids(parentOid: String, childOids: ChildOids)

  private val koodiQueriesQueue: TrieMap[String, mutable.MutableList[ActorRef]] = TrieMap()

  override def receive: Receive = {
    case RefreshOrganisaatioCache => fetchAll(sender())

    case CacheOrganisaatiot(o) =>
      val savingFuture = saveOrganisaatiot(o).map { _ =>
        log.info(s"${o.size} saved to cache, oppilaitoskoodiIndex: ${oppilaitoskoodiIndex.size}")
        true
      }
      if (sender != ActorRef.noSender) {
        savingFuture.pipeTo(sender)
      } else {
        Await.result(savingFuture, 1.minute)
      }

    case CacheChildOids(parentOid, childOids) =>
      saveChildOids(parentOid, childOids)
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

    case GetChildOids(parentOid) =>
      findChildOids(parentOid) pipeTo sender

    case Oppilaitos(koodi) =>
      if (koodiQueriesQueue.keySet.contains(koodi)) {
        koodiQueriesQueue(koodi).+=(sender())
      } else {
        val refs = new mutable.MutableList[ActorRef]()
        refs.+=(sender())
        koodiQueriesQueue.put(koodi, refs)
        findByOppilaitoskoodi(koodi).onComplete { response =>
          self ! HandleKoodiResponse(koodi, response)
        }
      }

    case HandleKoodiResponse(koodi: String, response: Try[Option[Organisaatio]]) =>
      response match {
        case Success(Some(oppilaitos)) =>
          koodiQueriesQueue(koodi).foreach(_ ! OppilaitosResponse(koodi, oppilaitos))
          koodiQueriesQueue.remove(koodi)
        case Success(None) =>
          koodiQueriesQueue(koodi).foreach(_ ! OppilaitosNotFoundException(koodi))
          koodiQueriesQueue.remove(koodi)
        case failure@scala.util.Failure(_) =>
          koodiQueriesQueue(koodi).foreach(_ ! failure)
          koodiQueriesQueue.remove(koodi)
      }
  }
}

case class HandleKoodiResponse(koodi: String, response: Try[Option[Organisaatio]])

class MockOrganisaatioActor(config: Config) extends Actor {
  implicit val formats = DefaultFormats
  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(config.integrations.asyncOperationThreadPoolSize, getClass.getSimpleName)

  def find(tunniste: String): Future[Option[Organisaatio]] =
    Future.successful(Some(parse(OrganisaatioMock.findByOid(tunniste)).extract[Organisaatio]))

  override def receive: Actor.Receive = {
    case oid: String =>
      find(oid) pipeTo sender

    case Oppilaitos(koodi) =>
      find(koodi) pipeTo sender
  }
}

case class GetChildOids(parentOid: String)

case class OrganisaatioResponse(numHits: Option[Int], organisaatiot: Seq[Organisaatio])

case class Oppilaitos(koodi: String)

case class OppilaitosResponse(koodi: String, oppilaitos: Organisaatio)

case class OppilaitosNotFoundException(koodi: String) extends Exception(s"Oppilaitosta ei löytynyt oppilaitoskoodilla $koodi.")

case class OrganisaatioFetchFailedException(t: Throwable) extends Exception(t)

case class OrganisaatioActorRef(actor: ActorRef) extends TypedActorRef
