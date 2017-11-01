package fi.vm.sade.hakurekisteri.integration.virta

import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import fi.vm.sade.hakurekisteri.SpecsLikeMockito
import fi.vm.sade.hakurekisteri.integration.organisaatio.{Oppilaitos, OppilaitosResponse, Organisaatio}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus}
import fi.vm.sade.hakurekisteri.test.tools.{FutureWaiting, MockedResourceActor}
import org.joda.time.LocalDate
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Future


class VirtaActorSpec extends FlatSpec with Matchers with FutureWaiting with SpecsLikeMockito {
  implicit val system = ActorSystem("test-virta-system")
  override implicit val ec = system.dispatcher

  behavior of "VirtaActor"

  it should "save results from Virta" in {
    val virtaClient = mock[VirtaClient]
    virtaClient.getOpiskelijanTiedot("1.2.3", Some("111111-1975")) returns Future.successful(
      Some(
        VirtaResult(
          oppijanumero = "1.2.3",
          opiskeluoikeudet = Seq(
            VirtaOpiskeluoikeus(
              alkuPvm = new LocalDate().minusYears(1),
              loppuPvm = None,
              myontaja = "01901",
              koulutuskoodit = Seq("782603"),
              kieli = "FI"
            )
          ),
          tutkinnot = Seq(
            VirtaTutkinto(
              suoritusPvm = new LocalDate().minusYears(2),
              koulutuskoodi = Some("725111"),
              myontaja = "01901",
              kieli = "FI"
            )
          ),
          suoritukset = Seq(
            VirtaOpintosuoritus(
              suoritusPvm = new LocalDate().minusYears(2),
              nimi = Some("foo"),
              koulutuskoodi = Some("725111"),
              laajuus = Some(5.0),
              arvosana = Some("5"),
              asteikko = Some("Viisiportainen"),
              myontaja = "01901",
              laji = Some("2")
            )
          )
        )
      )
    )

    val sWaiter = new Waiter()
    val oWaiter = new Waiter()

    val opiskeluoikeusHandler = (o: Opiskeluoikeus) => {
      oWaiter { o.myontaja should be ("1.3.0") }
      oWaiter.dismiss()
    }

    val suoritusActor = system.actorOf(Props(new MockedResourceActor[Suoritus, UUID]({
      case suoritus: VirallinenSuoritus =>
        sWaiter {
          suoritus.myontaja should be("1.3.0")
        }
        sWaiter.dismiss()
    })))
    val opiskeluoikeusActor = system.actorOf(Props(new MockedResourceActor[Opiskeluoikeus, UUID](opiskeluoikeusHandler)))
    val virtaActor: ActorRef = system.actorOf(Props(new VirtaActor(virtaClient, organisaatioActor, suoritusActor, opiskeluoikeusActor)))

    virtaActor ! VirtaQuery("1.2.3", Some("111111-1975"))

    import org.scalatest.time.SpanSugar._

    sWaiter.await(timeout(5.seconds), dismissals(1))
    oWaiter.await(timeout(5.seconds), dismissals(1))
  }


  class MockedOrganisaatioActor extends Actor {
    val hkiYliopisto = Organisaatio(
      oid = "1.3.0",
      nimi = Map("fi" -> "Helsingin yliopisto"),
      toimipistekoodi = None,
      oppilaitosKoodi = Some("01901"),
      parentOid = None,
      parentOidPath = None,
      children = Seq()
    )

    override def receive: Receive = {
      case Oppilaitos(o) => sender ! OppilaitosResponse(o, hkiYliopisto)
      case oid: String => sender ! None
    }
  }

  object AllResources

  val organisaatioActor = system.actorOf(Props(new MockedOrganisaatioActor()))

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


