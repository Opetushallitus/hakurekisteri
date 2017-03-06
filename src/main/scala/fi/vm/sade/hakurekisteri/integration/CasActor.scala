package fi.vm.sade.hakurekisteri.integration

import java.net.{ConnectException, URLEncoder}
import java.util.concurrent.{ExecutionException, TimeUnit, TimeoutException}

import akka.actor.{Actor, ActorLogging}
import akka.pattern.pipe
import com.ning.http.client._
import dispatch.{Http, Req}
import fi.vm.sade.hakurekisteri.integration.cas._

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case object GetJSession
case class JSessionId(sessionId: String)

class CasActor(serviceConfig: ServiceConfig, aClient: Option[AsyncHttpClient])(implicit val ec: ExecutionContext) extends Actor with ActorLogging {

  private sealed trait State
  private case object Empty extends State
  private case class Fetching(f: Future[JSessionId]) extends State
  private case class Cached(id: JSessionId) extends State

  private case object Clear
  private case class Set(id: JSessionId)

  private val jSessionTtl = Duration(5, TimeUnit.MINUTES)
  private val serviceUrlSuffix = "/j_spring_cas_security_check"
  private val serviceUrl = serviceConfig.serviceUrl match {
    case s if !s.endsWith(serviceUrlSuffix) => s"${serviceConfig.serviceUrl}$serviceUrlSuffix"
    case s => s
  }
  private val casUrl = serviceConfig.casUrl
  private val user = serviceConfig.user
  private val password = serviceConfig.password
  if (casUrl.isEmpty || user.isEmpty || password.isEmpty) throw new IllegalArgumentException("casUrl, user or password is not defined")

  private val internalClient: Http = aClient.map(Http(_)).getOrElse(Http.configure(_
    .setConnectionTimeoutInMs(serviceConfig.httpClientConnectionTimeout)
    .setRequestTimeoutInMs(serviceConfig.httpClientRequestTimeout)
    .setIdleConnectionTimeoutInMs(serviceConfig.httpClientRequestTimeout)
    .setFollowRedirects(false)
    .setMaxRequestRetry(2)
  ))

  private var state: State = Empty

  override def receive: Receive = {
    case GetJSession => state match {
      case Cached(id) =>
        sender ! id
      case Fetching(f) =>
        f pipeTo sender
      case Empty =>
        implicit val system = context.system
        val f = getJSession(serviceUrl)
        f.onComplete {
          case Success(id) =>
            self ! Set(id)
          case Failure(e) =>
            log.error(e, s"Fetching jsession for $serviceUrl failed")
            self ! Clear
        }
        state = Fetching(f)
        f pipeTo sender
    }
    case Set(id) =>
      state = Cached(id)
      context.system.scheduler.scheduleOnce(jSessionTtl, self, Clear)
    case Clear =>
      state = Empty
  }

  private def getTgtUrl = {
    val tgtUrlReq = dispatch.url(s"${casUrl.get}/v1/tickets") << s"username=${URLEncoder.encode(user.get, "UTF8")}&password=${URLEncoder.encode(password.get, "UTF8")}" <:< Map("Content-Type" -> "application/x-www-form-urlencoded")
    internalClient(tgtUrlReq > ((r: Response) => (r.getStatusCode, Option(r.getHeader("Location"))) match {
      case (201, Some(location)) => location
      case (201, None) => throw LocationHeaderNotFoundException("location header not found")
      case (code, _) => throw TGTWasNotCreatedException(s"got non ok response code from cas: $code")
    }))
  }

  private def retryable(t: Throwable): Boolean = t match {
    case t: TimeoutException => true
    case t: ConnectException => true
    case LocationHeaderNotFoundException(_) => true
    case _ => false
  }

  private def tryServiceTicket(retry: Int): Future[String] = getTgtUrl.flatMap(tgtUrl => {
    val proxyReq = dispatch.url(tgtUrl) << s"service=${URLEncoder.encode(serviceUrl, "UTF-8")}" <:< Map("Content-Type" -> "application/x-www-form-urlencoded")
    internalClient(proxyReq > ((r: Response) => (r.getStatusCode, r.getResponseBody.trim) match {
      case (200, st) if TicketValidator.isValidSt(st) => st
      case (200, st) => throw InvalidServiceTicketException(st)
      case (code, _) => throw STWasNotCreatedException(s"got non ok response from cas: $code")
    }))
  }).recoverWith {
    case t: ExecutionException if t.getCause != null && retryable(t.getCause) && retry < serviceConfig.httpClientMaxRetries =>
      log.warning(s"retrying request to $casUrl due to $t, retry attempt #${retry + 1}")
      tryServiceTicket(retry + 1)
  }

  private def getServiceTicket: Future[String] = {
    tryServiceTicket(0)
  }

  private object NoSessionFound extends Exception("No JSession Found")

  private def getJSession(uri: String): Future[JSessionId] = {
    val request: Req = dispatch.url(uri)
    getServiceTicket.flatMap(ticket => {
      log.debug(s"about to call $uri with ticket $ticket to get jsession")
      internalClient((request <<? Map("ticket" -> ticket)) > ((r: Response) => (r.getStatusCode, Option(r.getHeader("Set-Cookie")).filter(JSessionIdCookieParser.isJSessionIdCookie)) match {
        case (200 | 302 | 404, Some(cookie)) =>
          val id = JSessionIdCookieParser.fromString(cookie)
          log.debug(s"call to $uri was successful")
          log.debug(s"got jsession $id")
          id
        case (200 | 302 | 404, None) => throw NoSessionFound
        case (code, _) => throw PreconditionFailedException(s"precondition failed for url: $uri, response code: $code, text: ${r.getStatusText}", code)
      }))
    })
  }
}
