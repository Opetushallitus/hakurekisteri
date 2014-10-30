package fi.vm.sade.hakurekisteri.integration.virta

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import org.joda.time.LocalDate
import org.scalatest.{Matchers, FlatSpec}
import akka.pattern.ask

import scala.concurrent.Future
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import fi.vm.sade.hakurekisteri.SpecsLikeMockito


class VirtaActorSpec extends FlatSpec with Matchers with FutureWaiting with SpecsLikeMockito {
  implicit val system = ActorSystem("test-virta-system")
  override implicit val ec = system.dispatcher

  behavior of "VirtaActor"



  it should "convert VirtaResult into sequence of Suoritus" in {
    val virtaClient = mock[VirtaClient]
    virtaClient.getOpiskelijanTiedot("1.2.3", Some("111111-1975")) returns Future.successful(
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

    val virtaActor: ActorRef = system.actorOf(Props(new VirtaActor(virtaClient, organisaatioActor)))

    val result = (virtaActor ? VirtaQuery("1.2.3", Some("111111-1975")))(akka.util.Timeout(10, TimeUnit.SECONDS)).mapTo[VirtaData]

    waitFuture(result) {(r: VirtaData) => {
      r.opiskeluOikeudet.size should be(1)
      r.suoritukset.size should be(1)
    }}
  }




  class MockedOrganisaatioActor extends Actor {
    import akka.pattern.pipe
    override def receive: Receive = {
      case "01901" => Future.successful(Some(Organisaatio(oid = "1.3.0", nimi = Map("fi" -> "Helsingin yliopisto"), toimipistekoodi = None, oppilaitosKoodi = Some("01901"), parentOid = None))) pipeTo sender
      case default => Future.successful(None) pipeTo sender
    }
  }

  val organisaatioActor = system.actorOf(Props(new MockedOrganisaatioActor))

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


