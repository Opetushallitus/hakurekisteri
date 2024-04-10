package fi.vm.sade.hakurekisteri.rest

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.henkilo.MockOppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.integration.ytl._
import fi.vm.sade.hakurekisteri.integration.{DispatchSupport, Endpoint, ExecutorUtil}
import fi.vm.sade.hakurekisteri.web.integration.ytl.YtlResource
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, Security}
import fi.vm.sade.hakurekisteri.{Config, MockConfig}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.junit.JUnitRunner
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.{ExecutionContext, Future}

@RunWith(classOf[JUnitRunner])
class YtlResourceSpec
    extends ScalatraFunSuite
    with DispatchSupport
    with YtlMockFixture
    with MockFactory {
  implicit val system = ActorSystem()
  implicit val clientEc = ExecutorUtil.createExecutor(1, "ytl-resource-test-pool")
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val adminSecurity: Security = new SuoritusResourceAdminTestSecurity
  val hakemusService = stub[IHakemusService]
  val fileSystem = YtlFileSystem(ytlProperties)
  val ytlHttpFetch = new YtlHttpFetch(ytlProperties, fileSystem)
  val config: Config = new MockConfig

  val successfulYtlKokelasPersister: KokelasPersister = mock[KokelasPersister]
  (successfulYtlKokelasPersister
    .persistSingle(_: KokelasWithPersonAliases)(_: ExecutionContext))
    .expects(*, *)
    .returns(Future.unit)

  val ytlIntegration = new YtlIntegration(
    ytlProperties,
    ytlHttpFetch,
    hakemusService,
    MockOppijaNumeroRekisteri,
    successfulYtlKokelasPersister,
    config
  )
  val someKkHaku = "kkhaku"
  ytlIntegration.setAktiivisetKKHaut(Set(someKkHaku))

  val answers =
    HakemusAnswers(henkilotiedot = Some(HakemusHenkilotiedot(Henkilotunnus = Some("050996-9574"))))
  val hakemusWithPersonOidEnding9574 = Future.successful(
    Seq(
      FullHakemus(
        "",
        Some("050996-9574"),
        someKkHaku,
        Some(answers),
        Some("ACTIVE"),
        Seq(),
        Seq(),
        Some(1615219923688L),
        None
      )
    )
  )

  val ytlFetchActor = YtlFetchActorRef(
    system.actorOf(
      Props(
        new YtlFetchActor(
          properties = ytlProperties,
          ytlHttpFetch,
          hakemusService,
          MockOppijaNumeroRekisteri,
          successfulYtlKokelasPersister,
          config
        )
      ),
      "ytlFetchActor"
    )
  )

  addServlet(new YtlResource(ytlIntegration, ytlFetchActor), "/*")
  ytlFetchActor.actor ! ActiveKkHakuOids(Set(someKkHaku, "another_kk_haku"))
  val endPoint = mock[Endpoint]

  def fullHakemusToHakemusHakuHetuPerson(f: FullHakemus): HakemusHakuHetuPersonOid =
    HakemusHakuHetuPersonOid(f.oid, f.applicationSystemId, f.hetu.get, f.personOid.get)

  test("should launch YTL fetch") {
    post("/http_request") {
      status should be(202)
    }
    hakemusService.hetuAndPersonOidForPersonOid _ when (*) returns hakemusWithPersonOidEnding9574
      .map(h => h.map(fullHakemusToHakemusHakuHetuPerson))
    get("/http_request/050996-9574") {
      //body should be(true)
      status should be(202)
    }
  }

  test("should launch YTL fetch for single full haku") {
    hakemusService.hetuAndPersonOidForHakuLite _ when (*) returns Future.successful(
      Seq(HetuPersonOid("hetu", "personOid"))
    )
    get("/http_request_byhaku/1.2.3.4.5") {
      status should be(202)
    }
    Thread.sleep(5000)
  }

  test("should launch YTL fetch for all active hakus") {
    hakemusService.hetuAndPersonOidForHakuLite _ when (*) returns Future.successful(
      Seq(HetuPersonOid("hetu", "personOid"))
    )
    post("/http_request_byhaku") {
      status should be(202)
    }
    Thread.sleep(5000)
  }
}
