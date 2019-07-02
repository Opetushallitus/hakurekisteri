package fi.vm.sade.hakurekisteri.integration

import java.io.{ByteArrayInputStream, InputStream, OutputStream}
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util
import java.util.Map
import java.util.concurrent.{CompletableFuture, Executor}
import java.util.function.Predicate

import akka.actor.Scheduler
import io.netty.handler.codec.http.cookie.{ClientCookieDecoder, Cookie, DefaultCookie}
import io.netty.handler.codec.http.{DefaultHttpHeaders, EmptyHttpHeaders, HttpHeaders}
import org.asynchttpclient._
import org.asynchttpclient.uri.Uri
import org.hamcrest.{BaseMatcher, Description, Matcher}
import org.mockito.hamcrest.MockitoHamcrest

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.implicitConversions
import scala.util.Try
import scala.util.matching.Regex


trait DispatchSupport {
  def forUrl(url: String) = ERMatcher(Some(url), Set(), Set())
  def forPattern(url: String) = ERPatternMatcher(url.r, Set(), Set())

  def forUrl(url: String, body: String) = ERMatcher(Some(url), Set(body), Set())

  implicit def matcherToValue[T](m:Matcher[T]):T = MockitoHamcrest.argThat(m)
}

case class BaseStatus(code: Int, text: String, req: Request) extends HttpResponseStatus(req.getUri) {
  override def getProtocolText: String = ""

  override def getProtocolMinorVersion: Int = 1

  override def getProtocolMajorVersion: Int = 1

  override def getProtocolName: String = req.getUri.getScheme

  override def getStatusText: String = text

  override def getStatusCode: Int = code

  override def getRemoteAddress: SocketAddress = ???

  override def getLocalAddress: SocketAddress = ???
}

class BaseHeaders(req: Request, headers: List[(String, String)]) extends DefaultHttpHeaders {
  headers.foreach { h => add(h._1, h._2) }
}

object OkStatus {
  def apply(req: Request) = BaseStatus(200, "OK", req)
}

class BodyString(request: Request, body: String = "") extends HttpResponseBodyPart(true) {
  var closed = false

  override def getBodyPartBytes: Array[Byte] = body.getBytes

  override def isLast: Boolean = true

  def writeTo(outputStream: OutputStream): Int = {
    val b = getBodyPartBytes
    outputStream.write(b)
    b.length
  }

  override def getBodyByteBuffer: ByteBuffer = ByteBuffer.wrap(getBodyPartBytes)

  override def length(): Int = getBodyPartBytes.length
}

class BaseResponse(s: HttpResponseStatus, h: HttpHeaders, bs: Seq[HttpResponseBodyPart]) extends Response {
  import scala.collection.JavaConverters._

  val status = Option(s)
  val headers = Option(h)

  val bodyParts = Option(bs)

  override def hasResponseBody: Boolean = !bodyParts.flatMap(_.headOption).isEmpty

  override def hasResponseHeaders: Boolean = headers.isDefined

  override def hasResponseStatus: Boolean = status.isDefined

  override def getCookies: util.List[Cookie] = {
    (for (
      header: Map.Entry[String, String] <- headers.get.entries.asScala if header.getKey.equalsIgnoreCase("Set-Cookie")
    ) yield ClientCookieDecoder.STRICT.decode(header.getValue)).asJava
  }

  override def isRedirected: Boolean = (status.get.getStatusCode >= 300) && (status.get.getStatusCode <= 399)

  override def getHeaders: HttpHeaders = headers.getOrElse(EmptyHttpHeaders.INSTANCE)

  override def getHeaders(name: CharSequence): util.List[String] =
    headers.map(_.get(name)).map(List(_)).getOrElse(List[String]()).asJava

  override def getHeader(name: CharSequence): String =
    headers.map(_.get(name)).orNull

  override def getContentType: String = getHeader("Content-Type")

  override def getUri: Uri = status.get.getUri

  lazy val bodyBytes = bodyParts.get.map(_.getBodyPartBytes).reduce(_ ++ _)

  lazy val bodyString = new String(getResponseBodyAsBytes)

  override def getResponseBody: String = bodyString

  def getResponseBodyExcerpt(maxLength: Int): String = if (bodyString.length <= maxLength) bodyString else bodyString.substring(0, maxLength)

  def getResponseBody(charset: String): String = new String(getResponseBodyAsBytes, charset)

