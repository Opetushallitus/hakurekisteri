package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorSystem, Props}
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.arvosana.Arvosana
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloActor
import fi.vm.sade.hakurekisteri.integration.koodisto.{GetKoodistoKoodiArvot, KoodistoKoodiArvot}
import fi.vm.sade.hakurekisteri.integration.organisaatio.OrganisaatioActor
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus, yksilollistaminen}
import fi.vm.sade.hakurekisteri.test.tools.{FailingResourceActor, MockedResourceActor}
import generators.DataGen
import org.joda.time.LocalDate
import org.mockito.Mockito._
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

class ArvosanatProcessingSpec extends FlatSpec with Matchers with MockitoSugar with DispatchSupport with AsyncAssertions with HakurekisteriJsonSupport {

  behavior of "ArvosanaProcessing"

  import Fixtures._

  it should "resolve data from henkilopalvelu, organisaatiopalvelu and suoritusrekisteri, and then import data into arvosanarekisteri" in {
    implicit val system = ActorSystem("test-import-arvosana-batch-processing")
    implicit val ec: ExecutionContext = system.dispatcher

    val henkiloClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/authentication-service"), Some(new AsyncHttpClient(asyncProvider)))
    val henkiloActor = system.actorOf(Props(new HenkiloActor(henkiloClient)))
    val koodistoActor = system.actorOf(Props(new MockedKoodistoActor()))

    val suoritus = VirallinenSuoritus(Config.perusopetusKomoOid, "1.2.246.562.5.05127", "KESKEN", new LocalDate(2001, 1, 1), "1.2.246.562.24.123", yksilollistaminen.Ei, "FI", None, vahv = true, lahde).identify(UUID.randomUUID())
    val suoritusrekisteri = system.actorOf(Props(new MockedResourceActor[Suoritus](save = {r => r}, query = {q => Seq(suoritus)})))
    val organisaatioClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/organisaatio-service"), Some(new AsyncHttpClient(asyncProvider)))
    val organisaatioActor = system.actorOf(Props(new OrganisaatioActor(organisaatioClient)))

    val aWaiter = new Waiter()
    val arvosanaHandler = (a: Arvosana) => {
      aWaiter { a.suoritus should be (suoritus.id) }
      aWaiter.dismiss()
    }
    val arvosanarekisteri = system.actorOf(Props(new MockedResourceActor[Arvosana](save = arvosanaHandler, query = { (q) => Seq() })))

    val iWaiter = new Waiter()
    val batchSaveHandler = (b: ImportBatch) => {
      iWaiter { b.state should be (BatchState.DONE) }
      iWaiter.dismiss()
    }
    val batch = batchGenerator.generate
    val importBatchActor = system.actorOf(Props(new MockedResourceActor[ImportBatch](save = batchSaveHandler, query = { (q) => Seq(batch) })))

    val arvosanatProcessing = new ArvosanatProcessing(organisaatioActor, henkiloActor, suoritusrekisteri, arvosanarekisteri, importBatchActor, koodistoActor)
    arvosanatProcessing.process(batch)

    import org.scalatest.time.SpanSugar._
    aWaiter.await(timeout(30.seconds), dismissals(22))
    iWaiter.await(timeout(30.seconds), dismissals(1))

    system.shutdown()
    system.awaitTermination()
  }

