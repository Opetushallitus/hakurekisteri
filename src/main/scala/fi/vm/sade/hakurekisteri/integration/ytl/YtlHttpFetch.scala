package fi.vm.sade.hakurekisteri.integration.ytl

import java.io.InputStream
import java.util
import java.util.concurrent.Future
import java.util.concurrent.Future

import fi.vm.sade.hakurekisteri.rest.support.{KausiDeserializer, StudentDeserializer}
import fi.vm.sade.javautils.httpclient.{OphHttpResponseHandler, OphRequestParameters, OphHttpResponse, ApacheOphHttpClient}
import fi.vm.sade.properties.OphProperties
import org.json4s.NoTypeHints
import org.json4s.jackson.Serialization

import scala.concurrent.Future

import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization

class YtlHttpFetch(config: OphProperties) {
  import scala.language.implicitConversions
  implicit val formats = Serialization.formats(NoTypeHints) + new KausiDeserializer + new StudentDeserializer

  val client = ApacheOphHttpClient.createDefaultOphHttpClient(
    "tester", config, 10000, 600);

  def fetchYtlData(hetut: List[String]): List[Student] = {
    //client.get(s"${config.host}")

     // .expectStatus(200).execute((r:OphHttpResponse) => r)
    /*
    new OphHttpResponseHandler[OphHttpResponse] {
      override def handleResponse(ophHttpResponse: OphHttpResponse): OphHttpResponse = {

        null
      }
    }
    */



    null
  }
  def fetchOne(hetu: String): Student = {
    val v = client.get("ytl.http.host").expectStatus(200).execute((r:OphHttpResponse) => parse(r.asText()).extract[Student])
    v
  }
/*
  def fetch(hetut: List[String]): Future[List[Student]] = {
    null
  }
  */
  implicit def function0ToRunnable(f:(OphHttpResponse) => Student): OphHttpResponseHandler[Student] =
    new OphHttpResponseHandler[Student]{
      override def handleResponse(ophHttpResponse: OphHttpResponse): Student = f(ophHttpResponse)
    }
}
