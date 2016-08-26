package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaActor, ArvosanaQuery}
import fi.vm.sade.hakurekisteri.integration.ActorSystemSupport
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusServiceMock
import fi.vm.sade.hakurekisteri.integration.henkilo.MockHenkiloActor
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository.{InMemJournal, Updated}
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import fi.vm.sade.hakurekisteri.{KomoOids, MockConfig, OrganisaatioOids}
import org.joda.time.LocalDate
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.implicitConversions

class YtlActorUpdateSuoritusSpec extends ScalatraFunSuite with ActorSystemSupport with YtlTestDsl with FutureWaiting {

  test("YtlActor should not overwrite an existing old suoritus in tila VALMIS with a new suoritus in tila KESKEN from YTL") {
    withSystem({
      implicit system => {
        implicit val ec: ExecutionContext = system.dispatcher

        suoritusJournal has vanhaValmisYoSuoritus

        val ytlActor = createYtlActor

        ytlActor ! YtlResult(UUID.randomUUID(), getClass.getResource("/ytl-update-suoritus-test.xml").getFile)

        waitFuture(waitForArvosanat)(a => a.nonEmpty should be (true))

        waitFuture(waitForSuoritus("1.2.246.562.24.71944845619"))(s => {
          s.tila should be ("VALMIS")
          s.valmistuminen should be (new LocalDate(1989, 6, 1))
        })
      }
    })
  }
}

trait YtlTestDsl {
  var suoritusActor: Option[ActorRef] = None
  var arvosanaActor: Option[ActorRef] = None

  implicit val timeout: Timeout = 60.seconds

  val vanhaValmisYoSuoritus = VirallinenSuoritus(
    komo = KomoOids.pohjakoulutus.yoTutkinto,
    myontaja = OrganisaatioOids.ytl,
    tila = "VALMIS",
    valmistuminen = new LocalDate(1989, 6, 1),
    henkilo = "1.2.246.562.24.71944845619",
    yksilollistaminen = yksilollistaminen.Ei,
    suoritusKieli = "FI",
    opiskeluoikeus = None,
    vahv = true,
    lahde = "testivirkailija"
  )

  def waitForSuoritus(henkilo: String)(implicit ec: ExecutionContext): Future[VirallinenSuoritus with Identified[UUID]] = {
    Future {
      val suoritusQ = SuoritusQuery(henkilo = Some(henkilo))
      var results: Seq[VirallinenSuoritus with Identified[UUID]] = List()
      while(results.isEmpty) {
        Thread.sleep(100)
        results = Await.result((suoritusActor.getOrElse(ActorRef.noSender) ? suoritusQ).mapTo[Seq[VirallinenSuoritus with Identified[UUID]]], 60.seconds)
      }
      results.head
    }
  }

  def waitForArvosanat(implicit ec: ExecutionContext): Future[Seq[Arvosana with Identified[UUID]]] = {
    Future {
      val suoritus = Await.result(waitForSuoritus("1.2.246.562.24.71944845619"), 60.seconds)
      val arvosanatQ = ArvosanaQuery(Some(suoritus.id))
      var results: Seq[Arvosana with Identified[UUID]] = List()
      while(results.isEmpty) {
        Thread.sleep(100)
        results = Await.result((arvosanaActor.getOrElse(ActorRef.noSender) ? arvosanatQ).mapTo[Seq[Arvosana with Identified[UUID]]], 60.seconds)
      }
      results
    }
  }

  implicit def seq2journal[R <: fi.vm.sade.hakurekisteri.rest.support.Resource[UUID, R]](s:Seq[R]): InMemJournal[R, UUID] = {
    val journal = new InMemJournal[R, UUID]
    s.foreach((resource:R) => journal.addModification(Updated(resource.identify(UUID.randomUUID()))))
    journal
  }

  object suoritusJournal extends InMemJournal[Suoritus, UUID] {
    def has(suoritus: Suoritus): Unit = addModification(Updated(suoritus.identify))
  }

  def createYtlActor(implicit system: ActorSystem) = {
    val config = new MockConfig

    val henkiloActor = system.actorOf(Props(new MockHenkiloActor(config)))

    suoritusActor = Some(system.actorOf(Props(new SuoritusActor(journal = suoritusJournal)), "suoritukset"))
    val arvosanaJournal: InMemJournal[Arvosana, UUID] = Seq()
    arvosanaActor = Some(system.actorOf(Props(new ArvosanaActor(journal = arvosanaJournal)), "arvosanat"))
    val hakemusService = new HakemusServiceMock

    system.actorOf(Props(new YtlActor(
      henkiloActor = henkiloActor,
      suoritusRekisteri = suoritusActor.getOrElse(ActorRef.noSender),
      arvosanaRekisteri = arvosanaActor.getOrElse(ActorRef.noSender),
      hakemusService = hakemusService,
      config = Some(YTLConfig("", "", "", "", "", List(), ""))
    )))
  }
}