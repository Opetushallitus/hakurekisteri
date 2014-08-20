package fi.vm.sade.hakurekisteri.integration.virta

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import fi.vm.sade.hakurekisteri.integration.organisaatio.{Organisaatio, Organisaatiopalvelu}
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import org.joda.time.{LocalDate}
import org.scalatest.FlatSpec
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.time.{Millis, Span}
import org.specs.mock.Mockito
import org.specs.specification.Examples
import akka.pattern.ask

import scala.concurrent.Future

class VirtaActorSpec extends FlatSpec with ShouldMatchers with AsyncAssertions with Mockito {
  implicit val system = ActorSystem("test-virta-system")
  implicit val ec = system.dispatcher

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

    val virtaActor: ActorRef = system.actorOf(Props(new VirtaActor(virtaClient, organisaatiopalvelu)))

    val result: Future[Seq[Suoritus]] = (virtaActor ? (Some("1.2.3"), Some("111111-1975")))(akka.util.Timeout(3, TimeUnit.SECONDS)).mapTo[Seq[Suoritus]]

    waitFuture(result) {(r: Seq[Suoritus]) => {
      r.size should be(2)
    }}
  }

  def waitFuture[A](f: Future[A])(assertion: A => Unit) = {
    val w = new Waiter

    f.onComplete(r => {
      w(assertion(r.get))
      w.dismiss()
    })

    w.await(timeout(Span(4000, Millis)), dismissals(1))
  }

  override def forExample: Examples = ???
  override def lastExample: Option[Examples] = ???

  object organisaatiopalvelu extends Organisaatiopalvelu {
    override def getAll() = ???
    override def get(str: String): Future[Option[Organisaatio]] = Future.successful(doTheMatch(str))

    def doTheMatch(str: String): Option[Organisaatio] = str match {
      case "01901" => Some(Organisaatio(oid = "1.3.0", nimi = Map("fi" -> "Helsingin yliopisto"), toimipistekoodi = None, oppilaitosKoodi = Some("01901"), parentOid = None))
      case default => None
    }
  }
}

