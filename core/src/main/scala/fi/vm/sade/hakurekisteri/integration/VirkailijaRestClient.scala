package fi.vm.sade.hakurekisteri.integration

import java.io.InputStreamReader
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
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.scalaproperties.OphProperties

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}


case class PreconditionFailedException(message: String, responseCode: Int) extends Exception(message)

class HttpConfig(properties: Map[String, String] = Map.empty) {
  val httpClientConnectionTimeout = properties.getOrElse("suoritusrekisteri.http.client.connection.timeout.ms", "10000").toInt
  val httpClientRequestTimeout = properties.getOrElse("suoritusrekisteri.http.client.request.timeout.ms", "180000").toInt
  val httpClientMaxRetries = properties.getOrElse("suoritusrekisteri.http.client.max.retries", "1").toInt
  val httpClientSlowRequest = properties.getOrElse("suoritusrekisteri.http.client.slow.request.ms", "1000").toLong
}

case class ServiceConfig(casUrl: Option[String] = None,
                         serviceUrl: String,
                         user: Option[String] = None,
                         password: Option[String] = None,
                         properties: Map[String, String] = Map.empty) extends HttpConfig(properties) {
}

object OphUrlProperties {
  def setupProperties = {
    val p = new OphProperties()
    p.config.addClassPathFile("/suoritusrekisteri-web-oph.properties")
    p.reload()
  }
  val ophProperties = setupProperties
}

class VirkailijaRestClient(config: ServiceConfig, aClient: Option[AsyncHttpClient] = None)(implicit val ec: ExecutionContext, val system: ActorSystem) extends HakurekisteriJsonSupport {
  val ophProperties = OphUrlProperties.ophProperties
  implicit val defaultTimeout: Timeout = 60.seconds

  val serviceUrl: String = config.serviceUrl
  val user = config.user
  val password = config.password
  val logger = Logging.getLogger(system, this)
  val serviceName = serviceUrl.split("/").lastOption.getOrElse(UUID.randomUUID())

  private val internalClient: Http = aClient.map(Http(_)).getOrElse(Http.configure(_
    .setConnectionTimeoutInMs(config.httpClientConnectionTimeout)
    .setRequestTimeoutInMs(config.httpClientRequestTimeout)
    .setIdleConnectionTimeoutInMs(config.httpClientRequestTimeout)
    .setFollowRedirects(true)
    .setMaxRequestRetry(2)
  ))
  val casActor = system.actorOf(Props(new CasActor(config, aClient)), s"$serviceName-cas-client-pool")

  object client {
    private def jSessionId: Future[JSessionId] = (casActor ? JSessionKey(serviceUrl)).mapTo[JSessionId]

    import org.json4s.jackson.Serialization._

    private class JsonReq(request: Req) {
      def attachJsonBody[A <: AnyRef : Manifest](body: Option[A]): Req = body match {
        case Some(a) =>
          (request << write[A](a)(jsonFormats)).setContentType("application/json", "UTF-8")
        case None => request
      }
    }

    private implicit def req2JsonReq(req:Req):JsonReq = new JsonReq(req)

    private def withSessionAndBody[A <: AnyRef: Manifest, B <: AnyRef: Manifest](request: Req)(f: (Req) => Future[B])(jSsessionId: String)(body: Option[A] = None): Future[B] = {
      f(request.attachJsonBody(body) <:< Map("Cookie" -> s"${JSessionIdCookieParser.name}=$jSsessionId"))
    }

    private def withBody[A <: AnyRef: Manifest, B <: AnyRef: Manifest](request: Req)(f: (Req) => Future[B])(body: Option[A] = None): Future[B] = {
      f(request.attachJsonBody(body))
    }

    def request[A <: AnyRef: Manifest, B <: AnyRef: Manifest](url: String)(handler: AsyncHandler[B], body: Option[A] = None): dispatch.Future[B] = {
      val request = dispatch.url(url) <:< Map("Caller-Id" -> "suoritusrekisteri.suoritusrekisteri.backend")
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

  def retryable(t: Throwable): Boolean = t match {
    case t: TimeoutException => true
    case t: ConnectException => true
    case PreconditionFailedException(_, code) if code >= 500 => true
    case _ => false
  }

  private def tryClient[A <: AnyRef: Manifest](url: String)(acceptedResponseCode: Int, maxRetries: Int, retryCount: AtomicInteger): Future[A] =
    client.request[A, A](url)(JsonExtractor.handler[A](acceptedResponseCode)).recoverWith {
      case t: ExecutionException if t.getCause != null && retryable(t.getCause) =>
        if (retryCount.getAndIncrement <= maxRetries) {
          logger.warning(s"retrying request to $url due to $t, retry attempt #${retryCount.get - 1}")
          Future { Thread.sleep(1000) }.flatMap(u => tryClient(url)(acceptedResponseCode, maxRetries, retryCount))
        } else Future.failed(t)
    }

  def result(t: Try[_]): String = t match {
    case Success(_) => "success"
    case Failure(e) => s"failure: $e"
  }

  def logLongQuery(f: Future[_], url: String) = {
    val t0 = Platform.currentTime
    f.onComplete(t => {
      val took = Platform.currentTime - t0
      if (took > config.httpClientSlowRequest) {
        logger.warning(s"slow request: url $url took $took ms to complete, result was ${result(t)}")
      }
    })
  }

  def readObject[A <: AnyRef: Manifest](uriKey: String, args: AnyRef*)(acceptedResponseCode: Int = 200, maxRetries: Int = 0): Future[A] = {
    val url1: String = ophProperties.url(uriKey, args:_*)
    readObjectFromUrl(url1, acceptedResponseCode, maxRetries)
  }

  def readObjectFromUrl[A <: AnyRef : Manifest](url: String, acceptedResponseCode: Int, maxRetries: Int): Future[A] = {
    val retryCount = new AtomicInteger(1)
    val result = tryClient[A](url)(acceptedResponseCode, maxRetries, retryCount)
    logLongQuery(result, url)
    result
  }

  def postObject[A <: AnyRef: Manifest, B <: AnyRef: Manifest](uriKey: String, args: AnyRef*)(acceptedResponseCode: Int = 200, resource: A): Future[B] = {
    val url = ophProperties.url(uriKey, args:_*)
    val result = client.request[A, B](url)(JsonExtractor.handler[B](acceptedResponseCode), Some(resource))
    logLongQuery(result, url)
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
        val t = new Thread(r, poolName + "-" + threadNumber.getAndIncrement)
        t.setDaemon(true)
        t
      }
    })

    ExecutionContext.fromExecutorService(pool)
  }
}

object JsonExtractor extends HakurekisteriJsonSupport {
  def handler[T: Manifest](codes: Int*) = {
    val f: (Res) => T = (resp: Res) => {
      import org.json4s.jackson.Serialization.read
      if (manifest[T] == manifest[String]) resp.getResponseBody.asInstanceOf[T]
      else read[T](new InputStreamReader(resp.getResponseBodyAsStream))
    }
    new FunctionHandler[T](f) {
      override def onStatusReceived(status: HttpResponseStatus) = {
        if (codes.contains(status.getStatusCode))
          super.onStatusReceived(status)
        else
          throw PreconditionFailedException(s"precondition failed for url: ${status.getUrl}, response code: ${status.getStatusCode}", status.getStatusCode)
      }
    }
  }
}
