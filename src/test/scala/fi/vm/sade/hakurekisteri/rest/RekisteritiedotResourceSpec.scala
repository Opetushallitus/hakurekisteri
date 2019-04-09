package fi.vm.sade.hakurekisteri.rest

import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.arvosana.Arvosana
import fi.vm.sade.hakurekisteri.batchimport.{ImportBatch, ImportBatchOrgActor}
import fi.vm.sade.hakurekisteri.integration.hakemus.IHakemusService
import fi.vm.sade.hakurekisteri.integration.henkilo.{MockOppijaNumeroRekisteri, PersonOidsWithAliases}
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.oppija.{Oppija, Todistus}
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, Registers, SuoritusDeserializer, User}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus, yksilollistaminen}
import fi.vm.sade.hakurekisteri.test.tools.{FutureWaiting, MockedResourceActor}
import fi.vm.sade.hakurekisteri.web.rekisteritiedot.{RekisteriQuery, RekisteritiedotResource}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity, TestUser}
import org.apache.commons.lang.NotImplementedException
import org.joda.time.{DateTime, LocalDate}
import org.json4s.jackson.Serialization._
import org.scalatest.mockito.MockitoSugar
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class RekisteritiedotResourceSpec extends ScalatraFunSuite with FutureWaiting with MockitoSugar {
  implicit val system = ActorSystem("rekisteritiedot-resource-test-system")
  implicit val security = new TestSecurity
  implicit val user: User = TestUser
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val formats = HakurekisteriJsonSupport.format ++ List(new SuoritusDeserializer)

  val suoritus = VirallinenSuoritus(
    komo = "pk",
    myontaja = "1.2.3",
    tila = "KESKEN",
    valmistuminen = new LocalDate(),
    henkilo = "1.2.246.562.24.00000000001",
    yksilollistaminen = yksilollistaminen.Ei,
    suoritusKieli = "FI",
    vahv = true,
    lahde = "test"
  ).identify(UUID.randomUUID())

  val opiskelija = Opiskelija(
    oppilaitosOid = "1.2.3",
    luokkataso = "9",
    luokka = "9A",
    henkiloOid = "1.2.246.562.24.00000000002",
    alkuPaiva = new DateTime(),
    source = "test"
  ).identify(UUID.randomUUID())

  val rekisterit = new Registers {
    val eraOrgs = system.actorOf(Props(new ImportBatchOrgActor(null, new MockConfig)))
    val erat = system.actorOf(Props(new MockedResourceActor[ImportBatch, UUID]()))
    val arvosanat = system.actorOf(Props(new MockedResourceActor[Arvosana, UUID]()))
    val ytlArvosanat = system.actorOf(Props(new MockedResourceActor[Arvosana, UUID]()))
    val opiskeluoikeudet = system.actorOf(Props(new MockedResourceActor[Opiskeluoikeus, UUID]()))
    val opiskelijat = system.actorOf(Props(new MockedResourceActor[Opiskelija, UUID](query = q => Seq(opiskelija))))
    val suoritukset = system.actorOf(Props(new MockedResourceActor[Suoritus, UUID](query = q => Seq(suoritus))))
    val ytlSuoritukset = system.actorOf(Props(new MockedResourceActor[Suoritus, UUID](query = q => Seq(suoritus))))

    override val eraOrgRekisteri: ActorRef = eraOrgs
    override val eraRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(erat)))
    override val arvosanaRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(arvosanat)))
    override val ytlArvosanaRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(ytlArvosanat)))
    override val opiskeluoikeusRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(opiskeluoikeudet)))
    override val opiskelijaRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(opiskelijat)))
    override val suoritusRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(suoritukset)))
    override val ytlSuoritusRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(ytlSuoritukset)))
  }

  val notImplementedActor = system.actorOf(Props(new Actor {
    override def receive: Receive = {
      case msg =>
        println(s"$self got message $msg from ${sender()}")
        Future.failed(new NotImplementedException("not implemented")) pipeTo sender
    }
  }))

  val resource = new RekisteritiedotResource(rekisterit, mock[IHakemusService], notImplementedActor, MockOppijaNumeroRekisteri)

  addServlet(resource, "/*")

  test("oppilaitos query should match only to suoritus.myontaja") {
    val tiedot = Await.result(resource.fetchTiedot(RekisteriQuery(oppilaitosOid = Some("1.2.246.562.24.00000000001"), vuosi = None)), 15.seconds)

    tiedot should be(Seq(Oppija(
      oppijanumero = "1.2.246.562.24.00000000001",
      opiskelu = Seq(),
      suoritukset = Seq(Todistus(suoritus, Seq())),
      opiskeluoikeudet = Seq(),
      ensikertalainen = None
    )))

    tiedot.head.suoritukset.head.suoritus.identify.id should equal(suoritus.id)
  }

  test("correct suoritus resource id should be retained when querying with person aliases") {
    val personOids = new PersonOidsWithAliases(Set("1.2.246.562.24.00000000001"),
      Map("1.2.246.562.24.00000000001" -> Set("1.2.246.562.24.00000000001")),
      Set("1.2.246.562.24.00000000001"))
    val oppijat = Await.result(resource.getRekisteriData(personOids), 15.seconds)
    oppijat should have size 1
    oppijat.head.suoritukset should have size 1

    oppijat.head.suoritukset.head.suoritus.identify.id should equal(suoritus.id)
  }

  test("should return a list of oppijas for a post query") {
    post("/", """["1.2.246.562.24.00000000001", "1.2.246.562.24.00000000002"]""") {
      response.status should be (200)

      val oppijat = read[List[Oppija]](body)
      oppijat.size should be (2)

      oppijat.head.suoritukset.head.suoritus.identify.id should equal(suoritus.id)
    }
  }

  override def stop(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    super.stop()
  }

}
