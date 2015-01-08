package fi.vm.sade.hakurekisteri.integration

import com.ning.http.client._
import java.io.{ByteArrayInputStream, InputStream, OutputStream}
import java.nio.ByteBuffer
import java.util
import com.ning.http.client.cookie.{Cookie, CookieDecoder}

import scala.concurrent.{Promise, Await, ExecutionContext, Future}
import scala.concurrent.duration._
import java.util.concurrent.{Callable, Executor}
import java.net.URI
import org.hamcrest.{BaseMatcher, Description, Matcher}
import org.mockito.Matchers
import scala.util.Try
import scala.language.implicitConversions


trait DispatchSupport {
  def forUrl(url: String) = ERMatcher(Some(url), Set())

  def forUrl(url: String, body: String) = ERMatcher(Some(url), Set(body))

  implicit def matcherToValue[T](m:Matcher[T]):T = Matchers.argThat(m)
}

case class BaseStatus(code: Int, text: String, req: Request, prov: AsyncHttpProvider) extends HttpResponseStatus(req.getURI, prov){
  override def getProtocolText: String = ""

  override def getProtocolMinorVersion: Int = 1

  override def getProtocolMajorVersion: Int = 1

  override def getProtocolName: String = req.getURI.getScheme

  override def getStatusText: String = text

  override def getStatusCode: Int = code
}

class BaseHeaders(req: Request, prov: AsyncHttpProvider, headers: List[(String, String)]) extends HttpResponseHeaders(req.getURI, prov){

  import scala.collection.JavaConversions._

  private val values: Map[String, util.List[String]] = headers.groupBy(_._1).mapValues(_.map(_._2))
  val headerMap: FluentCaseInsensitiveStringsMap = new FluentCaseInsensitiveStringsMap(values)

  override def getHeaders: FluentCaseInsensitiveStringsMap = headerMap
}

object OkStatus {
  def apply(req: Request, prov: AsyncHttpProvider) = BaseStatus(200, "OK", req, prov)
}

class BodyString(request: Request, provider: AsyncHttpProvider, body: String = "") extends HttpResponseBodyPart(request.getURI,provider) {
  var closed = false

  override def markUnderlyingConnectionAsClosed(): Unit = {closed = true}

  override def closeUnderlyingConnection(): Boolean = closed

  override def getBodyPartBytes: Array[Byte] = body.getBytes

  override def isLast: Boolean = true

  override def writeTo(outputStream: OutputStream): Int = {
    val b = getBodyPartBytes
    outputStream.write(b)
    b.length
  }

  override def getBodyByteBuffer: ByteBuffer = ByteBuffer.wrap(getBodyPartBytes)

  override def length(): Int = getBodyPartBytes.length
}

class BaseResponse(s: HttpResponseStatus, h: HttpResponseHeaders, bs: Seq[HttpResponseBodyPart]) extends Response{
  import scala.collection.JavaConversions._

  val status = Option(s)
  val headers = Option(h)

  val bodyParts = Option(bs)

  override def hasResponseBody: Boolean = !bodyParts.flatMap(_.headOption).isEmpty

  override def hasResponseHeaders: Boolean = headers.isDefined

  override def hasResponseStatus: Boolean = status.isDefined

  override def getCookies: util.List[Cookie] = {
    for (
      header <- headers.get.getHeaders.entrySet.toList if (header.getKey.equalsIgnoreCase("Set-Cookie"));
      value <- header.getValue
    ) yield CookieDecoder.decode(value)
  }

  override def isRedirected: Boolean = (status.get.getStatusCode >= 300) && (status.get.getStatusCode <= 399)

  override def getHeaders: FluentCaseInsensitiveStringsMap = headers.get.getHeaders

  override def getHeaders(name: String): util.List[String] =
    headers.map(_.getHeaders.get(name)).getOrElse(List[String]())

  override def getHeader(name: String): String =
    headers.map(_.getHeaders.getFirstValue(name)).getOrElse(null)

  override def getContentType: String = getHeader("Content-Type")