  it should "report a row error if no matching suoritus was found" in {
    implicit val system = ActorSystem("test-import-arvosana-batch-processing")
    implicit val ec: ExecutionContext = system.dispatcher

    val henkiloClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/authentication-service"), Some(new AsyncHttpClient(asyncProvider)))
    val henkiloActor = system.actorOf(Props(new HenkiloActor(henkiloClient)))
    val koodistoActor = system.actorOf(Props(new MockedKoodistoActor()))

    val suoritus = VirallinenSuoritus(Config.perusopetusKomoOid, "1.2.246.562.5.05127", "KESKEN", new LocalDate(2002, 1, 1), "1.2.246.562.24.123", yksilollistaminen.Ei, "FI", None, vahv = true, lahde).identify(UUID.randomUUID())
    val suoritusrekisteri = system.actorOf(Props(new MockedResourceActor[Suoritus](save = {r => r}, query = {q => Seq(suoritus)})))
    val organisaatioClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/organisaatio-service"), Some(new AsyncHttpClient(asyncProvider)))
    val organisaatioActor = system.actorOf(Props(new OrganisaatioActor(organisaatioClient)))

    val arvosanarekisteri = system.actorOf(Props(new MockedResourceActor[Arvosana](save = {r => r}, query = { (q) => Seq() })))

    val iWaiter = new Waiter()
    val batchSaveHandler = (b: ImportBatch) => {
      iWaiter { b.state should be (BatchState.DONE) }
      iWaiter.dismiss()
    }
    val batch = batchGenerator.generate
    val importBatchActor = system.actorOf(Props(new MockedResourceActor[ImportBatch](save = batchSaveHandler, query = { (q) => Seq(batch) })))

    val arvosanatProcessing = new ArvosanatProcessing(organisaatioActor, henkiloActor, suoritusrekisteri, arvosanarekisteri, importBatchActor, koodistoActor)
    val saved = Await.result(arvosanatProcessing.process(batch), Duration(30, TimeUnit.SECONDS))
    saved.status.messages("111111-111L").find(_.contains("SuoritusNotFoundException")) should not be None

    import org.scalatest.time.SpanSugar._
    iWaiter.await(timeout(30.seconds), dismissals(1))

    system.shutdown()
    system.awaitTermination()
  }

  it should "report a row error if arvosana save fails" in {
    implicit val system = ActorSystem("test-import-arvosana-batch-processing")
    implicit val ec: ExecutionContext = system.dispatcher

    val henkiloClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/authentication-service"), Some(new AsyncHttpClient(asyncProvider)))
    val henkiloActor = system.actorOf(Props(new HenkiloActor(henkiloClient)))
    val koodistoActor = system.actorOf(Props(new MockedKoodistoActor()))

    val suoritus = VirallinenSuoritus(Config.perusopetusKomoOid, "1.2.246.562.5.05127", "KESKEN", new LocalDate(2001, 1, 1), "1.2.246.562.24.123", yksilollistaminen.Ei, "FI", None, vahv = true, lahde).identify(UUID.randomUUID())
    val suoritusrekisteri = system.actorOf(Props(new MockedResourceActor[Suoritus](save = {r => r}, query = {q => Seq(suoritus)})))
    val organisaatioClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/organisaatio-service"), Some(new AsyncHttpClient(asyncProvider)))
    val organisaatioActor = system.actorOf(Props(new OrganisaatioActor(organisaatioClient)))

    val arvosanarekisteri = system.actorOf(Props(new FailingResourceActor[Arvosana]()))

    val iWaiter = new Waiter()
    val batchSaveHandler = (b: ImportBatch) => {
      iWaiter { b.state should be (BatchState.DONE) }
      iWaiter.dismiss()
    }
    val batch = batchGenerator.generate
    val importBatchActor = system.actorOf(Props(new MockedResourceActor[ImportBatch](save = batchSaveHandler, query = { (q) => Seq(batch) })))

    val arvosanatProcessing = new ArvosanatProcessing(organisaatioActor, henkiloActor, suoritusrekisteri, arvosanarekisteri, importBatchActor, koodistoActor)
    val saved = Await.result(arvosanatProcessing.process(batch), Duration(30, TimeUnit.SECONDS))
    saved.status.messages("111111-111L").find(_.contains("test save exception")) should not be None

    import org.scalatest.time.SpanSugar._
    iWaiter.await(timeout(30.seconds), dismissals(1))

    system.shutdown()
    system.awaitTermination()
  }

