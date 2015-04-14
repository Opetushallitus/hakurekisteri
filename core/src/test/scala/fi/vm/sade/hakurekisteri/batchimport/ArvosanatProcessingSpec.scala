package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
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
import org.scalatest.time.SpanSugar._
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

class ArvosanatProcessingSpec extends FlatSpec with Matchers with MockitoSugar with DispatchSupport with AsyncAssertions with HakurekisteriJsonSupport {

  behavior of "ArvosanaProcessing"

  import Fixtures._

  it should "resolve data from henkilopalvelu, organisaatiopalvelu and suoritusrekisteri, and then import data into arvosanarekisteri" in {
    withSystem(
      implicit system => {
        implicit val ec: ExecutionContext = system.dispatcher

        val arvosanaWaiter = new Waiter()
        val importBatchWaiter = new Waiter()

        val s = suoritus(new LocalDate(2001, 1, 1))
        val batch = batchGenerator.generate

        val arvosanatProcessing = new ArvosanatProcessing(
          createOrganisaatioActor,
          createHenkiloActor,
          system.actorOf(Props(new MockedResourceActor[Suoritus](save = {s => s}, query = {q => Seq(s)}))),
          system.actorOf(Props(new MockedResourceActor[Arvosana](save = (a: Arvosana) => {
            arvosanaWaiter { a.suoritus should be (s.id) }
            arvosanaWaiter.dismiss()
          }, query = {q => Seq()}))),
          createImportBatchActor(system, (b: ImportBatch) => {
            importBatchWaiter { b.state should be (BatchState.DONE) }
            importBatchWaiter.dismiss()
          }, batch),
          createKoodistoActor
        )

        arvosanatProcessing.process(batch)

        arvosanaWaiter.await(timeout(30.seconds), dismissals(22))
        importBatchWaiter.await(timeout(30.seconds), dismissals(1))
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
          createOrganisaatioActor,
          createHenkiloActor,
          system.actorOf(Props(new MockedResourceActor[Suoritus](save = {s => s}, query = {q => Seq(suoritus(new LocalDate(2002, 1, 1)))}))),
          system.actorOf(Props(new MockedResourceActor[Arvosana](save = {a => a}, query = {q => Seq()}))),
          createImportBatchActor(system, (b: ImportBatch) => {
            importBatchWaiter { b.state should be (BatchState.DONE) }
            importBatchWaiter.dismiss()
          }, batch),
          createKoodistoActor
        )

        val saved = Await.result(arvosanatProcessing.process(batch), Duration(30, TimeUnit.SECONDS))

        saved.status.messages("111111-111L").find(_.contains("SuoritusNotFoundException")) should not be None

        importBatchWaiter.await(timeout(30.seconds), dismissals(1))
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
          createOrganisaatioActor,
          createHenkiloActor,
          system.actorOf(Props(new MockedResourceActor[Suoritus](save = {s => s}, query = {q => Seq(suoritus(new LocalDate(2001, 1, 1)))}))),
          system.actorOf(Props(new FailingResourceActor[Arvosana]())),
          createImportBatchActor(system, (b: ImportBatch) => {
            importBatchWaiter { b.state should be (BatchState.DONE) }
            importBatchWaiter.dismiss()
          }, batch),
          createKoodistoActor
        )

        val saved = Await.result(arvosanatProcessing.process(batch), Duration(30, TimeUnit.SECONDS))

        saved.status.messages("111111-111L").find(_.contains("test save exception")) should not be None

        importBatchWaiter.await(timeout(30.seconds), dismissals(1))
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
          createOrganisaatioActor,
          createHenkiloActor,
          system.actorOf(Props(new MockedResourceActor[Suoritus](save = (s: Suoritus) => {
            suoritusWaiter { s.asInstanceOf[VirallinenSuoritus].komo should be (Config.lukioKomoOid) }
            suoritusWaiter.dismiss()
          }, query = {q => Seq()}))),
          system.actorOf(Props(new MockedResourceActor[Arvosana](save = {a => a}, query = {q => Seq()}))),
          createImportBatchActor(system, { r => r }, batch),
          createKoodistoActor
        )

        arvosanatProcessing.process(batch)

        suoritusWaiter.await(timeout(10.seconds), dismissals(1))
      }
    )
  }

  private def createKoodistoActor(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(new MockedKoodistoActor()))

  private def suoritus(valmistuminen: LocalDate): VirallinenSuoritus with Identified[UUID] =
    VirallinenSuoritus(Config.perusopetusKomoOid, "1.2.246.562.5.05127", "KESKEN", valmistuminen, "1.2.246.562.24.123", yksilollistaminen.Ei, "FI", None, vahv = true, lahde).identify(UUID.randomUUID())

  private def createImportBatchActor(system: ActorSystem, batchSaveHandler: (ImportBatch) => Unit, batch: ImportBatch with Identified[UUID]): ActorRef =
    system.actorOf(Props(new MockedResourceActor[ImportBatch](save = batchSaveHandler, query = {q => Seq(batch)})))

  private def createOrganisaatioActor(implicit system: ActorSystem, ec: ExecutionContext): ActorRef =
    system.actorOf(Props(new OrganisaatioActor(new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/organisaatio-service"), Some(new AsyncHttpClient(asyncProvider))))))

  private def createHenkiloActor(implicit system: ActorSystem, ec: ExecutionContext): ActorRef =
    system.actorOf(Props(new HenkiloActor(new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/authentication-service"), Some(new AsyncHttpClient(asyncProvider))))))

  private def withSystem(f: ActorSystem => Unit) = {
    val system = ActorSystem("test-import-arvosana-batch-processing")

    f(system)

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
        ImportBatch(xmlLukio, Some("foo2"), "arvosanat", lahde, BatchState.READY, ImportStatus()).identify(UUID.randomUUID())
    }

    def createEndpoint = {
      val result = mock[Endpoint]
      when(result.request(forUrl("http://localhost/authentication-service/resources/s2s/tiedonsiirrot"))).thenReturn((200, List(), "1.2.246.562.24.123"))
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
