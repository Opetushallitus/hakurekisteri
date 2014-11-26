package fi.vm.sade.hakurekisteri.integration

import java.io.InterruptedIOException
import java.net.{ConnectException, URLEncoder, URL}
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorSystem, ActorRef}
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.cas._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport

import scala.compat.Platform
import scala.concurrent.{Promise, Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.Try
import dispatch._
import com.ning.http.client._
import scala.util.Failure
import fi.vm.sade.hakurekisteri.integration.cas.LocationHeaderNotFoundException
import fi.vm.sade.hakurekisteri.integration.cas.TGTWasNotCreatedException
import com.ning.http.client.AsyncHandler.STATE
import scala.language.implicitConversions

case class PreconditionFailedException(message: String, responseCode: Int) extends Exception(message)

case class ServiceConfig(casUrl: Option[String] = None,
                         serviceUrl: String,
                         user: Option[String] = None,
                         password: Option[String] = None)

class VirkailijaRestClient(config: ServiceConfig, jSessionIdStorage: Option[ActorRef] = None, aClient: Option[AsyncHttpClient] = None)(implicit val ec: ExecutionContext, val system: ActorSystem) extends HakurekisteriJsonSupport {
  implicit val defaultTimeout: Timeout = 60.seconds

  val casUrl: Option[String] = config.casUrl
  val serviceUrl: String = config.serviceUrl
  val user: Option[String] = config.user
  val password: Option[String] = config.password

  val logger = Logging.getLogger(system, this)

  def logConnectionFailure[T](f: Future[T], url: URL) = f.onFailure {
    case t: InterruptedIOException => logger.warning(s"connection error calling url [$url]: $t")
    case t: JSessionIdCookieException => logger.warning(t.getMessage)
  }

  val cookieExpirationMillis = 5.minutes.toMillis

  private val defaultClient = Http.configure(_
    .setConnectionTimeoutInMs(Config.httpClientConnectionTimeout)
    .setRequestTimeoutInMs(Config.httpClientRequestTimeout)
    .setIdleConnectionTimeoutInMs(Config.httpClientRequestTimeout)
    .setFollowRedirects(true)
    .setMaxRequestRetry(2)
  )

  private val internalClient: Http = aClient.map(Http(_)).getOrElse(defaultClient)

  object LocationHeader extends (Response => String) {
    def apply(r: Response) =
      Try(Option(r.getHeader("Location")).get).recoverWith{
        case  e: NoSuchElementException =>  Failure(LocationHeaderNotFoundException("location header not found"))
      }.get
  }

  class TgtFunctionHandler
    extends FunctionHandler[String](LocationHeader) with TgtHandler

  class StFunctionHandler extends AsyncCompletionHandler[String] {
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

  trait TgtHandler extends AsyncHandler[String] {
    abstract override def onStatusReceived(status: HttpResponseStatus) = {
      if (status.getStatusCode == 201)
        super.onStatusReceived(status)
      else
        throw TGTWasNotCreatedException(s"got non ok response code from cas: ${status.getStatusCode}")
    }
  }

  object client  {
    val TgtUrl = new TgtFunctionHandler
    val ServiceTicket = new StFunctionHandler

    def getTgtUrl() = {
      val tgtUrlReq = dispatch.url(s"${casUrl.get}/v1/tickets") << s"username=${URLEncoder.encode(user.get, "UTF8")}&password=${URLEncoder.encode(password.get, "UTF8")}" <:< Map("Content-Type" -> "application/x-www-form-urlencoded")
      internalClient(tgtUrlReq > TgtUrl)
    }

    val serviceUrlSuffix = "/j_spring_cas_security_check"
    val maxRetriesCas = Config.httpClientMaxRetries

    private def postfixServiceUrl(url: String): String = url match {
      case s if !s.endsWith(serviceUrlSuffix) => s"$url$serviceUrlSuffix"
      case s => s
    }

    private def casRetryable(t: Throwable): Boolean = t match {
      case t: TimeoutException => true
      case t: ConnectException => true
      case LocationHeaderNotFoundException(_) => true
      case _ => false
    }

    private def tryProxyTicket(retryCount: AtomicInteger): Future[String] = getTgtUrl().flatMap(tgtUrl => {
      val proxyReq = dispatch.url(tgtUrl) << s"service=${URLEncoder.encode(postfixServiceUrl(serviceUrl), "UTF-8")}" <:< Map("Content-Type" -> "application/x-www-form-urlencoded")
      internalClient(proxyReq > ServiceTicket)
    }).recoverWith {
      case t: ExecutionException if t.getCause != null && casRetryable(t.getCause) =>
        if (retryCount.getAndIncrement <= maxRetriesCas) {
          logger.warning(s"connection error calling cas - trying to retry: $t")
          tryProxyTicket(retryCount)
        } else {
          logger.error(s"connection error calling cas: $t")
          Future.failed(t)
        }
    }

    def getProxyTicket: Future[String] = {
      if (casUrl.isEmpty || user.isEmpty || password.isEmpty) throw new IllegalArgumentException("casUrl, user or password is not defined")

      val retryCount = new AtomicInteger(1)
      tryProxyTicket(retryCount)
    }

    def jSessionId: Future[Option[JSessionId]] = {
      jSessionIdStorage match {
        case Some(actor) =>
          (actor ? JSessionKey(serviceUrl)).mapTo[Option[JSessionId]]

        case None =>
          Future.successful(None)
      }
    }

    def withSession[T](request: Req)(f: (Req) => Future[T])(jsession: Option[JSessionId]):Future[T] = jsession match {
      case Some(session) if (session.created + cookieExpirationMillis) > Platform.currentTime =>  {
        val req = request <:< Map("Cookie" -> s"${JSessionIdCookieParser.name}=${session.sessionId}")
        f(req)
      }
      case _ =>
        for (
          ticket <- getProxyTicket;
          result <- f(request <:< Map("CasSecurityTicket" -> ticket) )
        ) yield result
    }

    def apply(uri:String) = {
      val request = dispatch.url(s"$serviceUrl$uri")
      (user,password) match{
        case (Some(_), None) => throw new IllegalArgumentException("password is missing")
        case (Some(un),Some(pw)) =>
          for (
            jsession <- jSessionId;
            result <- withSession(request)((req) => internalClient(req))(jsession)
          ) yield result

        case _ =>
          internalClient(request)
      }
    }

    object NoSessionFound extends Exception("No JSession Found")

    class SessionCapturer[T](inner: AsyncHandler[T]) extends AsyncHandler[T] {
      private val promise = Promise[JSessionId]
      val jsession = promise.future

      override def onCompleted(): T = inner.onCompleted()

      override def onHeadersReceived(headers: HttpResponseHeaders): STATE = {
        import scala.collection.JavaConversions._
        for (
          header <- headers.getHeaders.entrySet() if header.getKey == "Set-Cookie";
          value <- header.getValue if JSessionIdCookieParser.isJSessionIdCookie(value)
        ) promise.trySuccess(JSessionIdCookieParser.fromString(value))

        promise.tryFailure(NoSessionFound)

        inner.onHeadersReceived(headers)
      }

      override def onStatusReceived(responseStatus: HttpResponseStatus): STATE = inner.onStatusReceived(responseStatus)

      override def onBodyPartReceived(bodyPart: HttpResponseBodyPart): STATE = inner.onBodyPartReceived(bodyPart)

      override def onThrowable(t: Throwable): Unit = inner.onThrowable(t)
    }

    def apply[T](tuple: (String, AsyncHandler[T])): dispatch.Future[T] = {
      val (uri, handler) = tuple
      (user,password) match{
        case (Some(_), None) => throw new IllegalArgumentException("password is missing")
        case (Some(un),Some(pw)) =>
          val request = dispatch.url(s"$serviceUrl$uri")
          val sessionCapturer = new SessionCapturer(handler)

          sessionCapturer.jsession.onSuccess{
            case session =>
              jSessionIdStorage.getOrElse(system.deadLetters) ! SaveJSessionId(JSessionKey(serviceUrl), session)
          }

          for (
            jsession <- jSessionId;
            result <- withSession(request)((req) => internalClient(req.toRequest, sessionCapturer))(jsession)
          ) yield result

        case _ => internalClient((dispatch.url(s"$serviceUrl$uri").toRequest, handler))
      }
    }
  }

  import VirkailijaRestImplicits._

  def retryable(t: Throwable): Boolean = t match {
    case t: TimeoutException => true
    case t: ConnectException => true
    case PreconditionFailedException(_, code) if code >= 500 => true
    case _ => false
  }

  private def tryClient[A <: AnyRef: Manifest](uri: String, acceptedResponseCode: Int, maxRetries: Int, retryCount: AtomicInteger): Future[A] = client(uri.accept(acceptedResponseCode).as[A]).recoverWith {
    case t: ExecutionException if t.getCause != null && retryable(t.getCause) =>
      if (retryCount.getAndIncrement <= maxRetries) {
        logger.warning(s"retrying request to $uri due to $t, retry attempt #${retryCount.get - 1}")
        tryClient(uri, acceptedResponseCode, maxRetries, retryCount)
      } else Future.failed(t)
  }

  def readObject[A <: AnyRef: Manifest](uri:String, acceptedResponseCode: Int, maxRetries: Int = 0): Future[A] = {
    val retryCount = new AtomicInteger(1)
    tryClient(uri, acceptedResponseCode, maxRetries, retryCount)
  }
}

case class JSessionIdCookieException(m: String) extends Exception(m)

object JSessionIdCookieParser {
  val name = "JSESSIONID"

  def isJSessionIdCookie(cookie: String): Boolean = {
    cookie.startsWith(name)
  }

  def fromString(cookie: String): JSessionId = {
    if (!isJSessionIdCookie(cookie)) throw JSessionIdCookieException(s"not a JSESSIONID cookie: $cookie")

    val value = cookie.split(";").headOption match {
      case Some(c) => c.split("=").lastOption match {
        case Some(v) => v
        case None => throw JSessionIdCookieException(s"JSESSIONID value not found from cookie: $cookie")
      }
      case None => throw JSessionIdCookieException(s"invalid JSESSIONID cookie structure: $cookie")
    }

    JSessionId(Platform.currentTime, value)
  }
}


object ExecutorUtil {
  def createExecutor(threads: Int, poolName: String) = {
    val threadNumber = new AtomicInteger(1)

    val pool = Executors.newFixedThreadPool(threads, new ThreadFactory() {
      override def newThread(r: Runnable): Thread = {
        new Thread(r, poolName + "-" + threadNumber.getAndIncrement)
      }
    })

    ExecutionContext.fromExecutorService(pool)
  }
}

abstract class JsonExtractor(val uri: String) extends HakurekisteriJsonSupport {
  def handler[T](f: (Response) => T):AsyncHandler[T]

  def as[T:Manifest] = {
    val f = (resp: Response) => {
      import org.json4s.jackson.Serialization.read
      read[T](resp.getResponseBody)
    }

    (uri, handler(f))
  }
}

class VirkailijaResultTuples(uri: String) {
  def accept[T](codes: Int*): JsonExtractor = new JsonExtractor(uri) {
    override def handler[T](f: (Response) => T): AsyncHandler[T] = new CodeFunctionHandler(codes.toSet, f)
  }
}


object VirkailijaRestImplicits {
  implicit def req2VirkailijaResulTuples(uri:String): VirkailijaResultTuples = new VirkailijaResultTuples(uri)
}

class CodeFunctionHandler[T](override val codes: Set[Int], f: Response => T) extends FunctionHandler[T](f) with CodeHandler[T]

trait CodeHandler[T] extends AsyncHandler[T] {
  val codes: Set[Int]

  abstract override def onStatusReceived(status: HttpResponseStatus) = {
    if (codes.contains(status.getStatusCode))
      super.onStatusReceived(status)
    else
      throw PreconditionFailedException(s"precondition failed for url: ${status.getUrl}, response code: ${status.getStatusCode}", status.getStatusCode)
  }
}