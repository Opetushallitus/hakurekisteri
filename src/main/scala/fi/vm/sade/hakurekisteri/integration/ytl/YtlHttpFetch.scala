package fi.vm.sade.hakurekisteri.integration.ytl

import java.io.{InputStream}
import java.text.SimpleDateFormat
import java.util.zip.{ZipInputStream}
import java.io
import fi.vm.sade.hakurekisteri.rest.support.{StatusDeserializer, KausiDeserializer, StudentDeserializer}
import fi.vm.sade.javautils.httpclient._
import fi.vm.sade.properties.OphProperties
import org.apache.commons.io.IOUtils
import org.apache.http.HttpHeaders
import org.apache.http.auth.{UsernamePasswordCredentials, AuthScope}
import org.apache.http.client.methods.HttpPost
import org.apache.http.impl.auth.BasicScheme
import org.apache.http.impl.client.BasicCredentialsProvider
import org.json4s.NoTypeHints
import org.json4s.jackson.Serialization.{write}

import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization

import scala.util.{Failure, Success, Try}

class YtlHttpFetch(config: OphProperties, fileSystem: YtlFileSystem) {
  import scala.language.implicitConversions
  implicit val formats = Serialization.formats(NoTypeHints) + new KausiDeserializer + new StudentDeserializer
  val username = config.getProperty("ytl.http.username")
  val password = config.getProperty("ytl.http.password")
  val credentials = new UsernamePasswordCredentials(username, password)
  val preemptiveBasicAuthentication = new BasicScheme().authenticate(credentials, new HttpPost(), null).getValue

  private def buildClient() = {
    val a = ApacheOphHttpClient.createCustomBuilder()
    val provider = new BasicCredentialsProvider()
    val scope = new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT, AuthScope.ANY_REALM)
    provider.setCredentials(scope, credentials)
    a.httpBuilder.setDefaultCredentialsProvider(provider)
    a.httpBuilder.disableAutomaticRetries()
    val client = a.buildOphClient("ytlHttpClient", config)
    client
  }

  val client = buildClient()

  def zipToStudents(stream: InputStream): Stream[List[Student]] = {
    val zip = new ZipInputStream(stream)
    Stream.continually(Try(zip.getNextEntry()).getOrElse(null))
      .takeWhile(_ != null)
      .map(e => {
        Try(parse(zip).extract[List[Student]]) match {
          case Success(students) => students
          case Failure(e) => throw e
        }
      })
  }

  def fetchOne(hetu: String): Student =
    client.get("ytl.http.host.fetchone", hetu).expectStatus(200).execute((r:OphHttpResponse) => parse(r.asText()).extract[Student])

  def fetch(hetus: List[String]): Either[Throwable, Stream[List[Student]]] = {
    for {
      operation <- fetchOperation(hetus).right
      ok <- fetchStatus(operation.operationUuid).right
      zip <- downloadZip(operation.operationUuid).right
    } yield zipToStudents(zip)
  }

  def fetchStatus(uuid: String): Either[Throwable,Status] = {
    Try(client.get("ytl.http.host.status", uuid).expectStatus(200).execute((r: OphHttpResponse) => {
      implicit val formats = new DefaultFormats {
        override def dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
      } + new StatusDeserializer
      parse(r.asText()).extract[Status]
    })) match {
      case Success(e: InProgress) => {
        Thread.sleep(1000)
        println(e)
        fetchStatus(uuid)
      }
      case Success(e: Finished) => Right(e)
      case Success(e: Failed) => Left(new RuntimeException(s"Polling YTL service returned failed status ${e}"))
      case Failure(e) => Left(e)
      case status => Left(new RuntimeException(s"Unknown status ${status}"))
    }
  }

  def downloadZip(uuid: String): Either[Throwable, InputStream] = {
    Try(client.get("ytl.http.host.download", uuid).expectStatus(200).execute((r:OphHttpResponse) => {
      val output = fileSystem.write(uuid)
      val input = r.asInputStream()
      IOUtils.copyLarge(input,output)
      IOUtils.closeQuietly(input)
      IOUtils.closeQuietly(output)
      fileSystem.read(uuid)
    })) match {
      case Success(e) => Right(e)
      case Failure(e) => Left(e)
    }
  }

  def fetchOperation(hetus: List[String]): Either[Throwable, Operation] = {
    Try(client.post("ytl.http.host.bulk")
      .header(HttpHeaders.AUTHORIZATION, preemptiveBasicAuthentication)
      .dataWriter("application/json", "UTF-8", new OphRequestPostWriter() {
        override def writeTo(writer: io.Writer): Unit = writer.write(write(hetus))
      })
      .expectStatus(200).execute((r: OphHttpResponse) => parse(r.asText()).extract[Operation])) match {
      case Success(e) => Right(e)
      case Failure(e) => Left(e)
    }
  }

  implicit def function0ToRunnable[U](f:(OphHttpResponse) => U): OphHttpResponseHandler[U] =
    new OphHttpResponseHandler[U]{
      override def handleResponse(ophHttpResponse: OphHttpResponse): U = f(ophHttpResponse)
    }
}
