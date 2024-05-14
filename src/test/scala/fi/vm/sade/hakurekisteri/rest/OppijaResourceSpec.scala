package fi.vm.sade.hakurekisteri.rest

import java.util.UUID
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.TestActorRef
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaJDBCActor, ArvosanaTable}
import fi.vm.sade.hakurekisteri.batchimport.{ImportBatch, ImportBatchOrgActor}
import fi.vm.sade.hakurekisteri.ensikertalainen.{EnsikertalainenActor, Testihaku}
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, HakuNotFoundException}
import fi.vm.sade.hakurekisteri.integration.henkilo.{
  Henkilo,
  IOppijaNumeroRekisteri,
  LinkedHenkiloOids,
  MockPersonAliasesProvider,
  PersonOidsWithAliases
}
import fi.vm.sade.hakurekisteri.integration.tarjonta._
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{
  EnsimmainenVastaanotto,
  ValintarekisteriActor,
  ValintarekisteriActorRef
}
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaJDBCActor, OpiskelijaTable}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{
  Opiskeluoikeus,
  OpiskeluoikeusJDBCActor,
  OpiskeluoikeusTable
}
import fi.vm.sade.hakurekisteri.oppija.Oppija
import fi.vm.sade.hakurekisteri.ovara.OvaraDbRepository
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.storage.repository.{InMemJournal, Updated}
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.test.tools.{FutureWaiting, MockedResourceActor}
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import fi.vm.sade.hakurekisteri.web.oppija.{OppijaResource, OppijatPostSize}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity, TestUser}
import fi.vm.sade.hakurekisteri.{Config, MockConfig}
import org.joda.time.{DateTime, LocalDate}
import org.json4s.Extraction.decompose
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.read
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.implicitConversions
import scala.util.Random

