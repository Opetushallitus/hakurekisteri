package fi.vm.sade.hakurekisteri.integration

import java.io.InterruptedIOException
import java.net.URL
import java.util.concurrent.{ThreadFactory, Executors}
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import com.stackmob.newman.{ApacheHttpClient, HttpClient}
import com.stackmob.newman.dsl._
import com.stackmob.newman.response.{HttpResponseCode, HttpResponse}
import fi.vm.sade.hakurekisteri.integration.cas.CasClient
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.apache.http.conn.ClientConnectionManager
import org.apache.http.impl.NoConnectionReuseStrategy
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.impl.conn.PoolingClientConnectionManager
import org.apache.http.params.HttpConnectionParams
import org.slf4j.LoggerFactory

import scala.compat.Platform
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.Try

case class PreconditionFailedException(message: String, responseCode: HttpResponseCode) extends Exception(message)

case class ServiceConfig(casUrl: Option[String] = None,
                         serviceUrl: String,
                         user: Option[String] = None,
                         password: Option[String] = None)

class VirkailijaRestClient(config: ServiceConfig, jSessionIdStorage: Option[ActorRef] = None)(implicit val httpClient: HttpClient, implicit val ec: ExecutionContext) extends HakurekisteriJsonSupport {

  implicit val defaultTimeout: Timeout = 60.seconds

  val casUrl: Option[String] = config.casUrl
  val serviceUrl: String = config.serviceUrl
  val user: Option[String] = config.user
  val password: Option[String] = config.password

  val logger = LoggerFactory.getLogger(getClass)
  val casClient = new CasClient(casUrl, serviceUrl, user, password)

  def logConnectionFailure[T](f: Future[T], url: URL) = f.onFailure {
    case t: InterruptedIOException => logger.warn(s"connection error calling url [$url]: $t")
    case t: JSessionIdCookieException => logger.warn(t.getMessage)
  }

  val cookieExpirationMillis = 5.minutes.toMillis

  def executeGet(uri: String): Future[(HttpResponse, Option[String])]= {
    val url = new URL(serviceUrl + uri)
    def executeUnauthorized: Future[(HttpResponse, Option[String])] = {
      val f = GET(url).apply.map((_, None))
      logConnectionFailure(f, url)
      f
    }
    def executeWithJSession(sessionId: String): Future[(HttpResponse, Option[String])] = {
      val cookie = s"${JSessionIdCookieParser.name}=$sessionId"
      val f = GET(url).addHeaders("Cookie" -> cookie).apply.map((_, Some(cookie)))
      logConnectionFailure(f, url)
      f
    }
    def saveJSessionId(r: HttpResponse) {
      if (jSessionIdStorage.isDefined) r.headers match {
        case Some(headerList) => headerList.list.find((t) => t._1 == "Set-Cookie") match {
          case Some((_, cookie)) if cookie.startsWith(JSessionIdCookieParser.name) =>
            jSessionIdStorage.get ! SaveJSessionId(JSessionKey(serviceUrl), JSessionIdCookieParser.fromString(cookie))

          case None => None

        }
        case None => None

      }
    }
    def getJSessionId: Future[Option[JSessionId]] = jSessionIdStorage match {
      case Some(actor) =>
        (actor ? JSessionKey(serviceUrl)).mapTo[Option[JSessionId]]

      case None =>
        Future.successful(None)
    }
    def executeWithTicket: Future[(HttpResponse, Option[String])] = {
      casClient.getProxyTicket.flatMap((ticket) => {
        val f = GET(url).addHeaders("CasSecurityTicket" -> ticket).apply.map(r => {
          saveJSessionId(r)
          (r, Some(ticket))
        })
        logConnectionFailure(f, url)
        f
      })
    }
    (user, password) match {
      case (None, None) =>
        executeUnauthorized
      case (Some(u), Some(p)) =>
        getJSessionId.flatMap {
          case Some(JSessionId(created, sessionId)) if created + cookieExpirationMillis > Platform.currentTime =>
            executeWithJSession(sessionId)
          case _ =>
            executeWithTicket
        }
      case _ => throw new IllegalArgumentException("either user or password is not defined")
    }
  }

