package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem, Props}
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.{Config, KomoOids, MockCacheFactory, MockConfig}
import fi.vm.sade.hakurekisteri.arvosana.Arvosana
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.henkilo.{CreateHenkilo, HenkiloActorRef, HttpHenkiloActor}
import fi.vm.sade.hakurekisteri.integration.koodisto.KoodistoActorRef
import fi.vm.sade.hakurekisteri.integration.organisaatio.{HttpOrganisaatioActor, OrganisaatioActorRef}
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus}
import fi.vm.sade.hakurekisteri.test.tools.MockedResourceActor
import org.mockito.Mockito._
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

class ImportBatchProcessingActorSpec extends FlatSpec with Matchers with MockitoSugar with DispatchSupport with AsyncAssertions with HakurekisteriJsonSupport with LocalhostProperties {
  behavior of "ImportBatchProcessingActor"
  val lahde = "testitiedonsiirto"

  val batch: ImportBatch with Identified[UUID] = ImportBatch(<perustiedot>
    <eranTunniste>eranTunniste</eranTunniste>
    <henkilot>
      <henkilo>
        <hetu>111111-1975</hetu>
        <lahtokoulu>05127</lahtokoulu>
        <luokka>9A</luokka>
        <sukunimi>Testinen</sukunimi>
        <etunimet>Juha Jaakko</etunimet>
        <kutsumanimi>Jaakko</kutsumanimi>
        <kotikunta>020</kotikunta>
        <aidinkieli>FI</aidinkieli>
        <kansalaisuus>246</kansalaisuus>
        <lahiosoite>Katu 1 A 1</lahiosoite>
        <postinumero>00100</postinumero>
        <matkapuhelin>040 1234 567</matkapuhelin>
        <muuPuhelin>09 1234 567</muuPuhelin>
        <perusopetus>
          <valmistuminen>2015-06-04</valmistuminen>
          <myontaja>05127</myontaja>
          <suorituskieli>FI</suorituskieli>
          <tila>VALMIS</tila>
          <yksilollistaminen>EI</yksilollistaminen>
        </perusopetus>
        <valma>
          <valmistuminen>2016-06-04</valmistuminen>
          <myontaja>05127</myontaja>
          <suorituskieli>FI</suorituskieli>
          <tila>KESKEN</tila>
          <yksilollistaminen>EI</yksilollistaminen>
        </valma>
      </henkilo>
      <henkilo>
        <henkiloTunniste>TUNNISTE</henkiloTunniste>
        <syntymaAika>1999-03-29</syntymaAika>
        <sukupuoli>1</sukupuoli>
        <lahtokoulu>05127</lahtokoulu>
        <luokka>9A</luokka>
        <sukunimi>Testinen</sukunimi>
        <etunimet>Juha Jaakko</etunimet>
        <kutsumanimi>Jaakko</kutsumanimi>
        <kotikunta>020</kotikunta>
        <aidinkieli>FI</aidinkieli>
        <kansalaisuus>246</kansalaisuus>
        <lahiosoite>Katu 1 A 1</lahiosoite>
        <postinumero>00100</postinumero>
        <matkapuhelin>040 1234 567</matkapuhelin>
        <muuPuhelin>09 1234 567</muuPuhelin>
        <ulkomainen>
          <valmistuminen>2014-06-04</valmistuminen>
          <myontaja>05127</myontaja>
          <suorituskieli>FI</suorituskieli>
          <tila>KESKEN</tila>
        </ulkomainen>
        <maahanmuuttajienammvalmistava>
          <valmistuminen>2015-06-04</valmistuminen>
          <myontaja>05127</myontaja>
          <suorituskieli>FI</suorituskieli>
          <tila>VALMIS</tila>
        </maahanmuuttajienammvalmistava>
      </henkilo>
    </henkilot>
  </perustiedot>, Some("foo"), "perustiedot", lahde, BatchState.READY, ImportStatus()).identify(UUID.randomUUID())

