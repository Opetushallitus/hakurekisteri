package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.arvosana.Arvosana
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.henkilo.{CreateHenkilo, HttpHenkiloActor}
import fi.vm.sade.hakurekisteri.integration.organisaatio.HttpOrganisaatioActor
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus}
import fi.vm.sade.hakurekisteri.test.tools.MockedResourceActor
import org.mockito.Mockito._
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class ImportBatchProcessingActorSpec extends FlatSpec with Matchers with MockitoSugar with DispatchSupport with AsyncAssertions with HakurekisteriJsonSupport {
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
          <tila>KESKEN</tila>
          <yksilollistaminen>EI</yksilollistaminen>
        </perusopetus>
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
      when(result.request(forUrl("http://localhost/authentication-service/resources/s2s/tiedonsiirrot"))).thenReturn((500, List(), "error"))
      when(result.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"))).thenReturn((500, List(), "error"))
      when(result.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/05127"))).thenReturn((500, List(), "error"))
    } else {
      when(result.request(forUrl("http://localhost/authentication-service/resources/s2s/tiedonsiirrot"))).thenReturn((200, List(), "1.2.246.562.24.123"))
      when(result.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"))).thenReturn((200, List(), "{\"numHits\":1,\"organisaatiot\":[{\"oid\":\"1.2.246.562.5.05127\",\"nimi\":{},\"oppilaitosKoodi\":\"05127\"}]}"))
      when(result.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/05127"))).thenReturn((200, List(), "{\"oid\":\"1.2.246.562.5.05127\",\"nimi\":{},\"oppilaitosKoodi\":\"05127\"}"))
    }

    result
  }
  val asyncProvider = new CapturingProvider(createEndpoint(fail = false))
  val failingAsyncProvider = new CapturingProvider(createEndpoint(fail = true))


  it should "import data into henkilopalvelu and suoritusrekisteri" in {
    implicit val system = ActorSystem("test-import-batch-processing")
    implicit val ec: ExecutionContext = system.dispatcher

    val importBatchActor = system.actorOf(Props(new MockedResourceActor[ImportBatch, UUID](save = {r =>}, query = { (q) => Seq(batch) })))
    val henkiloClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/authentication-service"), Some(new AsyncHttpClient(asyncProvider)))
    val henkiloActor = system.actorOf(Props(new HttpHenkiloActor(henkiloClient, Config.mockConfig)))

    val sWaiter = new Waiter()
    val oWaiter = new Waiter()
    val suoritusHandler = (suoritus: Suoritus) => {
      sWaiter { suoritus.asInstanceOf[VirallinenSuoritus].myontaja should be ("1.2.246.562.5.05127") }
      sWaiter.dismiss()
    }
    val opiskelijaHandler = (o: Opiskelija) => {
      oWaiter { o.oppilaitosOid should be ("1.2.246.562.5.05127") }
      oWaiter.dismiss()
    }
    val suoritusrekisteri = system.actorOf(Props(new MockedResourceActor[Suoritus, UUID](save = suoritusHandler, query = {q => Seq()})))
    val opiskelijarekisteri = system.actorOf(Props(new MockedResourceActor[Opiskelija, UUID](save = opiskelijaHandler, query = {q => Seq()})))
    val organisaatioClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/organisaatio-service"), Some(new AsyncHttpClient(asyncProvider)))
    val organisaatioActor = system.actorOf(Props(new HttpOrganisaatioActor(organisaatioClient, Config.mockConfig)))
    val koodistoActor = system.actorOf(Props(new MockedKoodistoActor()))
    val arvosanarekisteri = system.actorOf(Props(new MockedResourceActor[Arvosana, UUID](save = {r => r}, query = { (q) => Seq() })))

    val processingActor = system.actorOf(Props(new ImportBatchProcessingActor(importBatchActor, henkiloActor, suoritusrekisteri, opiskelijarekisteri, organisaatioActor, arvosanarekisteri, koodistoActor, Config.mockConfig)))

    processingActor ! ProcessReadyBatches

    sWaiter.await(timeout(30.seconds), dismissals(1))
    oWaiter.await(timeout(30.seconds), dismissals(1))

    system.shutdown()
    system.awaitTermination()
  }

  it should "report error for failed henkilo save" in {
    implicit val system = ActorSystem("test-import-batch-processing")
    implicit val ec: ExecutionContext = system.dispatcher

    val iWaiter = new Waiter()
    val batchHandler = (b: ImportBatch) => {
      if (b.state != BatchState.PROCESSING) iWaiter {
        b.status.messages.keys should (contain ("111111-1975") and contain ("TUNNISTE") and have size 2)
      }
      iWaiter.dismiss()
    }
    val importBatchActor = system.actorOf(Props(new MockedResourceActor[ImportBatch, UUID](save = batchHandler, query = { (q) => Seq(batch) })))
    val henkiloClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/authentication-service"), Some(new AsyncHttpClient(failingAsyncProvider)))
    val henkiloActor = system.actorOf(Props(new HttpHenkiloActor(henkiloClient, Config.mockConfig)))
    val suoritusrekisteri = system.actorOf(Props(new MockedResourceActor[Suoritus, UUID](save = {r => r}, query = {q => Seq()})))
    val opiskelijarekisteri = system.actorOf(Props(new MockedResourceActor[Opiskelija, UUID](save = {r => r}, query = {q => Seq()})))
    val organisaatioClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/organisaatio-service"), Some(new AsyncHttpClient(asyncProvider)))
    val organisaatioActor = system.actorOf(Props(new HttpOrganisaatioActor(organisaatioClient, Config.mockConfig)))
    val koodistoActor = system.actorOf(Props(new MockedKoodistoActor()))
    val arvosanarekisteri = system.actorOf(Props(new MockedResourceActor[Arvosana, UUID](save = {r => r}, query = { (q) => Seq() })))

    val processingActor = system.actorOf(Props(new ImportBatchProcessingActor(importBatchActor, henkiloActor, suoritusrekisteri, opiskelijarekisteri, organisaatioActor, arvosanarekisteri, koodistoActor, Config.mockConfig)))

    processingActor ! ProcessReadyBatches

    iWaiter.await(timeout(30.seconds), dismissals(2))

    system.shutdown()
    system.awaitTermination()
  }

}
