package fi.vm.sade.hakurekisteri.integration.organisaatio

import java.net.URLEncoder

import akka.actor.{ActorLogging, Actor, Cancellable}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.{PreconditionFailedException, VirkailijaRestClient}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class Oppilaitos(koodi: String)
case class OppilaitosResponse(koodi: String, oppilaitos: Organisaatio)

case class OppilaitosNotFoundException(koodi: String) extends Exception(s"Oppilaitosta ei löytynyt oppilaitoskoodilla $koodi.")

class OrganisaatioActor(organisaatioClient: VirkailijaRestClient) extends Actor with ActorLogging {
  implicit val executionContext: ExecutionContext = context.dispatcher
  private var cache: Map[String, (Long, Future[Option[Organisaatio]])] = Map()
  val maxRetries = Config.httpClientMaxRetries

  class Refresh
  private object refresh extends Refresh

  case class Refetch(oid: String)

  var cancellable: Option[Cancellable] = None

  override def preStart(): Unit = {
    organisaatioClient.readObject[Seq[String]]("/rest/organisaatio", 200, maxRetries).onSuccess {
      case s: Seq[String] =>
        fetchOrgs(s)
    }
    cancellable = Some(context.system.scheduler.schedule(10.minutes,
      10.minutes,
      self,
      refresh)(context.dispatcher, self))
  }

  def fetchOrgs(s: Seq[String], mf: String => AnyRef = (s) => s) = s.grouped(10).zipWithIndex.foreach((t: (Seq[String], Int)) => {
    t._1 foreach(oid => {context.system.scheduler.scheduleOnce(t._2.second, self, mf(oid))})
  })

  override def postStop(): Unit = {
    cancellable.foreach(_.cancel())
  }

  val timeToLive = Config.organisaatioCacheHours.hours

  case class Save(oid: String, value: (Long, Future[Option[Organisaatio]]))

  override def receive: Receive = {
    case oid: String => find(oid)._2 pipeTo sender
    case Oppilaitos(koodi) =>
      val f = find(koodi)._2
      f.flatMap {
        case Some(org) => Future.successful(OppilaitosResponse(koodi, org))
        case None => Future.failed(OppilaitosNotFoundException(koodi))
      } pipeTo sender
    case Refetch(oid) => val result = newValue(oid)
                         result._2.onSuccess {case _ => self ! Save(oid, result)}
                         result._2.onFailure {case _ => log.warning("fetching organisation data for %s failed. Trying again".format(oid))
                                                        self ! Refetch(oid)}
    case Save(oid, result) => cache = cache + (oid -> result)
    case refresh: Refresh => Future(fetchOrgs(cache.toSeq.filter(t => t._2._1 < Platform.currentTime).map(_._1), Refetch))
  }

  def find(oid: String): (Long, Future[Option[Organisaatio]]) = {
    Try(cache(oid)).recoverWith{ case e: NoSuchElementException => Try({val result = newValue(oid); cache = cache + (oid -> result); result})}.get
  }

  def newValue(oid: String): (Long, Future[Option[Organisaatio]]) = {
    val organisaatio: Future[Option[Organisaatio]] = organisaatioClient.readObject[Organisaatio](s"/rest/organisaatio/${URLEncoder.encode(oid, "UTF-8")}", 200, maxRetries).map(Option(_)).recoverWith {
      case p: PreconditionFailedException if p.responseCode == 204 => log.warning(s"organisaatio not found with oid $oid"); Future.successful(None)
    }
    (Platform.currentTime + timeToLive.toMillis, organisaatio)
  }
}