  def createEndpoint(fail: Boolean) = {
    val result = mock[Endpoint]

    val henkiloBody = {
      val oidResolver = (koodi: String) => s"1.2.246.562.5.$koodi"
      val henkilo: CreateHenkilo = (batch.data \ "henkilot" \ "henkilo").map(ImportHenkilo(_)(lahde)).head.toHenkilo(oidResolver)
      import org.json4s.jackson.Serialization.write
      write[CreateHenkilo](henkilo)
    }

    if (fail) {
      when(result.request(forUrl("http://localhost/oppijanumerorekisteri-service/s2s/findOrCreateHenkiloPerustieto"))).thenReturn((500, List(), "error"))
      when(result.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"))).thenReturn((500, List(), "error"))
      when(result.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/05127"))).thenReturn((500, List(), "error"))
    } else {
      when(result.request(forUrl("http://localhost/oppijanumerorekisteri-service/s2s/findOrCreateHenkiloPerustieto"))).thenReturn((200, List(), "{\"oidHenkilo\": \"1.2.246.562.24.123\"}"))
      when(result.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"))).thenReturn((200, List(), "{\"numHits\":1,\"organisaatiot\":[{\"oid\":\"1.2.246.562.5.05127\",\"nimi\":{},\"oppilaitosKoodi\":\"05127\"}]}"))
      when(result.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/05127"))).thenReturn((200, List(), "{\"oid\":\"1.2.246.562.5.05127\",\"nimi\":{},\"oppilaitosKoodi\":\"05127\"}"))
    }

    result
  }
  val asyncProvider = new CapturingProvider(createEndpoint(fail = false))
  val failingAsyncProvider = new CapturingProvider(createEndpoint(fail = true))

  def createProcessingActor(suoritusHandler: (Suoritus) => Unit = (s: Suoritus) => {},
                            opiskelijaHandler: (Opiskelija) => Unit = (o: Opiskelija) => {},
                            batchHandler: (ImportBatch) => Unit = (i: ImportBatch) => {},
                            httpProvider: CapturingProvider = asyncProvider)(implicit system: ActorSystem): ActorRef = {
    implicit val ec: ExecutionContext = system.dispatcher
    val config = new MockConfig

    val importBatchOrgActor = system.actorOf(Props(new ImportBatchOrgActor(null)))
    val importBatchActor = system.actorOf(Props(new MockedResourceActor[ImportBatch, UUID](save = batchHandler, query = { (q) => Seq(batch) })))
    val onrClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/oppijanumerorekisteri-service"), Some(new AsyncHttpClient(httpProvider)))
    val henkiloActor = new HenkiloActorRef(system.actorOf(Props(new HttpHenkiloActor(onrClient, config))))
    val suoritusrekisteri = system.actorOf(Props(new MockedResourceActor[Suoritus, UUID](save = suoritusHandler, query = {q => Seq()})))
    val opiskelijarekisteri = system.actorOf(Props(new MockedResourceActor[Opiskelija, UUID](save = opiskelijaHandler, query = {q => Seq()})))
    val organisaatioClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/organisaatio-service"), Some(new AsyncHttpClient(httpProvider)))
    val cacheFactory = MockCacheFactory.get
    val organisaatioActor = new OrganisaatioActorRef(system.actorOf(Props(new HttpOrganisaatioActor(organisaatioClient, config, cacheFactory))))
    val koodistoActor = new KoodistoActorRef(system.actorOf(Props(new MockedKoodistoActor())))
    val arvosanarekisteri = system.actorOf(Props(new MockedResourceActor[Arvosana, UUID](save = {r => }, query = { (q) => Seq() })))

    system.actorOf(Props(new ImportBatchProcessingActor(importBatchOrgActor, importBatchActor, henkiloActor, suoritusrekisteri, opiskelijarekisteri, organisaatioActor, arvosanarekisteri, koodistoActor, config)))
  }


  it should "import data into henkilopalvelu and suoritusrekisteri" in {
    implicit val system = ActorSystem("test-import-batch-processing")

    val suoritusWaiter = new Waiter()
    val suoritusValmaWaiter = new Waiter()
    val opiskelijaWaiter = new Waiter()
    val luokkatasoValmaWaiter = new Waiter()

    val suoritusHandler = (suoritus: Suoritus) => suoritus match {
      case v: VirallinenSuoritus if v.komo == KomoOids.lisapistekoulutus.valma =>
        suoritusValmaWaiter { v.myontaja should be ("1.2.246.562.5.05127") }
        suoritusValmaWaiter.dismiss()
      case v: VirallinenSuoritus =>
        suoritusWaiter { v.myontaja should be ("1.2.246.562.5.05127") }
        suoritusWaiter.dismiss()
    }

    val opiskelijaHandler = (o: Opiskelija) => o.luokkataso match {
      case "VALMA" =>
        luokkatasoValmaWaiter { o.oppilaitosOid should be ("1.2.246.562.5.05127") }
        luokkatasoValmaWaiter.dismiss()
      case _ =>
        opiskelijaWaiter { o.oppilaitosOid should be ("1.2.246.562.5.05127") }
        opiskelijaWaiter.dismiss()
    }

    val processingActor = createProcessingActor(suoritusHandler, opiskelijaHandler)

    processingActor ! ProcessReadyBatches

    suoritusWaiter.await(timeout(30.seconds), dismissals(3))
    suoritusValmaWaiter.await(timeout(30.seconds), dismissals(1))
    opiskelijaWaiter.await(timeout(30.seconds), dismissals(1))
    luokkatasoValmaWaiter.await(timeout(30.seconds), dismissals(1))

    Await.result(system.terminate(), 15.seconds)
  }

  it should "report error for failed henkilo save" in {
    implicit val system = ActorSystem("test-import-batch-processing")

    val batchWaiter = new Waiter()

    val batchHandler = (b: ImportBatch) => {
      if (b.state != BatchState.PROCESSING) batchWaiter {
        b.status.messages.keys should (contain ("111111-1975") and contain ("TUNNISTE") and have size 2)
      }
      batchWaiter.dismiss()
    }

    val processingActor = createProcessingActor(batchHandler = batchHandler, httpProvider = failingAsyncProvider)

    processingActor ! ProcessReadyBatches

    batchWaiter.await(timeout(30.seconds), dismissals(1))

    Await.result(system.terminate(), 15.seconds)
  }

}
