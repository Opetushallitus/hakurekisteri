package fi.vm.sade.hakurekisteri.integration.virta

import java.net.URL
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import com.stackmob.newman.request._
import com.stackmob.newman.response.{HttpResponseCode, HttpResponse}
import com.stackmob.newman.{RawBody, Headers, HttpClient}
import fi.vm.sade.hakurekisteri.integration.{ServiceConfig, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.integration.tarjonta.TarjontaActor
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import org.joda.time.LocalDate
import org.scalatest.FlatSpec
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.time.{Millis, Span}
import org.specs.mock.Mockito
import org.specs.specification.Examples
import akka.pattern.ask

import scala.concurrent.Future
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting

class VirtaActorSpec extends FlatSpec with ShouldMatchers with FutureWaiting with Mockito {
  implicit val system = ActorSystem("test-virta-system")
  override implicit val ec = system.dispatcher

  behavior of "VirtaActor"

  it should "convert VirtaResult into sequence of Suoritus" in {
    val virtaClient = mock[VirtaClient]
    virtaClient.getOpiskelijanTiedot(Some("1.2.3"), Some("111111-1975")) returns Future.successful(
      Some(
        VirtaResult(
          opiskeluoikeudet = Seq(
            VirtaOpiskeluoikeus(
              alkuPvm = new LocalDate().minusYears(1),
              loppuPvm = None,
              myontaja = "01901",
              koulutuskoodit = Seq("782603"),
              opintoala1995 = Some("58"),
              koulutusala2002 = None,
              kieli = "FI"
            )
          ),
          tutkinnot = Seq(
            VirtaTutkinto(
              suoritusPvm = new LocalDate().minusYears(2),
              koulutuskoodi = Some("725111"),
              opintoala1995 = Some("79"),
              koulutusala2002 = None,
              myontaja = "01901",
              kieli = "FI"
            )
          )
        )
      )
    )

    val tarjontaActor: ActorRef = system.actorOf(Props(new TarjontaActor(new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost"))(tarjontaHttpClient, ec))))
    val virtaActor: ActorRef = system.actorOf(Props(new VirtaActor(virtaClient, organisaatioActor, tarjontaActor)))

    val result = (virtaActor ? VirtaQuery(Some("1.2.3"), Some("111111-1975")))(akka.util.Timeout(10, TimeUnit.SECONDS)).mapTo[VirtaData]

    waitFuture(result) {(r: VirtaData) => {
      r.opiskeluOikeudet.size should be(1)
      r.suoritukset.size should be(1)
    }}
  }



  override def forExample: Examples = ???
  override def lastExample: Option[Examples] = ???

  class MockedOrganisaatioActor extends Actor {
    import akka.pattern.pipe
    override def receive: Receive = {
      case "01901" => Future.successful(Some(Organisaatio(oid = "1.3.0", nimi = Map("fi" -> "Helsingin yliopisto"), toimipistekoodi = None, oppilaitosKoodi = Some("01901"), parentOid = None))) pipeTo sender
      case default => Future.successful(None) pipeTo sender
    }
  }

  val organisaatioActor = system.actorOf(Props(new MockedOrganisaatioActor))

  val tarjontaHttpClient = new HttpClient {
    override def get(url: URL, headers: Headers): GetRequest = GetRequest(url, headers) {
      println(s"get request $url")
      url match {
        case s: URL if s.getQuery.contains("koulutus=782603") => Future.successful(HttpResponse(HttpResponseCode.Ok, Headers(List()), RawBody(json782603)))
        case s: URL if s.getQuery.contains("koulutus=725111") => Future.successful(HttpResponse(HttpResponseCode.Ok, Headers(List()), RawBody(json725111)))
        case _ => Future.successful(HttpResponse(HttpResponseCode.Ok, Headers(List()), RawBody("{\"result\":[]}")))
      }
    }
    override def put(url: URL, headers: Headers, body: RawBody): PutRequest = ???
    override def delete(url: URL, headers: Headers): DeleteRequest = ???
    override def post(url: URL, headers: Headers, body: RawBody): PostRequest = ???
    override def head(url: URL, headers: Headers): HeadRequest = ???
  }

  val json782603 = """{
    |"result":[
    |  {
    |    "oid":"1.4.0",
    |    "koulutuskoodi": {
    |      "uri":"koulutus_782603",
    |      "arvo":"782603",
    |      "nimi":"Testikoulutus"
    |    },
    |    "koulutusala": {
    |      "uri":"",
    |      "arvo":"",
    |      "nimi":""
    |    },
    |    "opintoala": {
    |      "uri":"opintoalaoph1995_58",
    |      "arvo":"58",
    |      "nimi":"Testiala"
    |    },
    |    "koulutusmoduuliTyyppi":"TUTKINTO",
    |    "koulutusasteTyyppi":"KORKEAKOULUTUS"
    |  }
    |]}""".stripMargin

  val json725111 = """{
    |"result":[
    |  {
    |    "oid":"1.4.0",
    |    "koulutuskoodi": {
    |      "uri":"koulutus_725111",
    |      "arvo":"725111",
    |      "nimi":"Testikoulutus 2"
    |    },
    |    "koulutusala": {
    |      "uri":"",
    |      "arvo":"",
    |      "nimi":""
    |    },
    |    "opintoala": {
    |      "uri":"opintoalaoph1995_79",
    |      "arvo":"79",
    |      "nimi":"Testiala 2"
    |    },
    |    "koulutusmoduuliTyyppi":"TUTKINTO",
    |    "koulutusasteTyyppi":"KORKEAKOULUTUS"
    |  }
    |]}""".stripMargin

}


