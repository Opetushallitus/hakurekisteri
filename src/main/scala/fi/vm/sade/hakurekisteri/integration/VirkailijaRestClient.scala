package fi.vm.sade.hakurekisteri.integration

import java.net.ConnectException
import java.util.UUID
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorSystem, Props}
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout
import com.ning.http.client._
import dispatch._
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}


case class PreconditionFailedException(message: String, responseCode: Int) extends Exception(message)

case class ServiceConfig(casUrl: Option[String] = None,
                         serviceUrl: String,
                         user: Option[String] = None,
                         password: Option[String] = None)

class VirkailijaRestClient(config: ServiceConfig, aClient: Option[AsyncHttpClient] = None)(implicit val ec: ExecutionContext, val system: ActorSystem) extends HakurekisteriJsonSupport {
  implicit val defaultTimeout: Timeout = 60.seconds

  val serviceUrl: String = config.serviceUrl
  val user = config.user
  val password = config.password
  val logger = Logging.getLogger(system, this)

  private val internalClient: Http = aClient.map(Http(_)).getOrElse(Http.configure(_
    .setConnectionTimeoutInMs(Config.httpClientConnectionTimeout)
    .setRequestTimeoutInMs(Config.httpClientRequestTimeout)
    .setIdleConnectionTimeoutInMs(Config.httpClientRequestTimeout)
    .setFollowRedirects(true)
    .setMaxRequestRetry(2)
  ))
  val serviceName = serviceUrl.split("/").reverse.headOption
  val casActor = system.actorOf(Props(new CasActor(config, aClient)), s"cas-client-${serviceName.getOrElse(UUID.randomUUID())}")

  object client {
    def jSessionId: Future[JSessionId] = (casActor ? JSessionKey(serviceUrl)).mapTo[JSessionId]

    import org.json4s.jackson.Serialization._

    class JsonReq(request: Req) {
      def attachJsonBody[A <: AnyRef : Manifest](body: Option[A]): Req = body match {
        case Some(a) =>
          (request << write[A](a)(jsonFormats)).setContentType("application/json", "UTF-8")
        case None => request
      }
    }

    implicit def req2JsonReq(req:Req):JsonReq = new JsonReq(req)

    def withSessionAndBody[A <: AnyRef: Manifest, B <: AnyRef: Manifest](request: Req)(f: (Req) => Future[B])(jSsessionId: String)(body: Option[A] = None): Future[B] = {
      f(request.attachJsonBody(body) <:< Map("Cookie" -> s"${JSessionIdCookieParser.name}=$jSsessionId"))
    }

    def withBody[A <: AnyRef: Manifest, B <: AnyRef: Manifest](request: Req)(f: (Req) => Future[B])(body: Option[A] = None): Future[B] = {
      f(request.attachJsonBody(body))
    }

    def apply[A <: AnyRef: Manifest, B <: AnyRef: Manifest](tuple: (String, AsyncHandler[B]), body: Option[A] = None): dispatch.Future[B] = {
      val (uri, handler) = tuple
      val request = dispatch.url(s"$serviceUrl$uri") <:< Map("Caller-Id" -> "suoritusrekisteri-backend")
      (user, password) match{
        case (Some(un), Some(pw)) =>
          for (
            jsession <- jSessionId;
            result <- withSessionAndBody[A, B](request)((req) => internalClient(req.toRequest, handler))(jsession.sessionId)(body)
          ) yield result

        case _ =>
          for (
            result <- withBody[A, B](request)((req) => internalClient(req.toRequest, handler))(body)
          ) yield result
      }
    }
  }

  import fi.vm.sade.hakurekisteri.integration.VirkailijaRestImplicits._

  def retryable(t: Throwable): Boolean = t match {
    case t: TimeoutException => true
    case t: ConnectException => true
    case PreconditionFailedException(_, code) if code >= 500 => true
    case _ => false
  }

  private def tryClient[A <: AnyRef: Manifest](uri: String, acceptedResponseCode: Int, maxRetries: Int, retryCount: AtomicInteger): Future[A] = client[A, A](uri.accept(acceptedResponseCode).as[A]).recoverWith {
    case t: ExecutionException if t.getCause != null && retryable(t.getCause) =>
      if (retryCount.getAndIncrement <= maxRetries) {
        logger.warning(s"retrying request to $uri due to $t, retry attempt #${retryCount.get - 1}")
        tryClient(uri, acceptedResponseCode, maxRetries, retryCount)
      } else Future.failed(t)
  }

  def result(t: Try[_]): String = t match {
    case Success(_) => "success"
    case Failure(e) => s"failure: $e"
  }

  def logLongQuery(f: Future[_], uri: String) = {
    val t0 = Platform.currentTime
    f.onComplete(t => {
      val took = Platform.currentTime - t0
      if (took > Config.httpClientSlowRequest) {
        logger.warning(s"slow request: url $serviceUrl$uri took $took ms to complete, result was ${result(t)}")
      }
    })
  }

  def readObject[A <: AnyRef: Manifest](uri: String, acceptedResponseCode: Int, maxRetries: Int = 0): Future[A] = {
    val retryCount = new AtomicInteger(1)
    val result = tryClient[A](uri, acceptedResponseCode, maxRetries, retryCount)
    logLongQuery(result, uri)
    result
  }

  def postObject[A <: AnyRef: Manifest, B <: AnyRef: Manifest](uri: String, acceptedResponseCode: Int, resource: A): Future[B] = {
    val result = client[A, B](uri.accept(acceptedResponseCode).as[B], Some(resource))
    logLongQuery(result, uri)
    result
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

    JSessionId(value)
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
  def handler[T](f: (Response) => T): AsyncHandler[T]

  def as[T: Manifest] = {
    val f = (resp: Response) => {
      import org.json4s.jackson.Serialization.read
      if (manifest[T] == manifest[String]) resp.getResponseBody.asInstanceOf[T]
      else read[T](resp.getResponseBody)
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