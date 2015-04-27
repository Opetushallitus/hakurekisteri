package fi.vm.sade.hakurekisteri.rekisteritiedot

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem, Props}
import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.arvosana.Arvosana
import fi.vm.sade.hakurekisteri.batchimport.ImportBatch
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.oppija.{Oppija, Todistus}
import fi.vm.sade.hakurekisteri.rest.support.{User, Registers}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus, yksilollistaminen}
import fi.vm.sade.hakurekisteri.test.tools.MockedResourceActor
import fi.vm.sade.hakurekisteri.web.rekisteritiedot.{RekisteriQuery, RekisteritiedotResource}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.joda.time.{DateTime, LocalDate}
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

class RekisteritiedotResourceSpec extends ScalatraFunSuite {
  implicit val system = ActorSystem("rekisteritiedot-resource-test-system")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val security = new TestSecurity
  implicit val user: User = security.TestUser
  implicit val swagger: Swagger = new HakurekisteriSwagger

  val suoritus = VirallinenSuoritus(
    komo = "pk",
    myontaja = "1.2.3",
    tila = "KESKEN",
    valmistuminen = new LocalDate(),
    henkilo = "1.24.1",
    yksilollistaminen = yksilollistaminen.Ei,
    suoritusKieli = "FI",
    vahv = true,
    lahde = "test"
  ).identify(UUID.randomUUID())

  val opiskelija = Opiskelija(
    oppilaitosOid = "1.2.3",
    luokkataso = "9",
    luokka = "9A",
    henkiloOid = "1.24.2",
    alkuPaiva = new DateTime(),
    source = "test"
  ).identify(UUID.randomUUID())

  val rekisterit = new Registers {
    val erat = system.actorOf(Props(new MockedResourceActor[ImportBatch]()))
    val arvosanat = system.actorOf(Props(new MockedResourceActor[Arvosana]()))
    val opiskeluoikeudet = system.actorOf(Props(new MockedResourceActor[Opiskeluoikeus]()))
    val opiskelijat = system.actorOf(Props(new MockedResourceActor[Opiskelija](query = q => Seq(opiskelija))))
    val suoritukset = system.actorOf(Props(new MockedResourceActor[Suoritus](query = q => Seq(suoritus))))

    override val eraRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(erat)))
    override val arvosanaRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(arvosanat)))
    override val opiskeluoikeusRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(opiskeluoikeudet)))
    override val opiskelijaRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(opiskelijat)))
    override val suoritusRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(suoritukset)))
  }

  val resource = new RekisteritiedotResource(rekisterit, Oids.oids)

  test("oppilaitos query to light endpoint should match only to suoritus.myontaja") {
    val tiedot = Await.result(resource.fetchTiedot(RekisteriQuery(oppilaitosOid = Some("1.2.3"), vuosi = None)), 15.seconds)
    
    tiedot should be(Seq(Oppija(
      oppijanumero = "1.24.1",
      opiskelu = Seq(),
      suoritukset = Seq(Todistus(suoritus, Seq())),
      opiskeluoikeudet = Seq(),
      ensikertalainen = None
    )))
  }

  override def stop(): Unit = {
    system.shutdown()
    system.awaitTermination(15.seconds)
    super.stop()
  }

}
