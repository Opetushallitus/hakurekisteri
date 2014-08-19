package fi.vm.sade.hakurekisteri.virta

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import org.joda.time.LocalDate
import org.scalatest.FlatSpec
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.time.{Millis, Span}
import org.specs.mock.Mockito
import org.specs.specification.Examples

import scala.concurrent.Future

class VirtaActorSpec extends FlatSpec with ShouldMatchers with AsyncAssertions with Mockito {
  implicit val system = ActorSystem("test-virta-system")
  implicit val ec = system.dispatcher

  behavior of "VirtaActor"

  it should "convert VirtaResult into sequence of Suoritus" in {
    val virtaClient = mock[VirtaClient]
    virtaClient.getOpiskelijanTiedot(Some("1.2.3"), Some("111111-1975")) returns Future.successful(Some(VirtaResult(Seq(
      VirtaOpiskeluoikeus(
        alkuPvm = new LocalDate(),
        loppuPvm = None,
        myontaja = "01901",
        koulutuskoodit = Seq("782603"),
        opintoala1995 = Some("58"),
        koulutusala2002 = None,
        kieli = "FI"
      )
    ), Seq(
      VirtaTutkinto(
        suoritusPvm = new LocalDate(),
        koulutuskoodi = Some("725111"),
        opintoala1995 = Some("79"),
        koulutusala2002 = None,
        myontaja = "01901",
        kieli = "FI"
      )
    ))))

    val virtaActor: ActorRef = system.actorOf(Props(new VirtaActor(virtaClient)))

    import akka.pattern.ask

    val q: (Some[String], Some[String]) = (Some("1.2.3"), Some("111111-1975"))
    val result: Future[Seq[Suoritus]] = (virtaActor ? q)(akka.util.Timeout(3, TimeUnit.SECONDS)).mapTo[Seq[Suoritus]]

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
}
