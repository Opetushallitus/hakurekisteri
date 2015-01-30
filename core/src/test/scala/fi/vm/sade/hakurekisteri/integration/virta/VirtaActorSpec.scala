package fi.vm.sade.hakurekisteri.integration.virta

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.hakurekisteri.suoritus.VirallinenSuoritus
import org.joda.time.LocalDate
import org.scalatest.{Matchers, FlatSpec}

import scala.concurrent.Future
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import fi.vm.sade.hakurekisteri.SpecsLikeMockito


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

    val sWaiter = new Waiter()
    val oWaiter = new Waiter()

    val suoritusHandler = (suoritus: VirallinenSuoritus) => {
      sWaiter { suoritus.myontaja should be ("1.3.0") }
      sWaiter.dismiss()
    }

    val opiskeluoikeusHandler = (o: Opiskeluoikeus) => {
      oWaiter { o.myontaja should be ("1.3.0") }
      oWaiter.dismiss()
    }

    val suoritusActor = system.actorOf(Props(new MockedResourceActor[VirallinenSuoritus](suoritusHandler)))
    val opiskeluoikeusActor = system.actorOf(Props(new MockedResourceActor[Opiskeluoikeus](opiskeluoikeusHandler)))
    val virtaActor: ActorRef = system.actorOf(Props(new VirtaActor(virtaClient, organisaatioActor, suoritusActor, opiskeluoikeusActor)))

    virtaActor ! VirtaQuery("1.2.3", Some("111111-1975"))

    import org.scalatest.time.SpanSugar._

    sWaiter.await(timeout(5.seconds), dismissals(1))
    oWaiter.await(timeout(5.seconds), dismissals(1))
  }


  class MockedOrganisaatioActor extends Actor {
    import akka.pattern.pipe
    override def receive: Receive = {
      case "01901" => Future.successful(Some(Organisaatio(oid = "1.3.0", nimi = Map("fi" -> "Helsingin yliopisto"), toimipistekoodi = None, oppilaitosKoodi = Some("01901"), parentOid = None, children = Seq()))) pipeTo sender
      case default => Future.successful(None) pipeTo sender
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

class MockedResourceActor[T](save: (T) => Unit = {(r: T) => }, query: (Query[T]) => Seq[T] = {(q: Query[T]) => Seq()}) extends Actor {
  override def receive: Receive = {
    case q: Query[T] =>
      sender ! query(q)
    case r: T =>
      save(r)
      sender ! r
    case a: Any => println(s"got unrecognised message $a")
  }
}
