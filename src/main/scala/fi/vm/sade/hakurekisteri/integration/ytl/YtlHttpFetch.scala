package fi.vm.sade.hakurekisteri.integration.ytl

import java.io.InputStream
import java.util
import java.util.concurrent.Future
import java.util.concurrent.Future

import fi.vm.sade.hakurekisteri.rest.support.{KausiDeserializer, StudentDeserializer}
import fi.vm.sade.javautils.httpclient.{OphHttpResponseHandler, OphRequestParameters, OphHttpResponse, ApacheOphHttpClient}
import fi.vm.sade.properties.OphProperties
import org.apache.http.auth.{UsernamePasswordCredentials, AuthScope}
import org.apache.http.impl.client.BasicCredentialsProvider
import org.json4s.NoTypeHints
import org.json4s.jackson.Serialization

import scala.concurrent.Future

import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization

class YtlHttpFetch(config: OphProperties) {
  import scala.language.implicitConversions
  implicit val formats = Serialization.formats(NoTypeHints) + new KausiDeserializer + new StudentDeserializer

  private def buildClient(username: String, password: String) = {
    val a = ApacheOphHttpClient.
      createCustomBuilder()
    val provider = new BasicCredentialsProvider()

    // Create the authentication scope
    val scope = new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT, AuthScope.ANY_REALM);

    // Create credential pair
    val credentials = new UsernamePasswordCredentials(username, password);

    // Inject the credentials
    provider.setCredentials(scope, credentials);

    // Set the default credentials provider
    a.httpBuilder.setDefaultCredentialsProvider(provider)

    a.buildOphClient("tester", config)
  }

  val client = buildClient(config.getProperty("ytl.http.username"), config.getProperty("ytl.http.password"))

  def fetchOne(hetu: String): Student = {
    val v = client.get("ytl.http.host").expectStatus(200).execute((r:OphHttpResponse) => parse(r.asText()).extract[Student])
    v
  }

  implicit def function0ToRunnable(f:(OphHttpResponse) => Student): OphHttpResponseHandler[Student] =
    new OphHttpResponseHandler[Student]{
      override def handleResponse(ophHttpResponse: OphHttpResponse): Student = f(ophHttpResponse)
    }
}