  def tryReadBody[A <: AnyRef: Manifest](response: HttpResponse): Try[A] = {
    import org.json4s.jackson.Serialization.read
    Try(read[A](response.bodyString))
  }

  def readBody[A <: AnyRef: Manifest](response: HttpResponse): A = {
    val rawResult = tryReadBody[A](response)
    if (rawResult.isFailure) logger.warn("Failed to deserialize", rawResult.failed.get)
    rawResult.get
  }

  def readObject[A <: AnyRef: Manifest](uri: String, precondition: (HttpResponseCode) => Boolean): Future[A] = executeGet(uri).map{case (resp, auth) =>
    if (precondition(resp.code)) resp
    else throw PreconditionFailedException(s"precondition failed for url: $serviceUrl$uri, response code: ${resp.code} with ${auth.map("auth: " + _).getOrElse("no auth")}", resp.code)
  }.map(readBody[A])

  def readObject[A <: AnyRef: Manifest](uri: String, okCodes: HttpResponseCode*): Future[A] = {
    val codes = if (okCodes.isEmpty) Seq(HttpResponseCode.Ok) else okCodes
    readObject[A](uri, (code: HttpResponseCode) => codes.contains(code))
  }

  def readObject[A <: AnyRef: Manifest](uri: String, maxConnectionRetries: Int, okCodes: HttpResponseCode*): Future[A] = {
    val retryCount = new AtomicInteger(1)
    tryRead(uri, retryCount, maxConnectionRetries, okCodes)
  }

  private def tryRead[A <: AnyRef: Manifest](uri: String, retryCount: AtomicInteger, maxConnectionRetries: Int, okCodes: Seq[HttpResponseCode]): Future[A] = {
    val codes = if (okCodes.isEmpty) Seq(HttpResponseCode.Ok) else okCodes
    readObject[A](uri, (code: HttpResponseCode) => codes.contains(code)).recoverWith {
      case t: InterruptedIOException =>
        if (retryCount.getAndIncrement <= maxConnectionRetries) tryRead(uri, retryCount, maxConnectionRetries, okCodes)
        else Future.failed(t)
    }
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

object HttpClientUtil {
  def createHttpClient: HttpClient = createHttpClient("default")

  val socketTimeout = 120000
  val connectionTimeout = 10000

  private def createApacheHttpClient(maxConnections: Int): org.apache.http.client.HttpClient = {
    val connManager: ClientConnectionManager = {
      val cm = new PoolingClientConnectionManager()
      cm.setDefaultMaxPerRoute(maxConnections)
      cm.setMaxTotal(maxConnections)
      cm
    }

    val client = new DefaultHttpClient(connManager)
    val httpParams = client.getParams
    HttpConnectionParams.setConnectionTimeout(httpParams, connectionTimeout)
    HttpConnectionParams.setSoTimeout(httpParams, socketTimeout)
    HttpConnectionParams.setStaleCheckingEnabled(httpParams, false)
    HttpConnectionParams.setSoKeepalive(httpParams, false)
    client.setReuseStrategy(new NoConnectionReuseStrategy())
    client
  }

  def createHttpClient(poolName: String = "default", threads: Int = 10, maxConnections: Int = 100): HttpClient = {
    if (poolName == "default") new ApacheHttpClient(createApacheHttpClient(maxConnections))()
    else {
      val threadNumber = new AtomicInteger(1)
      val pool = Executors.newFixedThreadPool(threads, new ThreadFactory() {
        override def newThread(r: Runnable): Thread = {
          new Thread(r, poolName + "-" + threadNumber.getAndIncrement)
        }
      })
      new ApacheHttpClient(createApacheHttpClient(maxConnections))(ExecutionContext.fromExecutorService(pool))
    }
  }
}