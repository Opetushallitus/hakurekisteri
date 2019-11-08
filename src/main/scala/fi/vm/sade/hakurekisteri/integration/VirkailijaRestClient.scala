package fi.vm.sade.hakurekisteri.integration

import java.io.InputStreamReader
import java.net.ConnectException
import java.nio.charset.Charset
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorSystem, Props}
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout
import dispatch.{FunctionHandler, Http, HttpExecutor, Req, Res}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.scalaproperties.OphProperties
import io.netty.handler.codec.http.cookie.{Cookie, DefaultCookie}
import org.asynchttpclient._
import org.asynchttpclient.filter.ThrottleRequestFilter

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService, Future}
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}


case class PreconditionFailedException(message: String, responseCode: Int) extends Exception(message)

class HttpConfig(properties: Map[String, String] = Map.empty) {
  val httpClientConnectionTimeout = properties.getOrElse("suoritusrekisteri.http.client.connection.timeout.ms", "10000").toInt
  val httpClientRequestTimeout = properties.getOrElse("suoritusrekisteri.http.client.request.timeout.ms", "6000000").toInt
  val httpClientPooledConnectionIdleTimeout = properties.getOrElse("suoritusrekisteri.http.client.connection.idle.timeout.ms", "59001").toInt
  val httpClientMaxRetries = properties.getOrElse("suoritusrekisteri.http.client.max.retries", "1").toInt
  val httpClientSlowRequest = properties.getOrElse("suoritusrekisteri.http.client.slow.request.ms", "1000").toLong
  val useNativeTransport: Boolean = properties.getOrElse("suoritusrekisteri.http.client.use.native.transport", "false").toBoolean
}

case class ServiceConfig(casUrl: Option[String] = None,
                         serviceUrl: String,
                         user: Option[String] = None,
                         password: Option[String] = None,
                         properties: Map[String, String] = Map.empty,
                         maxSimultaneousConnections: Int = 50,
                         maxConnectionQueueMs: Int = 60000) extends HttpConfig(properties)

object OphUrlProperties extends OphProperties("/suoritusrekisteri-oph.properties") {
  addOptionalFiles(Paths.get(sys.props.getOrElse("user.home", ""), "/oph-configuration/common.properties").toString)
}