  def getResponseBodyExcerpt(maxLength: Int, charset: String): String =  {
    val body = getResponseBody(charset)
    if (body.length <= maxLength) body else body.substring(0, maxLength)
  }

  override def getResponseBodyAsStream: InputStream = new ByteArrayInputStream(bodyBytes)

  override def getResponseBodyAsByteBuffer: ByteBuffer = ByteBuffer.wrap(bodyBytes)

  override def getResponseBodyAsBytes: Array[Byte] =  bodyBytes

  override def getStatusText: String = status.get.getStatusText

  override def getStatusCode: Int = status.get.getStatusCode

  override def getResponseBody(charset: Charset): String = new String(bodyBytes, "UTF-8")

  override def getRemoteAddress: SocketAddress = ???

  override def getLocalAddress: SocketAddress = ???
}

case class EndpointRequest(url: String, body: Option[String], headers: List[(String, String)], cookies: Set[Cookie])

abstract class EndpointMatching  extends BaseMatcher[EndpointRequest] {
  val headers:Seq[(String, String)]
  val bodyParts: Set[String]
  val cookies: Set[Cookie]
  val urlString: String

  override def describeTo(description: Description): Unit = {
    val matchedHeaders  = headers.headOption.map((_) => "following headers: " + headers.mkString(", ")).getOrElse("any headers")
    val matchedCookies = if (cookies.isEmpty) "any cookies" else s"following cookies: ${cookies.mkString(", ")}"
    description.appendText(s"request with $urlString and $matchedHeaders and $matchedCookies")
  }

  def matchesUrl(rUrl:String): Boolean

  override def matches(item: scala.Any): Boolean = item match {
    case EndpointRequest(rUrl, body, rHeaders, rCookies) =>
      val urlMatches = matchesUrl(rUrl)
      val headersMatch = headers.map(rHeaders.contains).reduceOption(_ && _).getOrElse(true)
      val bodyPartsMatch = !bodyParts.exists(!body.getOrElse("").contains(_))
      val cookiesMatch = cookies.map(expectedCookie =>
        rCookies.exists(cookieMatches(expectedCookie)))
        .reduceOption(_ && _).getOrElse(true)
      urlMatches &&
        headersMatch &&
        bodyPartsMatch &&
        cookiesMatch

    case _ => false
  }


  private def cookieMatches(expectedCookie: Cookie): Cookie => Boolean = { c =>
    c.name() == expectedCookie.name() &&
      (c.value() == expectedCookie.value())
  }
}

case class ERMatcher(url: Option[String], bodyParts: Set[String], cookies: Set[Cookie], headers: (String, String)*) extends EndpointMatching {
  val urlString: String = url.map("url: " + _).getOrElse("any url")
  def matchesUrl(rUrl:String) = url.map(_ == rUrl).getOrElse(true)

  def withHeader(header: (String,String))  = ERMatcher(url,bodyParts, cookies, (header +: headers):_*)

  def withBodyPart(part: String) = ERMatcher(url, bodyParts + part, cookies, headers:_*)

  def withCookie(cookieName: String, cookieValue: String) = ERMatcher(url, bodyParts, cookies + new DefaultCookie(cookieName, cookieValue), headers:_*)

}


case class ERPatternMatcher(url: Regex, bodyParts: Set[String], cookies: Set[Cookie], headers: (String, String)*) extends EndpointMatching {
  val urlString: String = s"url: $url"
  def matchesUrl(rUrl:String) = rUrl match {
    case url() => true
    case _ => false
  }


}


trait Endpoint {
  def request(er: EndpointRequest): (Int, List[(String, String)], String)
}

trait FutureEndpoint {

  def request(er: EndpointRequest): Future[(Int, List[(String, String)], String)]

}


class CapturingAsyncHttpClient(endpoint: Endpoint) extends AsyncHttpClient {
  def prepareResponse(status: HttpResponseStatus, headers: HttpHeaders, bodyParts: util.List[HttpResponseBodyPart]): Response = {
    import scala.collection.JavaConverters._
    new BaseResponse(status, headers, bodyParts.asScala)
  }

  def close(): Unit = {}


  override def executeRequest[T](request: Request, handler: AsyncHandler[T]): ListenableFuture[T] = FutureListenableFuture(executeScala(request, handler))

  def execute[T](request: Request, handler: AsyncHandler[T]): ListenableFuture[T] = {
    FutureListenableFuture(executeScala(request, handler))
  }

