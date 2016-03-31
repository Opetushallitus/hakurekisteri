package fi.vm.sade.hakurekisteri.ensikertalainen

import akka.actor.{Actor, Props, ActorSystem}
import akka.pattern.ask
import akka.testkit.TestActorRef
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.tarjonta.{KomoResponse, GetKomoQuery}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{EnsimmainenVastaanotto, ValintarekisteriQuery}
import fi.vm.sade.hakurekisteri.suoritus._
import org.joda.time.{DateTimeZone, DateTime, LocalDate}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.duration._

import scala.language.reflectiveCalls

class EnsikertalainenActorSpec extends FlatSpec with Matchers with FutureWaiting with BeforeAndAfterAll {

  implicit val system = ActorSystem("ensikertalainen-test-system")
  implicit val timeout: Timeout = 10.seconds

  behavior of "EnsikertalainenActor"

  it should "return true if no kk tutkinto and no vastaanotto found" in {
    val (actor, _) = initEnsikertalainenActor(vastaanotot = Seq(EnsimmainenVastaanotto("1.2.246.562.24.1", None)))

    waitFuture((actor ? EnsikertalainenQuery(henkiloOids = Set("1.2.246.562.24.1"))).mapTo[Seq[Ensikertalainen]])(e => {
      e.head.ensikertalainen should be(true)
    })
  }

  it should "return ensikertalainen false based on kk tutkinto" in {
    val (actor, valintarek) = initEnsikertalainenActor(suoritukset = Seq(
      VirallinenSuoritus("koulutus_699999", "1.2.246.562.10.1", "VALMIS", new LocalDate(2014, 1, 1), "1.2.246.562.24.1", yksilollistaminen = yksilollistaminen.Ei, "FI", None, vahv = true, "")
    ), vastaanotot = Seq())

    waitFuture((actor ? EnsikertalainenQuery(henkiloOids = Set("1.2.246.562.24.1"))).mapTo[Seq[Ensikertalainen]])((e: Seq[Ensikertalainen]) => {
      e.head.ensikertalainen should be (false)
      e.head.menettamisenPeruste should be (Some(SuoritettuKkTutkinto(new DateTime(2014, 1, 1, 0, 0, 0, 0, DateTimeZone.forID("Europe/Helsinki")))))
      valintarek.underlyingActor.counter should be (0)
    })
  }

  it should "return ensikertalainen false based on vastaanotto" in {
    val (actor, _) = initEnsikertalainenActor(vastaanotot = Seq(EnsimmainenVastaanotto("1.2.246.562.24.1", Some(new DateTime(2015, 1, 1, 0, 0, 0, 0)))))

    waitFuture((actor ? EnsikertalainenQuery(henkiloOids = Set("1.2.246.562.24.1"))).mapTo[Seq[Ensikertalainen]])((e: Seq[Ensikertalainen]) => {
      e.head.ensikertalainen should be (false)
      e.head.menettamisenPeruste should be (Some(KkVastaanotto(new DateTime(2015, 1, 1, 0, 0, 0, 0))))
    })
  }

  def initEnsikertalainenActor(suoritukset: Seq[Suoritus] = Seq(), vastaanotot: Seq[EnsimmainenVastaanotto]) = {
    val valintarekisteri = TestActorRef(new Actor {
      var counter = 0
      override def receive: Actor.Receive = {
        case q: ValintarekisteriQuery =>
          counter = counter + 1
          sender ! vastaanotot
      }
    })
    (system.actorOf(Props(new EnsikertalainenActor(
      suoritusActor = system.actorOf(Props(new Actor {
        override def receive: Receive = {
          case q: SuoritusHenkilotQuery =>
            sender ! suoritukset
        }
      })),
      valintarekisterActor = valintarekisteri,
      tarjontaActor = system.actorOf(Props(new Actor {
        override def receive: Actor.Receive = {
          case q: GetKomoQuery => sender ! KomoResponse(q.oid, None)
        }
      })),
      config = Config.mockConfig
    ))), valintarekisteri)
  }

  override def afterAll() = {
    system.shutdown()
    system.awaitTermination(15.seconds)
  }

}