class VirkailijaRestClient(config: ServiceConfig, aClient: Option[AsyncHttpClient] = None,
                           jSessionName: String = "JSESSIONID", serviceUrlSuffix: String = "/j_spring_cas_security_check")
                          (implicit val ec: ExecutionContext, val system: ActorSystem) extends HakurekisteriJsonSupport {
  private implicit val defaultTimeout: Timeout = 60.seconds

  private val serviceUrl: String = config.serviceUrl
  private val user = config.user
  private val password = config.password
  private val logger = Logging.getLogger(system, this)
  private val serviceName = serviceUrl.split("/").lastOption.getOrElse(UUID.randomUUID())

  private val internalClient: HttpExecutor = {
    aClient match {
      case Some(asyncHttpClient) => new HttpExecutor {
        override def client: AsyncHttpClient = asyncHttpClient
        logger.info("client cookies: " + client.getConfig.getCookieStore.getAll.toString)
        client
      }
      case None =>
        logger.info("NEW HTTP WITH CONFIGURATION")
        var temp = Http.withConfiguration(_.
        setConnectTimeout(config.httpClientConnectionTimeout).
        setRequestTimeout(config.httpClientRequestTimeout).
        setReadTimeout(config.httpClientRequestTimeout).
        setPooledConnectionIdleTimeout(config.httpClientPooledConnectionIdleTimeout).
        setFollowRedirect(true).
        setMaxRequestRetry(2).
        setUseNativeTransport(config.useNativeTransport).
        addRequestFilter(new ThrottleRequestFilter(config.maxSimultaneousConnections, config.maxConnectionQueueMs)))
        logger.info("COOKIES FOR NEW HTTP CONFIG: " + temp.client.getConfig.getCookieStore.getAll.toString)
        temp
    }
  }

  val configToLog = config.copy(password = Some("*****"), properties = Map("properties" -> "censored"))
  logger.info(s"Initialized internal http client of class ${internalClient.getClass} with config $configToLog")
  private lazy val casActor = system.actorOf(Props(new CasActor(config, aClient, jSessionName, serviceUrlSuffix)), s"$serviceName-cas-client-pool-${new SecureRandom().nextLong().toString}")

  object Client {
    private def jSessionId: Future[JSessionId] = (casActor ? GetJSession).mapTo[JSessionId]
    def refreshJSessionId: Future[JSessionId] = (casActor ? RefreshJSession).mapTo[JSessionId]

    import org.json4s.jackson.Serialization._

    private def addCookies(request: Req, cookies: Seq[Cookie]): Req = {
      logger.info("method: " + request.toRequest.getMethod)
      var temp = cookies.foldLeft[Req](request)((req, cookie) => req.addOrReplaceCookie(cookie))
      logger.info("cookies after cookies set: " + temp.toRequest.getCookies.toString)
      temp
    }

    def request[A <: AnyRef: Manifest, B <: AnyRef: Manifest](url: String, basicAuth: Boolean = false)(handler: AsyncHandler[B], body: Option[A] = None): dispatch.Future[B] = {
      val request = dispatch.url(url) <:< Map("Caller-Id" -> Config.callerId)
      val cookies = new scala.collection.mutable.ListBuffer[Cookie]()

      val requestWithPostHeaders = body match {
        case Some(jsonBody) =>

          var test = (request << write[A](jsonBody)(jsonFormats)).setContentType("application/json", Charset.forName("UTF-8")) <:< Map("CSRF" -> "suoritusrekisteri")
          logger.info("REQUEST WITH POST HEADERS COOKIES: " +request.toRequest.getCookies)
          test
        case None => request
      }

      internalClient.client.getConfig.getCookieStore.remove(_.name().toLowerCase == jSessionName.toLowerCase)
      (user, password, basicAuth) match{
        case (Some(un), Some(pw), false) =>
          for (
            jsession <- jSessionId;
            result <- {
              cookies += new DefaultCookie(jSessionName, jsession.sessionId)
              logger.info("BEFORE REQUEST WITH COOKIES COOKIES: " + cookies.toString())
              cookies += new DefaultCookie("CSRF", "suoritusrekisteri")
              val requestWithCookies = addCookies(requestWithPostHeaders, cookies).toRequest
              logger.info("case (Some(un), Some(pw), false) request cookies: " + requestWithCookies.getCookies.toString)
              internalClient(requestWithCookies, handler)
            }
          ) yield result
        case (Some(un), Some(pw), true) =>
          for (
            result <- {
              cookies += new DefaultCookie("CSRF", "suoritusrekisteri")
              val request1 = addCookies(requestWithPostHeaders, cookies).as_!(un, pw).toRequest
              logger.info("case (Some(un), Some(pw), true) request cookies: " + request1.getCookies.toString)

              internalClient(request1, handler)
            }
          ) yield result
        case _ =>
          cookies += new DefaultCookie("CSRF", "suoritusrekisteri")
          val request2 = addCookies(requestWithPostHeaders, cookies).toRequest
          logger.info("case _ request cookies: " + request2.getCookies.toString)
          for (
            result <- internalClient(request2, handler)
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

  private def tryClient[A <: AnyRef: Manifest](url: String, basicAuth: Boolean = false)(acceptedResponseCodes: Seq[Int], maxRetries: Int, retryCount: AtomicInteger): Future[A] =
    Client.request[A, A](url, basicAuth)(JsonExtractor.handler[A](acceptedResponseCodes:_*)).recoverWith {
      case j: ExecutionException if j.getCause.getCause.isInstanceOf[JSessionIdCookieException] =>
        logger.warning("Expired jsession, fetching new JSessionId")
        Client.refreshJSessionId.flatMap (_ match {
          case j: JSessionId =>
            logger.warning(s"Retrying request to $url due to expired JSessionId.")
            Client.request[A, A](url, basicAuth)(JsonExtractor.handler[A](acceptedResponseCodes:_*)).recoverWith {
              case t: Exception if retryable(t) || (t.getCause != null && retryable(t.getCause)) =>
                if (retryCount.getAndIncrement <= maxRetries) {
                  logger.warning(s"Retrying request to $url due to $t, retry attempt #${retryCount.get - 1}")
                  Future { Thread.sleep(1000) }.flatMap(u => tryClient(url, basicAuth)(acceptedResponseCodes, maxRetries, retryCount))
                } else Future.failed(t)
            }
          case _ =>
            Future.failed {
              logger.error(s"Fetching jsession for $serviceUrl failed")
              new RuntimeException(s"Fetching jsession for $serviceUrl failed")
            }
        })
      case t: Exception if retryable(t) || (t.getCause != null && retryable(t.getCause)) =>
        if (retryCount.getAndIncrement <= maxRetries) {
          logger.warning(s"Retrying request to $url due to $t, retry attempt #${retryCount.get - 1}")
          Future { Thread.sleep(1000) }.flatMap(u => tryClient(url, basicAuth)(acceptedResponseCodes, maxRetries, retryCount))
        } else Future.failed(t)
    }

  private def tryPostClient[A <: AnyRef: Manifest, B <: AnyRef: Manifest](url: String, basicAuth: Boolean = false)(acceptedResponseCodes: Seq[Int], maxRetries: Int, retryCount: AtomicInteger, resource: A): Future[B] =
    Client.request[A, B](url, basicAuth)(JsonExtractor.handler[B](acceptedResponseCodes:_*), Some(resource)).recoverWith {
      case j: ExecutionException if j.getCause.getCause.isInstanceOf[JSessionIdCookieException] =>
          logger.warning("Expired jsession, fetching new JSessionId")
          Client.refreshJSessionId.flatMap (_ match {
            case j: JSessionId =>
              logger.warning(s"Retrying request to $url due to expired JSessionId.")
              Client.request[A, B](url, basicAuth)(JsonExtractor.handler[B](acceptedResponseCodes:_*), Some(resource)).recoverWith {
                case t: Exception if retryable(t) || (t.getCause != null && retryable(t.getCause)) =>
                  if (retryCount.getAndIncrement <= maxRetries) {
                    logger.warning(s"Retrying request to $url due to $t, retry attempt #${retryCount.get - 1}")
                    Future { Thread.sleep(1000) }.flatMap(u => tryPostClient(url, basicAuth)(acceptedResponseCodes, maxRetries, retryCount, resource))
                  } else Future.failed(t)
              }
            case _ =>
              Future.failed {
                logger.error(s"Fetching jsession for $serviceUrl failed")
                new RuntimeException(s"Fetching jsession for $serviceUrl failed")
              }
          })
      case t: Exception if retryable(t) || (t.getCause != null && retryable(t.getCause)) =>
        if (retryCount.getAndIncrement <= maxRetries) {
          logger.warning(s"Retrying request to $url due to $t, retry attempt #${retryCount.get - 1}")
          Future { Thread.sleep(1000) }.flatMap(u => tryPostClient(url, basicAuth)(acceptedResponseCodes, maxRetries, retryCount, resource))
        } else Future.failed(t)
    }

  private def result(t: Try[_]): String = t match {
    case Success(_) => "success"
    case Failure(e) => s"failure: $e"
  }

  private def logLongQuery(f: Future[_], url: String) = {
    val t0 = Platform.currentTime
    f.onComplete(t => {
      val took = Platform.currentTime - t0
      if (took > config.httpClientSlowRequest) {
        logger.warning(s"slow request: url $url took $took ms to complete, result was ${result(t)}, " +
          s"parameters: connectTimeout=${internalClient.client.getConfig.getConnectTimeout}, " +
          s"requestTimeout=${internalClient.client.getConfig.getRequestTimeout}, " +
          s"pooledConnectionIdleTimeout=${internalClient.client.getConfig.getPooledConnectionIdleTimeout}")
      }
    })
  }

  def readObject[A <: AnyRef: Manifest](uriKey: String, args: AnyRef*)(acceptedResponseCode: Int = 200, maxRetries: Int = 0): Future[A] = {
    readObjectWithCodes[A](uriKey, Seq(acceptedResponseCode), maxRetries, args:_*)
  }

  def readObjectWithCodes[A <: AnyRef: Manifest](uriKey: String, acceptedResponseCodes: Seq[Int], maxRetries: Int, args: AnyRef*): Future[A] = {
    val url1: String = OphUrlProperties.url(uriKey, args:_*)
    readObjectFromUrl(url1, acceptedResponseCodes, maxRetries)
  }

  def readObjectWithBasicAuth[A <: AnyRef: Manifest](uriKey: String, args: AnyRef*)(acceptedResponseCode: Int = 200, maxRetries: Int = 0): Future[A] = {
    val url1: String = OphUrlProperties.url(uriKey, args:_*)
    //logger.info(s"Tehdään rajapintakutsu: " + url1)
    readObjectFromUrl(url1, Seq(acceptedResponseCode), maxRetries, true)
  }

  def readObjectFromUrl[A <: AnyRef : Manifest](url: String, acceptedResponseCodes: Seq[Int], maxRetries: Int = 0, basicAuth: Boolean = false): Future[A] = {
    val retryCount = new AtomicInteger(1)
    val result = tryClient[A](url, basicAuth)(acceptedResponseCodes, maxRetries, retryCount)
    logLongQuery(result, url)
    result
  }

  def postObject[A <: AnyRef: Manifest, B <: AnyRef: Manifest](uriKey: String, args: AnyRef*)(acceptedResponseCode: Int = 200, resource: A, basicAuth: Boolean = false): Future[B] = {
    postObjectWithCodes[A,B](uriKey, Seq(acceptedResponseCode), 0, resource, basicAuth, args:_*)
  }

  def postObjectWithCodes[A <: AnyRef: Manifest, B <: AnyRef: Manifest](uriKey: String, acceptedResponseCodes: Seq[Int], maxRetries: Int, resource: A, basicAuth: Boolean, args: AnyRef*): Future[B] = {
    val retryCount = new AtomicInteger(1)
    val url = OphUrlProperties.url(uriKey, args:_*)
    val result = tryPostClient[A, B](url, basicAuth)(acceptedResponseCodes, maxRetries, retryCount, resource)
    logger.info("tryPostClient: " + result.toString)
    logLongQuery(result, url)
    result
  }
}

case class JSessionIdCookieException(m: String) extends Exception(m)

object JSessionIdCookieParser {

  def isJSessionIdCookie(cookie: String, jSessionName: String): Boolean = {
    cookie.startsWith(jSessionName)
  }

  def fromString(cookie: String, jSessionName: String): JSessionId = {
    if (!isJSessionIdCookie(cookie, jSessionName)) throw JSessionIdCookieException(s"not a JSESSIONID cookie: $cookie")

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
  def createExecutor(threads: Int, poolName: String): ExecutionContextExecutorService = {
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
  def handler[T: Manifest](codes: Int*)(implicit system: ActorSystem) = {
    val f: (Res) => T = (resp: Res) => {
      import org.json4s.jackson.Serialization.read
      if (manifest[T] == manifest[String]) {
        resp.getResponseBody.asInstanceOf[T]
      } else {
        val reader = new InputStreamReader(resp.getResponseBodyAsStream)
        Try(read[T](reader)).recover {
          case t: Throwable =>
            val logger = Logging.getLogger(system, this)
            logger.error(s"Error when parsing data from ${resp.getUri}: ${t.getMessage}")
            reader.close()
            throw t
        }.get
      }
    }
    new FunctionHandler[T](f) {
      override def onCompleted(response: Res): T = {
        if (isRedirectToCasLogin(response)) {
          throw new JSessionIdCookieException("JSESSIONID is expired")
        } else if (codes.contains(response.getStatusCode)) {
            super.onCompleted(response)
        } else {
          throw PreconditionFailedException(s"precondition failed for url: ${response.getUri}, status code: ${response.getStatusCode}, body: ${response.getResponseBody(Charset.forName("utf-8"))}", response.getStatusCode)
        }
      }

      private def isRedirectToCasLogin(response: Res): Boolean = response.getUri.getPath.equals("/cas/login")
    }
  }
}
