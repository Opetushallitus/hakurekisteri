package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, _}
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.henkilo.{HenkiloActorRef, HttpHenkiloActor}
import fi.vm.sade.hakurekisteri.integration.koodisto.{GetKoodistoKoodiArvot, KoodistoActorRef, KoodistoKoodiArvot}
import fi.vm.sade.hakurekisteri.integration.organisaatio.{HttpOrganisaatioActor, OrganisaatioActorRef}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, JDBCJournal}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus, yksilollistaminen}
import fi.vm.sade.hakurekisteri.test.tools.{FailingResourceActor, MockedResourceActor}
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import fi.vm.sade.hakurekisteri.{MockCacheFactory, MockConfig, Oids}
import generators.DataGen
import org.joda.time.LocalDate
import org.mockito.Mockito._
import org.scalatest.concurrent.{PatienceConfiguration, Waiters}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time.SpanSugar._
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext}

class ArvosanatProcessingSpec extends FlatSpec with Matchers with MockitoSugar with DispatchSupport with Waiters
  with HakurekisteriJsonSupport with ActorSystemSupport with LocalhostProperties {

  behavior of "ArvosanaProcessing"

  import Fixtures._

  private val awaitTimeout: FiniteDuration = Duration(1, TimeUnit.MINUTES)
  private val waiterTimeout: PatienceConfiguration.Timeout = timeout(1.minute)
  private val mockConfig: MockConfig = new MockConfig

  it should "resolve data from henkilopalvelu, organisaatiopalvelu and suoritusrekisteri, and then import data into arvosanarekisteri" in {
    withSystem(
      implicit system => {
        implicit val ec: ExecutionContext = system.dispatcher

        val suoritusWaiter = new Waiter()
        val arvosanaWaiter = new Waiter()
        val importBatchWaiter = new Waiter()

        var s = perusopetusSuoritus(new LocalDate(2001, 1, 1))
        val batch = batchGenerator.generate

        val arvosanatProcessing = new ArvosanatProcessing(
          createImportBatchOrgActor(system),
          createOrganisaatioActor,
          createHenkiloActor,
          system.actorOf(Props(new MockedResourceActor[Suoritus, UUID](save = {
            case suoritus: VirallinenSuoritus with Identified[UUID] =>
              suoritusWaiter { suoritus.tila should be ("VALMIS") }
              suoritusWaiter.dismiss()
              s = s.identify(suoritus.id)
          }, query = { q => Seq(s) }))),
          system.actorOf(Props(new MockedResourceActor[Arvosana, UUID](save = (a: Arvosana) => {
            arvosanaWaiter { a.suoritus should be (s.id) }
            arvosanaWaiter.dismiss()
          }, query = {q => Seq()}))),
          createImportBatchActor(system, (b: ImportBatch) => {
            importBatchWaiter { b.state should be (BatchState.DONE) }
            importBatchWaiter.dismiss()
          }, batch),
          createKoodistoActor,
          mockConfig
        )
        val status = Await.result(arvosanatProcessing.process(batch), awaitTimeout).status
        status.messages shouldBe empty
        suoritusWaiter.await(waiterTimeout, dismissals(1))
        arvosanaWaiter.await(waiterTimeout, dismissals(23))
        importBatchWaiter.await(waiterTimeout, dismissals(1))
      }
    )
  }

  it should "update the valmistuminen date to the one from the arvosanat" in {
    withSystem(
      implicit system => {
        implicit val ec: ExecutionContext = system.dispatcher

        val suoritusWaiter = new Waiter()
        val s = perusopetusSuoritus(new LocalDate(2001, 1, 1))
        val batch = batchGeneratorUpdatedValmistuminen.generate

        val arvosanatProcessing = new ArvosanatProcessing(
          createImportBatchOrgActor(system),
          createOrganisaatioActor,
          createHenkiloActor,
          system.actorOf(Props(new MockedResourceActor[Suoritus, UUID](save = {
            case suoritus: VirallinenSuoritus with Identified[UUID] =>
              suoritusWaiter {
                suoritus.tila should be ("VALMIS")
                suoritus.valmistuminen should be (new LocalDate(2001, 2, 2))
              }
              suoritusWaiter.dismiss()
          }, query = { q => Seq(s) }))),
          system.actorOf(Props(new MockedResourceActor[Arvosana, UUID](save = {a => }, query = {q => Seq()}))),
          createImportBatchActor(system, {b => }, batch),
          createKoodistoActor,
          mockConfig
        )
        val status = Await.result(arvosanatProcessing.process(batch), awaitTimeout).status
        status.messages shouldBe empty
        suoritusWaiter.await(waiterTimeout, dismissals(1))
      }
    )
  }

  it should "import luokalle jaanyt" in {
    withSystem(
      implicit system => {
        implicit val ec: ExecutionContext = system.dispatcher

        val suoritusWaiter = new Waiter()
        val importBatchWaiter = new Waiter()

        val s = perusopetusSuoritus(new LocalDate(2001, 1, 1))
        val batch = batchGeneratorLuokalleJaanyt.generate

        val arvosanatProcessing = new ArvosanatProcessing(
          createImportBatchOrgActor(system),
          createOrganisaatioActor,
          createHenkiloActor,
          system.actorOf(Props(new MockedResourceActor[Suoritus, UUID](save = {
            case s: VirallinenSuoritus =>
              suoritusWaiter {
                s.tila should be ("KESKEN")
                s.valmistuminen should be (new LocalDate(2002, 1, 1))
              }
              suoritusWaiter.dismiss()
          }, query = {q => Seq(s)}))),
          system.actorOf(Props(new MockedResourceActor[Arvosana, UUID](save = {a => }, query = {q => Seq()}))),
          createImportBatchActor(system, (b: ImportBatch) => {
            importBatchWaiter { b.state should be (BatchState.DONE) }
            importBatchWaiter.dismiss()
          }, batch),
          createKoodistoActor,
          mockConfig
        )

        val status = Await.result(arvosanatProcessing.process(batch), awaitTimeout).status
        status.messages shouldBe empty
        suoritusWaiter.await(waiterTimeout, dismissals(1))
        importBatchWaiter.await(waiterTimeout, dismissals(1))
      }
    )
  }

  it should "import ei valmistu" in {
    withSystem(
      implicit system => {
        implicit val ec: ExecutionContext = system.dispatcher

        val importBatchWaiter = new Waiter()
        val suoritusWaiter = new Waiter()

        val s = perusopetusSuoritus(new LocalDate(2001, 1, 1))
        val batch = batchGeneratorEiValmistu.generate

        val arvosanatProcessing = new ArvosanatProcessing(
          createImportBatchOrgActor(system),
          createOrganisaatioActor,
          createHenkiloActor,
          system.actorOf(Props(new MockedResourceActor[Suoritus, UUID](save = {
            case s: VirallinenSuoritus =>
              suoritusWaiter {
                s.tila should be ("KESKEYTYNYT")
                s.valmistuminen should be (new LocalDate(2002, 1, 1))
              }
              suoritusWaiter.dismiss()
          }, query = {q => Seq(s)}))),
          system.actorOf(Props(new MockedResourceActor[Arvosana, UUID](save = {a => }, query = {q => Seq()}))),
          createImportBatchActor(system, (b: ImportBatch) => {
            importBatchWaiter { b.state should be (BatchState.DONE) }
            importBatchWaiter.dismiss()
          }, batch),
          createKoodistoActor,
          mockConfig
        )

        val status = Await.result(arvosanatProcessing.process(batch), awaitTimeout).status
        status.messages shouldBe empty
        importBatchWaiter.await(waiterTimeout, dismissals(1))
        suoritusWaiter.await(waiterTimeout, dismissals(1))
      }
    )
  }

  it should "import ei valmistu lisaopetus with korotettu arvosana" in {
    withSystem(
      implicit system => {
        implicit val ec: ExecutionContext = system.dispatcher

        val importBatchWaiter = new Waiter()
        val suoritusWaiter = new Waiter()
        val arvosanaWaiter = new Waiter()

        val l = lisaopetusSuoritus(new LocalDate(2015, 5, 30))
        val batch = batchGeneratorKymppiEiValmistuKorotuksilla.generate

        val arvosanatProcessing = new ArvosanatProcessing(
          createImportBatchOrgActor(system),
          createOrganisaatioActor,
          createHenkiloActor,
          system.actorOf(Props(new MockedResourceActor[Suoritus, UUID](save = {
            case s: VirallinenSuoritus =>
              suoritusWaiter {
                s.tila should be ("KESKEYTYNYT")
                s.valmistuminen should be (new LocalDate(2015, 3, 1))
              }
              suoritusWaiter.dismiss()
          }, query = {q => Seq(l)}))),
          system.actorOf(Props(new MockedResourceActor[Arvosana, UUID](save = a => {
            arvosanaWaiter {
              a.aine should be ("MA")
              a.arvio.asInstanceOf[Arvio410].arvosana should be ("10")
            }
            arvosanaWaiter.dismiss()
          }, query = {q => Seq()}))),
          createImportBatchActor(system, (b: ImportBatch) => {
            importBatchWaiter { b.state should be (BatchState.DONE) }
            importBatchWaiter.dismiss()
          }, batch),
          createKoodistoActor,
          mockConfig
        )

        val status = Await.result(arvosanatProcessing.process(batch), awaitTimeout).status
        status.messages shouldBe empty
        importBatchWaiter.await(waiterTimeout, dismissals(1))
        suoritusWaiter.await(waiterTimeout, dismissals(1))
        arvosanaWaiter.await(waiterTimeout, dismissals(1))
      }
    )
  }

  it should "report a row error if no matching suoritus was found" in {
    withSystem(
      implicit system => {
        implicit val ec: ExecutionContext = system.dispatcher

        val importBatchWaiter = new Waiter()
        val batch = batchGenerator.generate
        val arvosanatProcessing = new ArvosanatProcessing(
          createImportBatchOrgActor(system),
          createOrganisaatioActor,
          createHenkiloActor,
          system.actorOf(Props(new MockedResourceActor[Suoritus, UUID](
            save = {s => },
            query = {q => Seq()}
          ))),
          system.actorOf(Props(new MockedResourceActor[Arvosana, UUID](save = {a => }, query = {q => Seq()}))),
          createImportBatchActor(system, (b: ImportBatch) => {
            importBatchWaiter { b.state should be (BatchState.DONE) }
            importBatchWaiter.dismiss()
          }, batch),
          createKoodistoActor,
          mockConfig
        )

        val saved = Await.result(arvosanatProcessing.process(batch), awaitTimeout)
        saved.status.messages("111111-111L").find(_.contains("SuoritusNotFoundException")) should not be None
        importBatchWaiter.await(waiterTimeout, dismissals(1))
      }
    )
  }

  it should "report a row error if multiple matching suoritus were found" in {
    withSystem(
      implicit system => {
        implicit val ec: ExecutionContext = system.dispatcher

        val importBatchWaiter = new Waiter()
        val batch = batchGenerator.generate
        val arvosanatProcessing = new ArvosanatProcessing(
          createImportBatchOrgActor(system),
          createOrganisaatioActor,
          createHenkiloActor,
          system.actorOf(Props(new MockedResourceActor[Suoritus, UUID](
            save = {s => },
            query = {q => Seq(
              perusopetusSuoritus(new LocalDate(2001, 1, 1)),
              lukioSuoritus(new LocalDate(2001, 1, 1))
            )}
          ))),
          system.actorOf(Props(new MockedResourceActor[Arvosana, UUID](save = {a => }, query = {q => Seq()}))),
          createImportBatchActor(system, (b: ImportBatch) => {
            importBatchWaiter { b.state should be (BatchState.DONE) }
            importBatchWaiter.dismiss()
          }, batch),
          createKoodistoActor,
          mockConfig
        )

        val saved = Await.result(arvosanatProcessing.process(batch), awaitTimeout)
        saved.status.messages("111111-111L").find(_.contains("MultipleSuoritusException")) should not be None
        importBatchWaiter.await(waiterTimeout, dismissals(1))
      }
    )
  }

  it should "report a row error if arvosana save fails" in {
    withSystem(
      implicit system => {
        implicit val ec: ExecutionContext = system.dispatcher

        val importBatchWaiter = new Waiter()

        val batch = batchGenerator.generate

        val arvosanatProcessing = new ArvosanatProcessing(
          createImportBatchOrgActor(system),
          createOrganisaatioActor,
          createHenkiloActor,
          system.actorOf(Props(new MockedResourceActor[Suoritus, UUID](save = {s => }, query = {q => Seq(perusopetusSuoritus(new LocalDate(2001, 1, 1)))}))),
          system.actorOf(Props(new FailingResourceActor[Arvosana]())),
          createImportBatchActor(system, (b: ImportBatch) => {
            importBatchWaiter { b.state should be (BatchState.DONE) }
            importBatchWaiter.dismiss()
          }, batch),
          createKoodistoActor,
          mockConfig
        )

        val saved = Await.result(arvosanatProcessing.process(batch), awaitTimeout)
        saved.status.messages("111111-111L").find(_.contains("test save exception")) should not be None
        importBatchWaiter.await(waiterTimeout, dismissals(1))
      }
    )
  }

  it should "create a valma suoritus" in {
    withSystem(
      implicit system => {
        implicit val ec: ExecutionContext = system.dispatcher

        val importBatchWaiter = new Waiter()
        val suoritusWaiter = new Waiter()
        val arvosanaWaiter = new Waiter()

        var v = valmaSuoritus(new LocalDate(2016, 1, 1))
        val batch = batchGeneratorValma.generate

        val arvosanatProcessing = new ArvosanatProcessing(
          createImportBatchOrgActor(system),
          createOrganisaatioActor,
          createHenkiloActor,
          system.actorOf(Props(new MockedResourceActor[Suoritus, UUID](save = {
            case s: VirallinenSuoritus =>
              suoritusWaiter {
                s.tila should be ("VALMIS")
                s.komo should be (Oids.valmaKomoOid)
                s.valmistuminen should be (new LocalDate(2016, 6, 4))
              }
              v = v.identify(s.id)
              suoritusWaiter.dismiss()
          }, query = {q => Seq(v)}))),
          system.actorOf(Props(new MockedResourceActor[Arvosana, UUID](save = a => {
            arvosanaWaiter {
              a.suoritus should be (v.id)
            }
            arvosanaWaiter.dismiss()
          }, query = {q => Seq()}))),
          createImportBatchActor(system, (b: ImportBatch) => {
            importBatchWaiter { b.state should be (BatchState.DONE) }
            importBatchWaiter.dismiss()
          }, batch),
          createKoodistoActor,
          mockConfig
        )

        val status = Await.result(arvosanatProcessing.process(batch), awaitTimeout).status
        status.messages shouldBe empty
        importBatchWaiter.await(waiterTimeout, dismissals(1))
        suoritusWaiter.await(waiterTimeout, dismissals(1))
        arvosanaWaiter.await(waiterTimeout, dismissals(2))
      }
    )
  }

  it should "create a lukio suoritus if it does not exist" in {
    withSystem(
      implicit system => {
        implicit val ec: ExecutionContext = system.dispatcher

        val suoritusWaiter = new Waiter()

        val batch = batchGeneratorLukio.generate

        val arvosanatProcessing = new ArvosanatProcessing(
          createImportBatchOrgActor(system),
          createOrganisaatioActor,
          createHenkiloActor,
          system.actorOf(Props(new MockedResourceActor[Suoritus, UUID](save = (s: Suoritus) => {
            suoritusWaiter { s.asInstanceOf[VirallinenSuoritus].komo should be (Oids.lukioKomoOid) }
            suoritusWaiter.dismiss()
          }, query = {q => Seq()}))),
          system.actorOf(Props(new MockedResourceActor[Arvosana, UUID](save = {a => }, query = {q => Seq()}))),
          createImportBatchActor(system, { r => }, batch),
          createKoodistoActor,
          mockConfig
        )

        Await.result(arvosanatProcessing.process(batch), awaitTimeout)
        suoritusWaiter.await(waiterTimeout, dismissals(1))
      }
    )
  }

  it should "set correct index numbers for optional grades" in {
    withSystem(
      implicit system => {
        implicit val ec: ExecutionContext = system.dispatcher
        implicit val database = Database.forURL(ItPostgres.getEndpointURL)

        val batch = batchGenerator.generate

        var s = perusopetusSuoritus(new LocalDate(2001, 1, 1))

        val arvosanaJournal = new JDBCJournal[Arvosana, UUID, ArvosanaTable](TableQuery[ArvosanaTable], config = mockConfig)
        val arvosanaActor = system.actorOf(Props(new ArvosanaJDBCActor(arvosanaJournal, 1, mockConfig)))

        val arvosanatProcessing = new ArvosanatProcessing(
          createImportBatchOrgActor(system),
          createOrganisaatioActor,
          createHenkiloActor,
          system.actorOf(Props(new MockedResourceActor[Suoritus, UUID](save = {ss =>
            s = s.identify(ss.id)
          }, query = {q => Seq(s)}))),
          arvosanaActor,
          createImportBatchActor(system, {r => }, batch),
          createKoodistoActor,
          mockConfig
        )

        Await.result(arvosanatProcessing.process(batch), awaitTimeout)

        val savedArvosanat: Seq[Arvosana] =
          Await.result((arvosanaActor ? ArvosanaQuery(s.id))(akka.util.Timeout(1, TimeUnit.MINUTES)).mapTo[Seq[Arvosana]], awaitTimeout)

        savedArvosanat should contain (
          Arvosana(
            suoritus = s.id,
            arvio = Arvio410("6"),
            aine = "A1",
            lisatieto = Some("EN"),
            valinnainen = true,
            myonnetty = None,
            source = lahde,
            lahdeArvot = Map(),
            jarjestys = Some(0)
          )
        )

        savedArvosanat should contain (
            Arvosana(
              suoritus = s.id,
              arvio = Arvio410("8"),
              aine = "A1",
              lisatieto = Some("EN"),
              valinnainen = true,
              myonnetty = None,
              source = lahde,
              lahdeArvot = Map(),
              jarjestys = Some(1)
            )
          )

        savedArvosanat should contain (
            Arvosana(
              suoritus = s.id,
              arvio = Arvio410("10"),
              aine = "A1",
              lisatieto = Some("EN"),
              valinnainen = true,
              myonnetty = None,
              source = lahde,
              lahdeArvot = Map(),
              jarjestys = Some(2)
            )
          )

        savedArvosanat should contain (
            Arvosana(
              suoritus = s.id,
              arvio = Arvio410("6"),
              aine = "MA",
              lisatieto = None,
              valinnainen = true,
              myonnetty = None,
              source = lahde,
              lahdeArvot = Map(),
              jarjestys = Some(0)
            )
          )

        savedArvosanat should contain (
            Arvosana(
              suoritus = s.id,
              arvio = Arvio410("8"),
              aine = "MA",
              lisatieto = None,
              valinnainen = true,
              myonnetty = None,
              source = lahde,
              lahdeArvot = Map(),
              jarjestys = Some(1)
            )
          )

        database.close()
      }
    )
  }

  private def createKoodistoActor(implicit system: ActorSystem): KoodistoActorRef =
    new KoodistoActorRef(system.actorOf(Props(new MockedKoodistoActor())))

  private def perusopetusSuoritus(valmistuminen: LocalDate): VirallinenSuoritus with Identified[UUID] =
    VirallinenSuoritus(Oids.perusopetusKomoOid, "1.2.246.562.5.05127", "KESKEN", valmistuminen, "1.2.246.562.24.123",
      yksilollistaminen.Ei, "FI", None, vahv = true, lahde).identify(UUID.randomUUID())

  private def lisaopetusSuoritus(valmistuminen: LocalDate): VirallinenSuoritus with Identified[UUID] =
    VirallinenSuoritus(Oids.lisaopetusKomoOid, "1.2.246.562.5.05127", "KESKEN", valmistuminen, "1.2.246.562.24.123",
      yksilollistaminen.Ei, "FI", None, vahv = true, lahde).identify(UUID.randomUUID())

  private def valmaSuoritus(valmistuminen: LocalDate): VirallinenSuoritus with Identified[UUID] =
    VirallinenSuoritus(Oids.valmaKomoOid, "1.2.246.562.5.05127", "KESKEN", valmistuminen, "1.2.246.562.24.123",
      yksilollistaminen.Ei, "FI", None, vahv = true, lahde).identify(UUID.randomUUID())

  private def lukioSuoritus(valmistuminen: LocalDate): VirallinenSuoritus with Identified[UUID] =
    VirallinenSuoritus(Oids.lukioKomoOid, "1.2.246.562.5.05127", "KESKEN", valmistuminen, "1.2.246.562.24.123",
      yksilollistaminen.Ei, "FI", None, vahv = true, lahde).identify(UUID.randomUUID())

  private def createImportBatchActor(system: ActorSystem, batchSaveHandler: (ImportBatch) => Unit, batch: ImportBatch with Identified[UUID]): ActorRef =
    system.actorOf(Props(new MockedResourceActor[ImportBatch, UUID](save = batchSaveHandler, query = {q => Seq(batch)})))

  private def createImportBatchOrgActor(system: ActorSystem): ActorRef =
    system.actorOf(Props(new ImportBatchOrgActor(Database.forURL(ItPostgres.getEndpointURL), mockConfig)))

  private def createOrganisaatioActor(implicit system: ActorSystem, ec: ExecutionContext): OrganisaatioActorRef =
    new OrganisaatioActorRef(system.actorOf(Props(new HttpOrganisaatioActor(
      new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/organisaatio-service"), Some(asyncClient)),
      new MockConfig, MockCacheFactory.get
    ))))

  private def createHenkiloActor(implicit system: ActorSystem, ec: ExecutionContext): HenkiloActorRef =
    new HenkiloActorRef(system.actorOf(Props(new HttpHenkiloActor(
      new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/oppijanumerorekisteri-service"), Some(asyncClient)),
      new MockConfig
    ))))

  object Fixtures {
    val lahde = "testiarvosanatiedonsiirto"
    val batchGenerator = new DataGen[ImportBatch with Identified[UUID]] {
      val xml = <arvosanat>
        <eranTunniste>PKERA3_2015S_05127</eranTunniste>
        <henkilot>
          <henkilo>
            <hetu>111111-111L</hetu>
            <sukunimi>foo</sukunimi>
            <etunimet>bar k</etunimet>
            <kutsumanimi>bar</kutsumanimi>
            <todistukset>
              <perusopetus>
                <valmistuminen>2001-01-01</valmistuminen>
                <myontaja>05127</myontaja>
                <suorituskieli>FI</suorituskieli>
                <AI>
                  <yhteinen>5</yhteinen>
                  <tyyppi>FI</tyyppi>
                </AI>
                <A1>
                  <yhteinen>5</yhteinen>
                  <valinnainen>6</valinnainen>
                  <valinnainen>8</valinnainen>
                  <valinnainen>10</valinnainen>
                  <kieli>EN</kieli>
                </A1>
                <B1>
                  <yhteinen>5</yhteinen>
                  <kieli>SV</kieli>
                </B1>
                <MA>
                  <yhteinen>5</yhteinen>
                  <valinnainen>6</valinnainen>
                  <valinnainen>8</valinnainen>
                </MA>
                <KS>
                  <yhteinen>5</yhteinen>
                  <valinnainen>6</valinnainen>
                </KS>
                <KE>
                  <yhteinen>5</yhteinen>
                </KE>
                <KU>
                  <yhteinen>5</yhteinen>
                </KU>
                <KO>
                  <yhteinen>5</yhteinen>
                </KO>
                <BI>
                  <yhteinen>5</yhteinen>
                </BI>
                <MU>
                  <yhteinen>5</yhteinen>
                </MU>
                <LI>
                  <yhteinen>5</yhteinen>
                </LI>
                <HI>
                  <yhteinen>5</yhteinen>
                </HI>
                <FY>
                  <yhteinen>5</yhteinen>
                </FY>
                <YH>
                  <yhteinen>5</yhteinen>
                </YH>
                <TE>
                  <yhteinen>5</yhteinen>
                </TE>
                <KT>
                  <yhteinen>5</yhteinen>
                </KT>
                <GE>
                  <yhteinen>5</yhteinen>
                </GE>
              </perusopetus>
            </todistukset>
          </henkilo>
        </henkilot>
      </arvosanat>
      override def generate: ImportBatch with Identified[UUID] =
        ImportBatch(xml, Some("foo"), "arvosanat", lahde, BatchState.READY, ImportStatus()).identify(UUID.randomUUID())
    }

    val batchGeneratorValma = new DataGen[ImportBatch with Identified[UUID]] {
      val xml = <arvosanat>
        <eranTunniste>PKERA3_2015S_05127</eranTunniste>
        <henkilot>
          <henkilo>
            <hetu>111111-111L</hetu>
            <sukunimi>foo</sukunimi>
            <etunimet>bar k</etunimet>
            <kutsumanimi>bar</kutsumanimi>
            <todistukset>
              <valma>
                <valmistuminen>2016-06-04</valmistuminen>
                <myontaja>05127</myontaja>
                <suorituskieli>FI</suorituskieli>
                <AI>
                  <yhteinen>5</yhteinen>
                  <tyyppi>FI</tyyppi>
                </AI>
                <A1>
                  <yhteinen>5</yhteinen>
                  <valinnainen>6</valinnainen>
                  <valinnainen>8</valinnainen>
                  <valinnainen>10</valinnainen>
                  <kieli>EN</kieli>
                </A1>
              </valma>
            </todistukset>
          </henkilo>
        </henkilot>
      </arvosanat>
      override def generate: ImportBatch with Identified[UUID] =
        ImportBatch(xml, Some("foo"), "arvosanat", lahde, BatchState.READY, ImportStatus()).identify(UUID.randomUUID())
    }

    val batchGeneratorUpdatedValmistuminen = new DataGen[ImportBatch with Identified[UUID]] {
      val xml = <arvosanat>
        <eranTunniste>PKERA3_2015S_05127</eranTunniste>
        <henkilot>
          <henkilo>
            <hetu>111111-111L</hetu>
            <sukunimi>foo</sukunimi>
            <etunimet>bar k</etunimet>
            <kutsumanimi>bar</kutsumanimi>
            <todistukset>
              <perusopetus>
                <valmistuminen>2001-02-02</valmistuminen>
                <myontaja>05127</myontaja>
                <suorituskieli>FI</suorituskieli>
                <AI>
                  <yhteinen>5</yhteinen>
                  <tyyppi>FI</tyyppi>
                </AI>
                <A1>
                  <yhteinen>5</yhteinen>
                  <valinnainen>6</valinnainen>
                  <valinnainen>8</valinnainen>
                  <valinnainen>10</valinnainen>
                  <kieli>EN</kieli>
                </A1>
                <B1>
                  <yhteinen>5</yhteinen>
                  <kieli>SV</kieli>
                </B1>
                <MA>
                  <yhteinen>5</yhteinen>
                  <valinnainen>6</valinnainen>
                  <valinnainen>8</valinnainen>
                </MA>
                <KS>
                  <yhteinen>5</yhteinen>
                  <valinnainen>6</valinnainen>
                </KS>
                <KE>
                  <yhteinen>5</yhteinen>
                </KE>
                <KU>
                  <yhteinen>5</yhteinen>
                </KU>
                <KO>
                  <yhteinen>5</yhteinen>
                </KO>
                <BI>
                  <yhteinen>5</yhteinen>
                </BI>
                <MU>
                  <yhteinen>5</yhteinen>
                </MU>
                <LI>
                  <yhteinen>5</yhteinen>
                </LI>
                <HI>
                  <yhteinen>5</yhteinen>
                </HI>
                <FY>
                  <yhteinen>5</yhteinen>
                </FY>
                <YH>
                  <yhteinen>5</yhteinen>
                </YH>
                <TE>
                  <yhteinen>5</yhteinen>
                </TE>
                <KT>
                  <yhteinen>5</yhteinen>
                </KT>
                <GE>
                  <yhteinen>5</yhteinen>
                </GE>
              </perusopetus>
            </todistukset>
          </henkilo>
        </henkilot>
      </arvosanat>
      override def generate: ImportBatch with Identified[UUID] =
        ImportBatch(xml, Some("foo"), "arvosanat", lahde, BatchState.READY, ImportStatus()).identify(UUID.randomUUID())
    }

    val batchGeneratorLuokalleJaanyt = new DataGen[ImportBatch with Identified[UUID]] {
      val xml = <arvosanat>
        <eranTunniste>PKERA3_2015S_05127</eranTunniste>
        <henkilot>
          <henkilo>
            <hetu>111111-111L</hetu>
            <sukunimi>foo</sukunimi>
            <etunimet>bar k</etunimet>
            <kutsumanimi>bar</kutsumanimi>
            <todistukset>
              <perusopetus>
                <myontaja>05127</myontaja>
                <suorituskieli>FI</suorituskieli>
                <oletettuvalmistuminen>2002-01-01</oletettuvalmistuminen>
                <valmistuminensiirtyy>JAA LUOKALLE</valmistuminensiirtyy>
              </perusopetus>
            </todistukset>
          </henkilo>
        </henkilot>
      </arvosanat>
      override def generate: ImportBatch with Identified[UUID] =
        ImportBatch(xml, Some("foo1"), "arvosanat", lahde, BatchState.READY, ImportStatus()).identify(UUID.randomUUID())
    }

    val batchGeneratorEiValmistu = new DataGen[ImportBatch with Identified[UUID]] {
      val xml = <arvosanat>
        <eranTunniste>PKERA3_2015S_05127</eranTunniste>
        <henkilot>
          <henkilo>
            <hetu>111111-111L</hetu>
            <sukunimi>foo</sukunimi>
            <etunimet>bar k</etunimet>
            <kutsumanimi>bar</kutsumanimi>
            <todistukset>
              <perusopetus>
                <myontaja>05127</myontaja>
                <suorituskieli>FI</suorituskieli>
                <opetuspaattynyt>2002-01-01</opetuspaattynyt>
                <eivalmistu>PERUSOPETUS PAATTYNYT VALMISTUMATTA</eivalmistu>
              </perusopetus>
            </todistukset>
          </henkilo>
        </henkilot>
      </arvosanat>
      override def generate: ImportBatch with Identified[UUID] =
        ImportBatch(xml, Some("foo2"), "arvosanat", lahde, BatchState.READY, ImportStatus()).identify(UUID.randomUUID())
    }

    val batchGeneratorKymppiEiValmistuKorotuksilla = new DataGen[ImportBatch with Identified[UUID]] {
      val xml = <arvosanat>
        <eranTunniste>PKERA3_2015S_05127</eranTunniste>
        <henkilot>
          <henkilo>
            <hetu>111111-111L</hetu>
            <sukunimi>foo</sukunimi>
            <etunimet>bar k</etunimet>
            <kutsumanimi>bar</kutsumanimi>
            <todistukset>
              <perusopetuksenlisaopetus>
                <myontaja>05127</myontaja>
                <suorituskieli>FI</suorituskieli>
                <valmistuminen>2015-03-01</valmistuminen>
                <MA>
                  <yhteinen>10</yhteinen>
                </MA>
                <eivalmistu>SUORITUS HYLATTY</eivalmistu>
              </perusopetuksenlisaopetus>
            </todistukset>
          </henkilo>
        </henkilot>
      </arvosanat>
      override def generate: ImportBatch with Identified[UUID] =
        ImportBatch(xml, Some("foo2"), "arvosanat", lahde, BatchState.READY, ImportStatus()).identify(UUID.randomUUID())
    }

    val batchGeneratorLukio = new DataGen[ImportBatch with Identified[UUID]] {
      val xmlLukio = <arvosanat>
        <eranTunniste>LKERA3_2015S_05127</eranTunniste>
        <henkilot>
          <henkilo>
            <hetu>111111-111L</hetu>
            <sukunimi>foo</sukunimi>
            <etunimet>bar k</etunimet>
            <kutsumanimi>bar</kutsumanimi>
            <todistukset>
              <lukio>
                <valmistuminen>2004-01-01</valmistuminen>
                <myontaja>05127</myontaja>
                <suorituskieli>FI</suorituskieli>
                <AI>
                  <yhteinen>5</yhteinen>
                  <tyyppi>FI</tyyppi>
                </AI>
                <A1>
                  <yhteinen>5</yhteinen>
                  <kieli>EN</kieli>
                </A1>
                <B1>
                  <yhteinen>5</yhteinen>
                  <kieli>SV</kieli>
                </B1>
                <MA>
                  <yhteinen>5</yhteinen>
                  <laajuus>lyhyt</laajuus>
                </MA>
                <BI>
                  <yhteinen>5</yhteinen>
                </BI>
                <GE>
                  <yhteinen>5</yhteinen>
                </GE>
                <FY>
                  <yhteinen>5</yhteinen>
                  <laajuus>pitkä</laajuus>
                </FY>
                <KE>
                  <yhteinen>5</yhteinen>
                </KE>
                <TE>
                  <yhteinen>5</yhteinen>
                </TE>
                <KT>
                  <yhteinen>5</yhteinen>
                </KT>
                <HI>
                  <yhteinen>5</yhteinen>
                </HI>
                <YH>
                  <yhteinen>5</yhteinen>
                </YH>
                <MU>
                  <yhteinen>5</yhteinen>
                </MU>
                <KU>
                  <yhteinen>5</yhteinen>
                </KU>
                <LI>
                  <yhteinen>5</yhteinen>
                </LI>
                <PS>
                  <yhteinen>5</yhteinen>
                </PS>
                <FI>
                  <yhteinen>5</yhteinen>
                </FI>
              </lukio>
            </todistukset>
          </henkilo>
        </henkilot>
      </arvosanat>
      override def generate: ImportBatch with Identified[UUID] =
        ImportBatch(xmlLukio, Some("foo3"), "arvosanat", lahde, BatchState.READY, ImportStatus()).identify(UUID.randomUUID())
    }

    val batchGeneratorJaaLuokalle = new DataGen[ImportBatch with Identified[UUID]] {
      val xml = <arvosanat>
        <eranTunniste>PKERA3_2015S_05127</eranTunniste>
        <henkilot>
          <henkilo>
            <hetu>111111-111L</hetu>
            <sukunimi>foo</sukunimi>
            <etunimet>bar k</etunimet>
            <kutsumanimi>bar</kutsumanimi>
            <todistukset>
              <perusopetus>
                <oletusvalmistuminen>2017-06-06</oletusvalmistuminen>
                <valmistuminensiirtyy>JAA LUOKALLE</valmistuminensiirtyy>
                <myontaja>05127</myontaja>
                <suorituskieli>FI</suorituskieli>
              </perusopetus>
            </todistukset>
          </henkilo>
        </henkilot>
      </arvosanat>
      override def generate: ImportBatch with Identified[UUID] =
        ImportBatch(xml, Some("foo3"), "arvosanat", lahde, BatchState.READY, ImportStatus()).identify(UUID.randomUUID())
    }

    private def createEndpoint = {
      val result = mock[Endpoint]
      when(result.request(forUrl("http://localhost/oppijanumerorekisteri-service/s2s/findOrCreateHenkiloPerustieto")))
        .thenReturn((200, List(), "{\"oidHenkilo\":\"1.2.246.562.24.123\"}"))
      when(result.request(
        forUrl("http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true")
      )).thenReturn((200, List(), "{\"numHits\":1,\"organisaatiot\":[{\"oid\":\"1.2.246.562.5.05127\",\"nimi\":{},\"oppilaitosKoodi\":\"05127\"}]}"))
      when(result.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/05127")))
        .thenReturn((200, List(), "{\"oid\":\"1.2.246.562.5.05127\",\"nimi\":{},\"oppilaitosKoodi\":\"05127\"}"))
      result
    }
    private val endpoint = createEndpoint
    val asyncClient = new CapturingAsyncHttpClient(endpoint)
  }
}

class MockedKoodistoActor
  extends Actor {
  override def receive: Actor.Receive = {
    case q: GetKoodistoKoodiArvot => q.koodistoUri match {
      case "oppiaineetyleissivistava" => sender ! KoodistoKoodiArvot(
        koodistoUri = "oppiaineetyleissivistava",
        arvot = Seq("AI", "A1", "A12", "A2", "A22", "B1", "B2", "B22", "B23", "B3", "B32", "B33", "BI", "FI","FY", "GE",
          "HI", "KE", "KO", "KS", "KT", "KU", "LI", "MA", "MU", "PS", "TE", "YH")
      )
      case "kieli" => sender ! KoodistoKoodiArvot(
        koodistoUri = "kieli",
        arvot = Seq("FI", "SV", "EN")
      )
    }
  }
}
