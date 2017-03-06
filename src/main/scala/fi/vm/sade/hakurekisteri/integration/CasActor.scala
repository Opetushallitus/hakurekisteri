package fi.vm.sade.hakurekisteri.integration

import java.net.{ConnectException, URLEncoder}
import java.util.concurrent.{ExecutionException, TimeUnit, TimeoutException}

import akka.actor.{Actor, ActorLogging}
import akka.pattern.pipe
import com.ning.http.client.AsyncHandler.STATE
import com.ning.http.client._
import dispatch.{FunctionHandler, Http, Req}
import fi.vm.sade.hakurekisteri.integration.cas._

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

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
        val f = getJSession(serviceUrl, JsonExtractor.handler[Unit](302, 200, 404))
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

  private object LocationHeader extends (Response => String) {
    def apply(r: Response): String =
      Try(Option(r.getHeader("Location")).get).recoverWith{
        case  e: NoSuchElementException => Failure(LocationHeaderNotFoundException("location header not found"))
      }.get
  }

  private class TgtFunctionHandler
    extends FunctionHandler[String](LocationHeader) with TgtHandler

  private class StFunctionHandler extends AsyncCompletionHandler[String] {
    override def onStatusReceived(status: HttpResponseStatus): STATE = {
      if (status.getStatusCode == 200)
        super.onStatusReceived(status)
      else
        throw STWasNotCreatedException(s"got non ok response from cas: ${status.getStatusCode}")
    }

    override def onCompleted(response: Response): String = {
      val st = response.getResponseBody.trim
      if (TicketValidator.isValidSt(st)) st
      else throw InvalidServiceTicketException(st)
    }
  }

  private trait TgtHandler extends AsyncHandler[String] {
    abstract override def onStatusReceived(status: HttpResponseStatus): STATE = {
      if (status.getStatusCode == 201)
        super.onStatusReceived(status)
      else
        throw TGTWasNotCreatedException(s"got non ok response code from cas: ${status.getStatusCode}")
    }
  }

  private val TgtUrl = new TgtFunctionHandler
  private val ServiceTicket = new StFunctionHandler

  private def getTgtUrl = {
    val tgtUrlReq = dispatch.url(s"${casUrl.get}/v1/tickets") << s"username=${URLEncoder.encode(user.get, "UTF8")}&password=${URLEncoder.encode(password.get, "UTF8")}" <:< Map("Content-Type" -> "application/x-www-form-urlencoded")
    internalClient(tgtUrlReq > TgtUrl)
  }

  private def retryable(t: Throwable): Boolean = t match {
    case t: TimeoutException => true
    case t: ConnectException => true
    case LocationHeaderNotFoundException(_) => true
    case _ => false
  }

  private def tryServiceTicket(retry: Int): Future[String] = getTgtUrl.flatMap(tgtUrl => {
    val proxyReq = dispatch.url(tgtUrl) << s"service=${URLEncoder.encode(serviceUrl, "UTF-8")}" <:< Map("Content-Type" -> "application/x-www-form-urlencoded")
    internalClient(proxyReq > ServiceTicket)
  }).recoverWith {
    case t: ExecutionException if t.getCause != null && retryable(t.getCause) && retry < serviceConfig.httpClientMaxRetries =>
      log.warning(s"retrying request to $casUrl due to $t, retry attempt #${retry + 1}")
      tryServiceTicket(retry + 1)
  }

  private def getServiceTicket: Future[String] = {
    tryServiceTicket(0)
  }

  private object NoSessionFound extends Exception("No JSession Found")

  private class SessionCapturer[T](inner: AsyncHandler[T]) extends AsyncHandler[T] {
    private val promise = Promise[JSessionId]
    val jsession: Future[JSessionId] = promise.future

    log.debug("initialized session capturer")

    override def onCompleted(): T = inner.onCompleted()

    override def onHeadersReceived(headers: HttpResponseHeaders): STATE = {
      import scala.collection.JavaConversions._
      for (
        header <- headers.getHeaders.entrySet() if header.getKey == "Set-Cookie";
        value <- header.getValue if JSessionIdCookieParser.isJSessionIdCookie(value)
      ) {
        val jses = JSessionIdCookieParser.fromString(value)
        log.debug(s"got jsession $jses")
        promise.trySuccess(jses)
      }

      if (!promise.isCompleted) {
        log.debug("no session found")
        promise.tryFailure(NoSessionFound)
      }

      inner.onHeadersReceived(headers)
    }

    override def onStatusReceived(responseStatus: HttpResponseStatus): STATE = inner.onStatusReceived(responseStatus)

    override def onBodyPartReceived(bodyPart: HttpResponseBodyPart): STATE = inner.onBodyPartReceived(bodyPart)

    override def onThrowable(t: Throwable): Unit = {
      promise.tryFailure(t)
      inner.onThrowable(t)
    }
  }

  private def getJSession[T](uri: String, handler: AsyncHandler[T]): dispatch.Future[JSessionId] = {
    val request: Req = dispatch.url(uri)
    val sessionCapturer = new SessionCapturer(handler)

    val res: Future[JSessionId] = getServiceTicket.flatMap(ticket => {
      log.debug(s"about to call $uri with ticket $ticket to get jsession")
      internalClient((request <<? Map("ticket" -> ticket)).toRequest, sessionCapturer)
      sessionCapturer.jsession
    })

    res.onSuccess {
      case t =>
        log.debug(s"call to $uri was successful")
        log.debug(s"got jsession $t")
    }
    res
  }
}
