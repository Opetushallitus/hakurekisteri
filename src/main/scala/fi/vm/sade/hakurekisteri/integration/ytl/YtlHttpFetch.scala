package fi.vm.sade.hakurekisteri.integration.ytl

import java.io
import java.io.{ByteArrayInputStream, InputStream}
import java.text.SimpleDateFormat
import java.util.zip.ZipInputStream

import com.google.common.io.ByteStreams
import fi.vm.sade.hakurekisteri.integration.ytl.Student.StudentAsyncParser
import fi.vm.sade.hakurekisteri.tools.Zip
import fi.vm.sade.javautils.httpclient._
import fi.vm.sade.javautils.httpclient.apache.{ApacheHttpClientBuilder, ApacheOphHttpClient}
import fi.vm.sade.properties.OphProperties
import org.apache.commons.io.IOUtils
import org.apache.http.auth.{AuthScope, UsernamePasswordCredentials}
import org.apache.http.impl.auth.BasicScheme
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.protocol.HttpContext
import org.apache.http.{HttpRequest, HttpRequestInterceptor}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

class PreemptiveAuthInterceptor(creds: UsernamePasswordCredentials) extends HttpRequestInterceptor {
  override def process(request: HttpRequest, context: HttpContext): Unit =
    request.addHeader(new BasicScheme().authenticate(creds, request, context))
}

class YtlHttpFetch(
  config: OphProperties,
  fileSystem: YtlFileSystem,
  builder: ApacheHttpClientBuilder = ApacheOphHttpClient.createCustomBuilder()
) {
  val log = LoggerFactory.getLogger(this.getClass)
  import scala.language.implicitConversions
  implicit val formats = Student.formatsStudent
  val chunkSize = config.getOrElse("ytl.http.chunksize", "50000").toInt
  val username = config.getProperty("ytl.http.username")
  val password = config.getProperty("ytl.http.password")
  val bufferSize = config.getOrElse("ytl.http.buffersize", "4096").toInt // 4K
  val credentials = new UsernamePasswordCredentials(username, password)

  private def buildClient(a: ApacheHttpClientBuilder) = {
    val provider = new BasicCredentialsProvider()
    val scope = new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT, AuthScope.ANY_REALM)
    provider.setCredentials(scope, credentials)
    a.getHttpBuilder.setDefaultCredentialsProvider(provider)
    a.getHttpBuilder.disableAutomaticRetries()
    a.getHttpBuilder.addInterceptorFirst(new PreemptiveAuthInterceptor(credentials))
    val client = a.buildOphClient("ytlHttpClient", config)
    client.retryOnError(3)
  }

  val client = buildClient(builder)

  def zipToStudents(z: ZipInputStream): Iterator[(String, Student)] = {
    streamToStudents(Zip.toInputStreams(z))
  }

  def zipToStudents(z: Iterator[ZipInputStream]): Iterator[(String, Student)] = {
    streamToStudents(Zip.toInputStreams(z))
  }

  def streamToStudents(streams: Iterator[InputStream]): Iterator[(String, Student)] =
    streams.flatMap(input => {
      val parser = StudentAsyncParser()
      val data = new Array[Byte](bufferSize)

      Iterator
        .continually(ByteStreams.read(input, data, 0, data.length))
        .takeWhile(_ != 0)
        .flatMap {
          case x if x == data.length => safeParseStudentsFromBytes(parser, data)
          case x if x < data.length =>
            val newarr = new Array[Byte](x)
            Array.copy(data, 0, newarr, 0, x)
            safeParseStudentsFromBytes(parser, newarr)
        }
    })

  private def safeParseStudentsFromBytes(
    parser: StudentAsyncParser,
    data: Array[Byte]
  ): Seq[(String, Student)] = {
    parser.feedChunk(data).flatMap {
      case (json, Success(student)) => Some(json, student)
      case (json, Failure(e)) =>
        log.error(s"Unable to parse student from YTL data! ${e.getMessage} , json: $json")
        None
    }
  }

  def fetchOne(hetu: String): Option[(String, Student)] =
    client
      .get("ytl.http.host.fetchone", hetu)
      .expectStatus(200, 404)
      .execute((r: OphHttpResponse) => {
        r.getStatusCode match {
          case 404 => None
          case 200 =>
            val json = r.asText()
            Some(json, parse(json).extract[Student])
        }
      })

  private def internalFetch(
    groupUuid: String
  )(hetus: Seq[String]): Either[Throwable, (ZipInputStream, Iterator[Student])] = {
    for {
      operation <- fetchOperation(hetus).right
      ok <- fetchStatus(operation.operationUuid).right
      zip <- downloadZip(groupUuid)(operation.operationUuid).right
    } yield {
      val z = new ZipInputStream(zip)
      (z, zipToStudents(z).map(_._2))
    }
  }

  def fetch(
    groupUuid: String,
    hetus: Seq[String]
  ): Iterator[Either[Throwable, (ZipInputStream, Iterator[Student])]] =
    hetus.grouped(chunkSize).map(internalFetch(groupUuid))

  @tailrec
  private def fetchStatus(uuid: String): Either[Throwable, Status] = {
    log.debug(s"Fetching with opertationUuid $uuid")
    implicit val formats = new DefaultFormats {
      override def dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
    } + StatusDeserializer
    Try(
      client
        .get("ytl.http.host.status", uuid)
        .expectStatus(200)
        .execute((r: OphHttpResponse) => {
          parse(r.asText()).extract[Status]
        })
    ) match {
      case Success(e: InProgress) => {
        Thread.sleep(1000)
        log.debug(e.toString)
        fetchStatus(uuid)
      }
      case Success(e: Finished) => Right(e)
      case Success(e: Failed) =>
        Left(new RuntimeException(s"Polling YTL service returned failed status ${e}"))
      case Failure(e) => Left(e)
      case status     => Left(new RuntimeException(s"Unknown status ${status}"))
    }
  }

  def downloadZip(groupUuid: String)(uuid: String): Either[Throwable, InputStream] = {
    Try[InputStream] {
      log.info(s"Making request to YTL for uuid $uuid ...")
      client
        .get("ytl.http.host.download", uuid)
        .expectStatus(200)
        .execute((r: OphHttpResponse) => {
          val responseContent: Array[Byte] = IOUtils.toByteArray(r.asInputStream())
          log.info(
            s"Read ${responseContent.length} bytes from YTL for uuid $uuid . Storing to ${fileSystem.getClass.getSimpleName} and returning for processing."
          )
          fileSystem.write(groupUuid, uuid)(new ByteArrayInputStream(responseContent))
          new ByteArrayInputStream(responseContent)
        })
    }.toEither
  }

  def fetchOperation(hetus: Seq[String]): Either[Throwable, Operation] = {
    Try[Operation](
      client
        .post("ytl.http.host.bulk")
        .dataWriter(
          "application/json",
          "UTF-8",
          new OphRequestPostWriter() {
            override def writeTo(writer: io.Writer): Unit = writer.write(write(hetus))
          }
        )
        .expectStatus(200)
        .execute((r: OphHttpResponse) => parse(r.asText()).extract[Operation])
    ) match {
      case Success(e) => {
        log.info(s"Got operation uuid ${e.operationUuid}")
        Right(e)
      }
      case Failure(e) => Left(e)
    }
  }

  private implicit def function0ToRunnable[U](
    f: (OphHttpResponse) => U
  ): OphHttpResponseHandler[U] =
    new OphHttpResponseHandler[U] {
      override def handleResponse(ophHttpResponse: OphHttpResponse): U = f(ophHttpResponse)
    }
}
