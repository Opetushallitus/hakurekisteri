package fi.vm.sade.hakurekisteri.integration.ytl

import java.io.{InputStream}
import java.text.SimpleDateFormat
import java.util.zip.{ZipInputStream}
import java.{io}
import fi.vm.sade.hakurekisteri.rest.support.{KausiDeserializer, StudentDeserializer}
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

  def zipToStudents(stream: InputStream): Unit = {
    val zip = new ZipInputStream(stream)
    Stream.continually(Try(zip.getNextEntry()).getOrElse(null))
      .takeWhile(_ != null)
      .foreach( e => {
        Try(parse(zip).extract[List[Student]]) match {
          case Success(students) =>
          case Failure(e) => throw e
        }
      })
  }

  def fetch(hetus: List[String]): Either[Throwable, List[Student]] = {
    val writer: OphRequestPostWriter = new OphRequestPostWriter() {
      override def writeTo(writer: io.Writer): Unit = writer.write(write(hetus))
    }

    val operation = client.post("ytl.http.host.bulk")
        .header(HttpHeaders.AUTHORIZATION,preemptiveBasicAuthentication)
        .dataWriter("application/json", "UTF-8", writer)
      .expectStatus(200).execute((r:OphHttpResponse) => parse(r.asText()).extract[Operation])

    val uuid = operation.operationUuid

    val status = client.get("ytl.http.host.status", uuid).expectStatus(200).execute((r:OphHttpResponse) => {
      implicit val formats = new DefaultFormats {
        override def dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
      }
      parse(r.asText()).extract[Status]
    })

    if(status.finished.isDefined) {
      val download = client.get("ytl.http.host.download", uuid).expectStatus(200).execute((r:OphHttpResponse) => {
        val output = fileSystem.write(uuid)
        val input = r.asInputStream()
        IOUtils.copyLarge(input,output)
        IOUtils.closeQuietly(input)
        IOUtils.closeQuietly(output)
      })
      val file = zipToStudents(fileSystem.read(uuid))

    } else if(status.failure.isDefined) {
      Left(new RuntimeException(s"Fetching YTL data with hetu's failed: ${status.failure.get}"))
    }

    status.finished match {
      case Some(date) =>
        Right(List())
      case _ => null
    }
  }

  def fetchOne(hetu: String): Student =
    client.get("ytl.http.host.fetchone", hetu).expectStatus(200).execute((r:OphHttpResponse) => parse(r.asText()).extract[Student])


  implicit def function0ToRunnable[U](f:(OphHttpResponse) => U): OphHttpResponseHandler[U] =
    new OphHttpResponseHandler[U]{
      override def handleResponse(ophHttpResponse: OphHttpResponse): U = f(ophHttpResponse)
    }
}