  override def getUri: URI = status.get.getUrl

  lazy val bodyBytes = bodyParts.get.map(_.getBodyPartBytes).reduce(_ ++ _)

  lazy val bodyString = new String(getResponseBodyAsBytes)

  override def getResponseBody: String = bodyString

  override def getResponseBodyExcerpt(maxLength: Int): String = if (bodyString.length <= maxLength) bodyString else bodyString.substring(0, maxLength)

  override def getResponseBody(charset: String): String = new String(getResponseBodyAsBytes, charset)

  override def getResponseBodyExcerpt(maxLength: Int, charset: String): String =  {
    val body = getResponseBody(charset)
    if (body.length <= maxLength) body else body.substring(0, maxLength)
  }

  override def getResponseBodyAsStream: InputStream = new ByteArrayInputStream(bodyBytes)

  override def getResponseBodyAsByteBuffer: ByteBuffer = ByteBuffer.wrap(bodyBytes)

  override def getResponseBodyAsBytes: Array[Byte] =  bodyBytes

  override def getStatusText: String = status.get.getStatusText

  override def getStatusCode: Int = status.get.getStatusCode
}

case class EndpointRequest(url: String, body: Option[String], headers: List[(String, String)])

case class ERMatcher(url: Option[String], bodyParts: Set[String], headers: (String, String)*) extends BaseMatcher[EndpointRequest] {
  override def describeTo(description: Description): Unit = {
    val matchedHeaders  = headers.headOption.map((_) => "following headers: " + headers.mkString(", ")).getOrElse("any headers")
    description.appendText(s"request with ${url.map("url: " + _).getOrElse("any url")} and $matchedHeaders")
  }

  override def matches(item: scala.Any): Boolean = item match {
    case EndpointRequest(rUrl, body, rHeaders) =>
      url.map(_ == rUrl).getOrElse(true) &&
      headers.map(rHeaders.contains).reduceOption(_ && _).getOrElse(true) &&
      !bodyParts.exists(!body.getOrElse("").contains(_))

    case _ => false
  }

  def withHeader(header: (String,String))  = ERMatcher(url,bodyParts, (header +: headers):_*)

  def withBodyPart(part: String) = ERMatcher(url, bodyParts + part, headers:_*)
}

trait Endpoint {
  def request(er: EndpointRequest): (Int, List[(String, String)], String)
}

class CapturingProvider(endpoint: Endpoint) extends AsyncHttpProvider{
  override def prepareResponse(status: HttpResponseStatus, headers: HttpResponseHeaders, bodyParts: util.List[HttpResponseBodyPart]): Response = {
    import scala.collection.JavaConversions._
    new BaseResponse(status, headers, bodyParts)
  }

  override def close(): Unit = {}

  override def execute[T](request: Request, handler: AsyncHandler[T]): ListenableFuture[T] = {
    import scala.collection.JavaConversions._

    val foo = for (
      entry <- request.getHeaders.entrySet;
      value <- entry.getValue
    ) yield entry.getKey -> value

    val reqBody = Option(request.getStringData).orElse(Option(request.getByteData).map(new String(_)))

    val er = EndpointRequest(request.getUrl, reqBody, foo.toList)

    val response = Option(endpoint.request(er))

    def handle = {
      val (status, headers, body) = response.getOrElse(404, List(), "Not found")
      handler.onStatusReceived(BaseStatus(status, "", request, this))
      handler.onHeadersReceived(new BaseHeaders(request, this, headers))
      handler.onBodyPartReceived(new BodyString(request, this, body))
      handler.onCompleted()
    }

    val promise = Promise[T]()
    promise.tryComplete(Try(handle))

    FutureListenableFuture(promise.future)
  }
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

  override def getAndSetWriteBody(writeBody: Boolean): Boolean = ???

  override def getAndSetWriteHeaders(writeHeader: Boolean): Boolean = ???

  override def touch(): Unit = ???

  override def content(v: T): Unit = ???

  override def abort(t: Throwable): Unit = ???

  override def done(): Unit = ???
}