  it should "create a lukio suoritus if it does not exist" in {
    implicit val system = ActorSystem("test-import-arvosana-batch-processing")
    implicit val ec: ExecutionContext = system.dispatcher

    val henkiloClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/authentication-service"), Some(new AsyncHttpClient(asyncProvider)))
    val henkiloActor = system.actorOf(Props(new HenkiloActor(henkiloClient)))
    val koodistoActor = system.actorOf(Props(new MockedKoodistoActor()))

    val sWaiter = new Waiter()
    val suoritusHandler = (s: Suoritus) => {
      sWaiter { s.asInstanceOf[VirallinenSuoritus].komo should be (Config.lukioKomoOid) }
      sWaiter.dismiss()
    }
    val suoritusrekisteri = system.actorOf(Props(new MockedResourceActor[Suoritus](save = suoritusHandler, query = {q => {println("qqq"); Seq()}})))
    val organisaatioClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/organisaatio-service"), Some(new AsyncHttpClient(asyncProvider)))
    val organisaatioActor = system.actorOf(Props(new OrganisaatioActor(organisaatioClient)))

    val arvosanarekisteri = system.actorOf(Props(new MockedResourceActor[Arvosana](save = {r => r}, query = { (q) => Seq() })))
    val batch = batchGeneratorLukio.generate
    val importBatchActor = system.actorOf(Props(new MockedResourceActor[ImportBatch](save = {r => r}, query = { (q) => Seq(batch) })))

    val arvosanatProcessing = new ArvosanatProcessing(organisaatioActor, henkiloActor, suoritusrekisteri, arvosanarekisteri, importBatchActor, koodistoActor)
    arvosanatProcessing.process(batch)

    import org.scalatest.time.SpanSugar._
    sWaiter.await(timeout(10.seconds), dismissals(1))

    system.shutdown()
    system.awaitTermination()
  }

  object Fixtures {
    val lahde = "testiarvosanatiedonsiirto"
    val batchGenerator = new DataGen[ImportBatch with Identified[UUID]] {
      val xml = <arvosanat>
        <eranTunniste>PKERA3_2015S_05127</eranTunniste>
        <henkilot>
          <henkilo>
            <hetu>111111-111L</hetu>
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

    val batchGeneratorLukio = new DataGen[ImportBatch with Identified[UUID]] {
      val xmlLukio = <arvosanat>
        <eranTunniste>LKERA3_2015S_05127</eranTunniste>
        <henkilot>
          <henkilo>
            <hetu>111111-111L</hetu>
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
        ImportBatch(xmlLukio, Some("foo2"), "arvosanat", lahde, BatchState.READY, ImportStatus()).identify(UUID.randomUUID())
    }

    def createEndpoint = {
      val result = mock[Endpoint]
      when(result.request(forUrl("http://localhost/authentication-service/resources/henkilo?q=111111-111L&index=0&count=2&no=true&s=true"))).thenReturn((200, List(), "{\"totalCount\":1,\"results\":[{\"oidHenkilo\":\"1.2.246.562.24.123\",\"henkiloTyyppi\":\"OPPIJA\"}]}"))
      when(result.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"))).thenReturn((200, List(), "{\"numHits\":1,\"organisaatiot\":[{\"oid\":\"1.2.246.562.5.05127\",\"nimi\":{},\"oppilaitosKoodi\":\"05127\"}]}"))
      when(result.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/05127"))).thenReturn((200, List(), "{\"oid\":\"1.2.246.562.5.05127\",\"nimi\":{},\"oppilaitosKoodi\":\"05127\"}"))
      result
    }
    val endpoint = createEndpoint
    val asyncProvider = new CapturingProvider(endpoint)
  }
}

class MockedKoodistoActor extends Actor {
  override def receive: Actor.Receive = {
    case q: GetKoodistoKoodiArvot => q.koodistoUri match {
      case "oppiaineetyleissivistava" => sender ! KoodistoKoodiArvot(
        koodistoUri = "oppiaineetyleissivistava",
        arvot = Seq("AI", "A1", "A12", "A2", "A22", "B1", "B2", "B22", "B23", "B3", "B32", "B33", "BI", "FI","FY", "GE", "HI", "KE", "KO", "KS", "KT", "KU", "LI", "MA", "MU", "PS", "TE", "YH")
      )
    }
  }
}