class OppijaResourceSpec
    extends ScalatraFunSuite
    with MockitoSugar
    with DispatchSupport
    with FutureWaiting
    with LocalhostProperties {

  implicit var system: ActorSystem = _
  implicit var database: Database = _
  implicit val security = new TestSecurity
  implicit val user: User = TestUser
  implicit val swagger: Swagger = new HakurekisteriSwagger

  val henkilot: Set[String] = {
    var oids: Set[String] = Set("1.2.246.562.24.00000000001")
    while (oids.size < 10001) {
      oids = oids + s"1.2.246.562.24.${new Random().nextInt(99999999).toString.padTo(11, '0')}"
    }
    oids
  }

  val henkiloOidWithAliases = "1.2.246.562.24.12345678901"
  val aliasesOfHenkiloOid = Set("1.2.246.562.24.12345678902", "1.2.246.562.24.12345678903")
  val fakeOppijaNumeroRekisteri = new IOppijaNumeroRekisteri {
    override def fetchLinkedHenkiloOidsMap(henkiloOids: Set[String]): Future[LinkedHenkiloOids] = {
      val oidsOfAllLinked = aliasesOfHenkiloOid + henkiloOidWithAliases
      val allMappingsOfLinked = if (henkiloOids.intersect(oidsOfAllLinked).nonEmpty) {
        oidsOfAllLinked.map((_, oidsOfAllLinked)).toMap
      } else Map()
      Future.successful(
        LinkedHenkiloOids(
          henkiloOids.map(henkilo => (henkilo, Set(henkilo))).toMap ++ allMappingsOfLinked,
          Map()
        )
      )
    }

    override def enrichWithAliases(henkiloOids: Set[String]): Future[PersonOidsWithAliases] = {
      fetchLinkedHenkiloOidsMap(henkiloOids)
        .map(_.oidToLinkedOids)
        .map(PersonOidsWithAliases(henkiloOids, _))
    }

    override def getByHetu(hetu: String): Future[Henkilo] = {
      throw new UnsupportedOperationException("Not implemented")
    }

    override def getByOids(oids: Set[String]): Future[Map[String, Henkilo]] =
      Future.successful(Map.empty)

    override def fetchHenkilotInBatches(henkiloOids: Set[String]): Future[Map[String, Henkilo]] =
      Future.successful(Map.empty)
  }

  val linkedPersonsSuoritus = VirallinenSuoritus(
    "linkedPersonsSuoritusKomo",
    "linkedPersonsMyontaja",
    "VALMIS",
    new LocalDate(1996, 12, 3),
    henkiloOidWithAliases,
    yksilollistaminen.Ei,
    "FI",
    None,
    vahv = true,
    ""
  )
  val suorituksetSeq: Seq[VirallinenSuoritus] = henkilot
    .map(henkilo =>
      VirallinenSuoritus(
        "1.2.246.562.5.00000000001",
        "1.2.246.562.10.00000000001",
        "VALMIS",
        new LocalDate(2001, 1, 1),
        henkilo,
        yksilollistaminen.Ei,
        "FI",
        None,
        vahv = true,
        ""
      )
    )
    .toSeq :+ linkedPersonsSuoritus

  val hakemukset: Seq[FullHakemus] = henkilot
    .map(henkilo => {
      FullHakemus(
        oid = UUID.randomUUID().toString,
        personOid = Some(henkilo),
        applicationSystemId = "1.2.246.562.6.00000000001",
        answers = Some(HakemusAnswers(Some(HakemusHenkilotiedot(Henkilotunnus = Some(henkilo))))),
        state = Some("INCOMPLETE"),
        preferenceEligibilities = Seq(),
        attachmentRequests = Seq(),
        Some(1615219923688L),
        None
      )
    })
    .toSeq

  var valintarekisteri: TestActorRef[TestingValintarekisteriActor] = _
  var resource: OppijaResource = _
  val hakemusServiceMock = mock[IHakemusService]
  val config: MockConfig = new MockConfig

  private lazy val suoritusJournal =
    new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable], config = config)

  override def beforeAll(): Unit = {
    system = ActorSystem("oppija-resource-test-system")
    database = ItPostgres.getDatabase
    valintarekisteri = TestActorRef(
      new TestingValintarekisteriActor(
        new VirkailijaRestClient(
          config = ServiceConfig(serviceUrl = "http://localhost/valinta-tulos-service")
        ),
        config
      )
    )
    ItPostgres.reset()
    val rekisterit = new Registers {
      insertAFewRandomishSuoritukset(suoritusJournal)
      private val opiskelijaJournal = new JDBCJournal[Opiskelija, UUID, OpiskelijaTable](
        TableQuery[OpiskelijaTable],
        config = config
      )
      opiskelijaJournal.addModification(
        Updated(
          Opiskelija(
            "1.2.246.562.10.00000000001",
            "9",
            "9A",
            "1.2.246.562.24.61781310000",
            DateTime.now.minusYears(2),
            Some(DateTime.now.minusWeeks(1)),
            "source"
          ).identify
        )
      )

      private val arvosanaJournal =
        new JDBCJournal[Arvosana, UUID, ArvosanaTable](TableQuery[ArvosanaTable], config = config)
      private val opiskeluoikeusJournal =
        new JDBCJournal[Opiskeluoikeus, UUID, OpiskeluoikeusTable](
          TableQuery[OpiskeluoikeusTable],
          config = config
        )
      private val erat = system.actorOf(Props(new MockedResourceActor[ImportBatch, UUID]()))
      private val eraOrgs = system.actorOf(Props(new ImportBatchOrgActor(null, config)))
      private val arvosanat =
        system.actorOf(Props(new ArvosanaJDBCActor(arvosanaJournal, 1, config)))
      private val ytlArvosanat =
        system.actorOf(Props(new ArvosanaJDBCActor(arvosanaJournal, 1, config)))
      private val opiskeluoikeudet =
        system.actorOf(Props(new OpiskeluoikeusJDBCActor(opiskeluoikeusJournal, 1, config)))
      private val opiskelijat =
        system.actorOf(Props(new OpiskelijaJDBCActor(opiskelijaJournal, 1, config)))
      private val suoritukset = system.actorOf(
        Props(new SuoritusJDBCActor(suoritusJournal, 1, MockPersonAliasesProvider, config))
      )
      private val ytlSuoritukset = system.actorOf(
        Props(new SuoritusJDBCActor(suoritusJournal, 1, MockPersonAliasesProvider, config))
      )

      override val eraOrgRekisteri: ActorRef = eraOrgs
      override val eraRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(erat)))
      override val arvosanaRekisteri: ActorRef =
        system.actorOf(Props(new FakeAuthorizer(arvosanat)))
      override val ytlArvosanaRekisteri: ActorRef =
        system.actorOf(Props(new FakeAuthorizer(ytlArvosanat)))
      override val opiskeluoikeusRekisteri: ActorRef =
        system.actorOf(Props(new FakeAuthorizer(opiskeluoikeudet)))
      override val opiskelijaRekisteri: ActorRef =
        system.actorOf(Props(new FakeAuthorizer(opiskelijat)))
      override val suoritusRekisteri: ActorRef =
        system.actorOf(Props(new FakeAuthorizer(suoritukset)))
      override val ytlSuoritusRekisteri: ActorRef = {
        system.actorOf(Props(new FakeAuthorizer(ytlSuoritukset)))
      }
      override val ovaraDbRepository: OvaraDbRepository = mock[OvaraDbRepository]
    }
    val tarjontaActor = TarjontaActorRef(system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case GetKomoQuery(oid) =>
          sender ! KomoResponse(
            oid,
            Some(Komo(oid, Koulutuskoodi("123456"), "TUTKINTO_OHJELMA", "LUKIOKOULUTUS"))
          )
        case a => sender ! a
      }
    })))
    val hakuappConfig = ServiceConfig(serviceUrl = "http://localhost/haku-app")
    val endpoint = mock[Endpoint]
    when(
      endpoint.request(
        forPattern("http://localhost/haku-app/applications/listfull?start=0&rows=2000&asId=.*")
      )
    ).thenReturn((200, List(), "[]"))
    when(endpoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))
    val ensikertalaisuusActor = system.actorOf(
      Props(
        new EnsikertalainenActor(
          rekisterit.suoritusRekisteri,
          rekisterit.opiskeluoikeusRekisteri,
          new ValintarekisteriActorRef(valintarekisteri),
          tarjontaActor,
          system.actorOf(Props(new Actor {
            override def receive: Receive = {
              case GetHaku("notfound") =>
                Future.failed(HakuNotFoundException("haku not found")) pipeTo sender
              case q: GetHaku => sender ! Testihaku
            }
          })),
          hakemusServiceMock,
          fakeOppijaNumeroRekisteri,
          config
        )
      )
    )
    resource = new OppijaResource(
      rekisterit,
      hakemusServiceMock,
      ensikertalaisuusActor,
      fakeOppijaNumeroRekisteri
    )
    addServlet(resource, "/*")
    super.beforeAll()
  }

  private def insertAFewRandomishSuoritukset(
    suoritusJournal: JDBCJournal[Suoritus, UUID, SuoritusTable]
  ) = {
    val interval = 1000
    println(
      getClass.getSimpleName + s" inserting every ${interval}th of ${suorituksetSeq.size} suoritus rows..."
    )
    val started = System.currentTimeMillis()
    suorituksetSeq.zipWithIndex.foreach {
      case (s, index) if index % interval == 0 =>
        suoritusJournal.addModification(Updated(s.identify))
      case _ =>
    }
    println(
      getClass.getSimpleName + s" ...inserting every ${interval}th of ${suorituksetSeq.size} suoritus rows complete, took ${System
        .currentTimeMillis() - started} ms."
    )
  }

  override def afterAll(): Unit = {
    try super.afterAll()
    finally {
      Await.result(system.terminate(), 15.seconds)
      database.close()
    }
  }

  implicit val formats = HakurekisteriJsonSupport.format ++ List(new SuoritusDeserializer)

  private val OK: Int = 200
  private val BAD_REQUEST: Int = 400

  test("OppijaResource should return 200") {
    when(hakemusServiceMock.personOidsForHaku(anyString(), any[Option[String]]))
      .thenReturn(Future.successful(Set[String]()))
    when(hakemusServiceMock.hakemuksetForPersonsInHaku(any[Set[String]], anyString()))
      .thenReturn(Future.successful(Seq[FullHakemus]()))

    get("/?haku=1") {
      response.status should be(OK)
    }
  }

  test("OppijaResource should return 400 if no parameters are given") {
    get("/") {
      response.status should be(400)
    }
  }

  test("OppijaResource should return 10001 oppijas with ensikertalainen false") {
    when(hakemusServiceMock.personOidsForHaku(anyString(), any[Option[String]]))
      .thenReturn(Future.successful(henkilot))
    when(
      hakemusServiceMock.suoritusoikeudenTaiAiemmanTutkinnonVuosi(anyString(), any[Option[String]])
    ).thenReturn(Future.successful(Seq[FullHakemus]()))

    waitFuture(
      resource.fetchOppijat(true, HakemusQuery(Some("1.2.246.562.6.00000000001"), None, None))
    )(oppijat => {
      val expectedSize: Int = 10001
      oppijat.length should be(expectedSize)
      oppijat.foreach(o => o.ensikertalainen should be(Some(true)))
    })
  }

  test("OppijaResource should return opiskelu") {
    get("/1.2.246.562.24.61781310000?haku=1.2.3.4") {
      response.status should be(OK)

      val oppija = read[Oppija](response.body)
      oppija.opiskelu.size should be(1)
      oppija.opiskelu.head.oppilaitosOid should be("1.2.246.562.10.00000000001")
      oppija.opiskelu.head.luokka should be("9A")
    }
  }

  test("OppijaResource should return oppija with ensikertalainen true") {
    get("/1.2.246.562.24.00000000001?haku=1.2.3.4") {
      response.status should be(OK)

      val oppija = read[Oppija](response.body)
      oppija.ensikertalainen should be(Some(true))
    }
  }

  test("OppijaResource should not return ensikertalaisuus when haku parameter is not given") {
    get("/1.2.246.562.24.00000000001") {
      response.status should be(OK)

      val oppija = read[Oppija](response.body)
      oppija.oppijanumero should be("1.2.246.562.24.00000000001")
      oppija.ensikertalainen should be(None)
    }
  }

  test("OppijaResource should not cache ensikertalaisuus") {
    when(hakemusServiceMock.personOidsForHaku(anyString(), any[Option[String]]))
      .thenReturn(Future.successful(Set("1")))
    valintarekisteri.underlyingActor.requestCount = 0
    get("/?haku=1.2.246.562.6.00000000001") {
      get("/?haku=1.2.246.562.6.00000000001") {
        val expectedCount: Int = 2
        valintarekisteri.underlyingActor.requestCount should be(expectedCount)
      }
    }
  }

  test("OppijaResource should tell ensikertalaisuus true also for oppija without hetu") {
    waitFuture(
      resource.fetchOppijat(
        Set("1.2.246.562.24.00000000002"),
        true,
        HakemusQuery(haku = Some(Testihaku.oid))
      )(user)
    )((s: Seq[Oppija]) => {
      s.head.ensikertalainen should be(Some(true))
    })
  }

  test("OppijaResource should 200 when a list of person oids is sent as POST") {
    post("/?haku=1.2.3.4", """["1.2.246.562.24.00000000002"]""") {
      response.status should be(OK)
    }
  }

  test(
    "OppijaResource should return results for linked persons too when a list of person oids is sent as POST"
  ) {
    when(hakemusServiceMock.hakemuksetForPersonsInHaku(any[Set[String]], anyString()))
      .thenReturn(Future.successful(Seq[FullHakemus]()))
    suoritusJournal.addModification(Updated(linkedPersonsSuoritus.identify))
    post(s"/?haku=1.2.3.4&ensikertalaisuudet=false", s"""["$henkiloOidWithAliases"]""") {
      response.status should be(OK)
      val oppijas = read[Seq[Oppija]](response.body)
      oppijas should have size 1
      oppijas.head.suoritukset should have size 1
      oppijas.head.suoritukset.map(_.suoritus).foreach(_ should equal(linkedPersonsSuoritus))
    }

    val aliasesSeq = aliasesOfHenkiloOid.toSeq
    val (firstAlias, secondAlias) = (aliasesSeq(0), aliasesSeq(1))
    post(s"/?haku=1.2.3.4&ensikertalaisuudet=false", s"""["$firstAlias", "$secondAlias"]""") {
      response.status should be(OK)
      val oppijas = read[Seq[Oppija]](response.body)
      oppijas should have size 2
      oppijas(0).suoritukset should have size 1
      oppijas(0).suoritukset
        .map(_.suoritus)
        .foreach(_ should equal(linkedPersonsSuoritus.copy(henkilo = firstAlias)))
      oppijas(1).suoritukset should have size 1
      oppijas(1).suoritukset
        .map(_.suoritus)
        .foreach(_ should equal(linkedPersonsSuoritus.copy(henkilo = secondAlias)))
    }
  }

  test("OppijaResource should return 400 if too many person oids is sent as POST") {
    val json =
      decompose((1 to (OppijatPostSize.maxOppijatPostSize + 1)).map(i => s"1.2.246.562.24.$i"))

    post("/?haku=1.2.3.4", compact(json)) {
      response.status should be(BAD_REQUEST)
      response.body should include("too many person oids")
    }
  }

  test("OppijaResource should return 400 if invalid person oids is sent as POST") {
    post("/?haku=1.2.3.4", """["foo","1.2.246.562.24.00000000002"]""") {
      response.status should be(BAD_REQUEST)
      response.body should include("person oid must start with 1.2.246.562.24.")
    }
  }

  test("OppijaResource should return 100 oppijas when 100 person oids is sent as POST") {
    when(
      hakemusServiceMock.suoritusoikeudenTaiAiemmanTutkinnonVuosi(anyString, any[Option[String]])
    ).thenReturn(Future.successful(Seq[FullHakemus]()))
    val json = decompose(henkilot.take(100).map(i => s"1.2.246.562.24.$i"))

    post("/?haku=1.2.3.4", compact(json)) {
      val oppijat = read[Seq[Oppija]](response.body)
      oppijat.size should be(100)
    }
  }

  test(
    "OppijaResource should return an empty oppija object if no matching oppija found when person oid is sent as POST"
  ) {
    post("/?haku=1.2.3.4", """["1.2.246.562.24.00000000010"]""") {
      val oppijat = read[Seq[Oppija]](response.body)

      oppijat.size should be(1)

      val o = oppijat.head
      o.oppijanumero should be("1.2.246.562.24.00000000010")
      o.opiskelu.size should be(0)
      o.suoritukset.size should be(0)
      o.opiskeluoikeudet.size should be(0)
    }
  }

  test("OppijaResource should return 404 if haku not found") {
    get("/?haku=notfound") {
      response.status should be(404)
    }
  }

  implicit def seq2journalString[R <: fi.vm.sade.hakurekisteri.rest.support.Resource[String, R]](
    s: Seq[R]
  ): InMemJournal[R, String] = {
    val journal = new InMemJournal[R, String]
    s.foreach((resource: R) =>
      journal.addModification(Updated(resource.identify(UUID.randomUUID().toString)))
    )
    journal
  }
}

class TestingValintarekisteriActor(restClient: VirkailijaRestClient, config: Config)
    extends ValintarekisteriActor(restClient, config) {

  var requestCount: Long = 0

  override def fetchEnsimmainenVastaanotto(
    henkiloOids: Set[String],
    koulutuksenAlkamiskausi: String
  ): Future[Seq[EnsimmainenVastaanotto]] = {
    requestCount = requestCount + 1
    Future.successful(henkiloOids.map(EnsimmainenVastaanotto(_, None)).toSeq)
  }
}
