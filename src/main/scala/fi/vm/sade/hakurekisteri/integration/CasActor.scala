package fi.vm.sade.hakurekisteri.integration

import java.net.{ConnectException, URLEncoder}
import java.util.concurrent.{ExecutionException, TimeoutException}
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorLogging, Actor}
import akka.pattern.pipe
import com.ning.http.client.AsyncHandler.STATE
import com.ning.http.client._
import dispatch.{Req, FunctionHandler, Http}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.cas._

import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Try}

class CasActor(config: ServiceConfig, aClient: Option[AsyncHttpClient] = None)(implicit val ec: ExecutionContext) extends Actor with ActorLogging {
  val jSessionIdCache: FutureCache[JSessionKey, JSessionId] = new FutureCache[JSessionKey, JSessionId](5.minutes.toMillis)

  val serviceUrl = config.serviceUrl
  val casUrl = config.casUrl
  val user = config.user
  val password = config.password

  val cookieExpirationMillis = 5.minutes.toMillis

  private val internalClient: Http = aClient.map(Http(_)).getOrElse(Http.configure(_
    .setConnectionTimeoutInMs(Config.httpClientConnectionTimeout)
    .setRequestTimeoutInMs(Config.httpClientRequestTimeout)
    .setIdleConnectionTimeoutInMs(Config.httpClientRequestTimeout)
    .setFollowRedirects(false)
    .setMaxRequestRetry(2)
  ))

  override def receive: Receive = {
    case k: JSessionKey =>
      getJSessionIdFor(k) pipeTo sender
  }

  private def getJSessionIdFor(key: JSessionKey): Future[JSessionId] = {
    if (jSessionIdCache.contains(key)) {
      log.debug(s"jsession for $key found in cache")
      jSessionIdCache.get(key)
    } else {
      log.debug(s"no jsession found or it was expired, fetching a new one for $serviceUrl")
      import fi.vm.sade.hakurekisteri.integration.VirkailijaRestImplicits._

      val jSession = getJSession(postfixServiceUrl(serviceUrl).accept(302, 200, 404).as[Unit])

      jSessionIdCache + (JSessionKey(serviceUrl), jSession)

      jSession.onSuccess {
        case s => log.debug(s"got jsession $s for $serviceUrl")
      }

      jSession
    }
  }

  private object LocationHeader extends (Response => String) {
    def apply(r: Response) =
      Try(Option(r.getHeader("Location")).get).recoverWith{
        case  e: NoSuchElementException =>  Failure(LocationHeaderNotFoundException("location header not found"))
      }.get
  }

  private class TgtFunctionHandler
    extends FunctionHandler[String](LocationHeader) with TgtHandler

  private class StFunctionHandler extends AsyncCompletionHandler[String] {
    override def onStatusReceived(status: HttpResponseStatus) = {
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
    abstract override def onStatusReceived(status: HttpResponseStatus) = {
      if (status.getStatusCode == 201)
        super.onStatusReceived(status)
      else
        throw TGTWasNotCreatedException(s"got non ok response code from cas: ${status.getStatusCode}")
    }
  }

  private val TgtUrl = new TgtFunctionHandler
  private val ServiceTicket = new StFunctionHandler

  private def getTgtUrl() = {
    val tgtUrlReq = dispatch.url(s"${casUrl.get}/v1/tickets") << s"username=${URLEncoder.encode(user.get, "UTF8")}&password=${URLEncoder.encode(password.get, "UTF8")}" <:< Map("Content-Type" -> "application/x-www-form-urlencoded")
    internalClient(tgtUrlReq > TgtUrl)
  }

  private val serviceUrlSuffix = "/j_spring_cas_security_check"
  private val maxRetriesCas = Config.httpClientMaxRetries

  private def postfixServiceUrl(url: String): String = url match {
    case s if !s.endsWith(serviceUrlSuffix) => s"$url$serviceUrlSuffix"
    case s => s
  }

  private def retryable(t: Throwable): Boolean = t match {
    case t: TimeoutException => true
    case t: ConnectException => true
    case LocationHeaderNotFoundException(_) => true
    case _ => false
  }

  private def tryServiceTicket(retryCount: AtomicInteger): Future[String] = getTgtUrl().flatMap(tgtUrl => {
    val proxyReq = dispatch.url(tgtUrl) << s"service=${URLEncoder.encode(postfixServiceUrl(serviceUrl), "UTF-8")}" <:< Map("Content-Type" -> "application/x-www-form-urlencoded")
    internalClient(proxyReq > ServiceTicket)
  }).recoverWith {
    case t: ExecutionException if t.getCause != null && retryable(t.getCause) =>
      if (retryCount.getAndIncrement <= maxRetriesCas) {
        log.warning(s"retrying request to $casUrl due to $t, retry attempt #${retryCount.get - 1}")
        tryServiceTicket(retryCount)
      } else {
        log.error(s"error calling cas: $t")
        Future.failed(t)
      }
  }

  private def getServiceTicket: Future[String] = {
    if (casUrl.isEmpty || user.isEmpty || password.isEmpty) throw new IllegalArgumentException("casUrl, user or password is not defined")

    val retryCount = new AtomicInteger(1)
    tryServiceTicket(retryCount)
  }

  private object NoSessionFound extends Exception("No JSession Found")

  private class SessionCapturer[T](inner: AsyncHandler[T]) extends AsyncHandler[T] {
    private val promise = Promise[JSessionId]
    val jsession = promise.future

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

    override def onThrowable(t: Throwable): Unit = inner.onThrowable(t)
  }

  private def getJSession[T](tuple: (String, AsyncHandler[T])): dispatch.Future[JSessionId] = {
    val (uri, handler) = tuple
    val request: Req = dispatch.url(uri)
    val sessionCapturer = new SessionCapturer(handler)

    val res: Future[T] = getServiceTicket.flatMap(ticket => {
      log.debug(s"about to call $uri with ticket $ticket to get jsession")
      internalClient((request <<? Map("ticket" -> ticket)).toRequest, sessionCapturer)
    })

    res.onComplete{
      case t => log.debug(s"call to $uri ${if (t.isSuccess) "was successful" else "failed: " + t.failed.get}")
    }

    sessionCapturer.jsession.onSuccess {
      case t => log.debug(s"got jsession $t")
    }

    sessionCapturer.jsession
  }
}