  def handle[T](handler: AsyncHandler[T], request: Request)(response: Option[(Int, List[(String, String)], String)]) =  {
    val (status, headers, body) = response.getOrElse(404, List(), "Not found")
    handler.onStatusReceived(BaseStatus(status, "", request))
    handler.onHeadersReceived(new BaseHeaders(request, headers))
    handler.onBodyPartReceived(new BodyString(request, body))
    handler.onCompleted()
  }

  def executeScala[T](request: Request, handler: AsyncHandler[T]): Future[T] = {
    implicit val ec: ExecutionContextExecutor = ExecutionContext.global
    Future { handle(handler, request)(executeRequest(request)) }
  }

  def executeRequest[T](request: Request): Option[(Int, List[(String, String)], String)] = {
    import scala.collection.JavaConverters._

    val foo: Set[(String, String)] = for (
      entry: Map.Entry[String, String] <- request.getHeaders.asScala.toSet
    ) yield entry.getKey -> entry.getValue

    val reqBody = Option(request.getStringData).orElse(Option(request.getByteData).map(new String(_)))

    val er = EndpointRequest(request.getUrl, reqBody, foo.toList, request.getCookies.asScala.toSet)

    Option(endpoint.request(er))
  }

  override def isClosed: Boolean = ???

  override def setSignatureCalculator(signatureCalculator: SignatureCalculator): AsyncHttpClient = ???

  override def prepare(method: String, url: String): BoundRequestBuilder = ???

  override def prepareGet(url: String): BoundRequestBuilder = ???

  override def prepareConnect(url: String): BoundRequestBuilder = ???

  override def prepareOptions(url: String): BoundRequestBuilder = ???

  override def prepareHead(url: String): BoundRequestBuilder = ???

  override def preparePost(url: String): BoundRequestBuilder = ???

  override def preparePut(url: String): BoundRequestBuilder = ???

  override def prepareDelete(url: String): BoundRequestBuilder = ???

  override def preparePatch(url: String): BoundRequestBuilder = ???

  override def prepareTrace(url: String): BoundRequestBuilder = ???

  override def prepareRequest(request: Request): BoundRequestBuilder = ???

  override def prepareRequest(requestBuilder: RequestBuilder): BoundRequestBuilder = ???

  override def executeRequest[T](requestBuilder: RequestBuilder, handler: AsyncHandler[T]): ListenableFuture[T] = ???

  override def executeRequest(request: Request): ListenableFuture[Response] = ???

  override def executeRequest(requestBuilder: RequestBuilder): ListenableFuture[Response] = ???

  override def getClientStats: ClientStats = ???

  override def flushChannelPoolPartitions(predicate: Predicate[AnyRef]): Unit = ???

  override def getConfig: AsyncHttpClientConfig = new DefaultAsyncHttpClientConfig.Builder().build()
}

case class FutureListenableFuture[T](future: Future[T]) extends ListenableFuture[T]{
  override def get(timeout: Long, unit: TimeUnit): T = Await.result(future, Duration(timeout, unit))

  override def get(): T = Await.result(future, Duration.Inf)

  override def isDone: Boolean = future.isCompleted

  override def isCancelled: Boolean = false

  override def cancel(mayInterruptIfRunning: Boolean): Boolean = false

  override def addListener(listener: Runnable, exec: Executor): ListenableFuture[T] = {
    future.onComplete(
      _ => listener.run()
    )(ExecutionContext.fromExecutor(exec))
    this
  }

  def getAndSetWriteBody(writeBody: Boolean): Boolean = ???

  def getAndSetWriteHeaders(writeHeader: Boolean): Boolean = ???

  override def touch(): Unit = ???

  def content(v: T): Unit = ???

  override def abort(t: Throwable): Unit = ???

  override def done(): Unit = ???

  override def toCompletableFuture: CompletableFuture[T] = ???
}


class DelayingAsyncHttpClient(endpoint: Endpoint, delay: FiniteDuration)(implicit val ec: ExecutionContext, scheduler: Scheduler) extends CapturingAsyncHttpClient(endpoint) {

  println(s"provider with $delay initialized")

  override def executeScala[T](request: Request, handler: AsyncHandler[T]): Future[T] = {
    import akka.pattern.after
    after(delay, scheduler)(super.executeScala(request, handler))


  }
}

