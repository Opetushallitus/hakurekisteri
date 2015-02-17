package siirto

import akka.actor._
import fi.vm.sade.hakurekisteri.batchimport.{PerustiedotProcessingActor, BatchState}
import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.SuoritusActor
import fi.vm.sade.hakurekisteri.opiskelija.OpiskelijaActor
import akka.util.Timeout
import scala.concurrent.{Future, ExecutionContext, Await}
import org.scalameter.api._
import scala.xml.XML
import fi.vm.sade.hakurekisteri.batchimport.BatchState._
import fi.vm.sade.hakurekisteri.integration.organisaatio._
import akka.actor.Status.Failure
import scala.Some
import fi.vm.sade.hakurekisteri.integration.henkilo.{HenkiloActor, SavedHenkilo, SaveHenkilo, UpdateHenkilo}
import fi.vm.sade.hakurekisteri.batchimport.ImportBatch
import fi.vm.sade.hakurekisteri.batchimport.ImportStatus
import java.io.{ObjectInputStream, ObjectOutputStream, IOException}
import scala.concurrent.duration._
import fi.vm.sade.hakurekisteri.integration._
import com.ning.http.client.{AsyncHandler, Request, AsyncHttpClient}
import fi.vm.sade.hakurekisteri.integration.ServiceConfig
import akka.actor.Status.Failure
import scala.Some
import fi.vm.sade.hakurekisteri.integration.henkilo.SavedHenkilo
import fi.vm.sade.hakurekisteri.integration.henkilo.SaveHenkilo
import fi.vm.sade.hakurekisteri.batchimport.ImportBatch
import fi.vm.sade.hakurekisteri.integration.henkilo.UpdateHenkilo
import fi.vm.sade.hakurekisteri.batchimport.ImportStatus
import fi.vm.sade.hakurekisteri.integration.EndpointRequest
import fi.vm.sade.hakurekisteri.integration.ServiceConfig
import akka.actor.Status.Failure
import scala.Some
import fi.vm.sade.hakurekisteri.integration.henkilo.UpdateHenkilo
import fi.vm.sade.hakurekisteri.batchimport.ImportStatus
import fi.vm.sade.hakurekisteri.integration.henkilo.SavedHenkilo
import fi.vm.sade.hakurekisteri.integration.henkilo.SaveHenkilo
import fi.vm.sade.hakurekisteri.batchimport.ImportBatch
import fi.vm.sade.hakurekisteri.integration.EndpointRequest
import fi.vm.sade.hakurekisteri.integration.ServiceConfig
import akka.actor.Status.Failure
import scala.Some
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.integration.henkilo.UpdateHenkilo
import fi.vm.sade.hakurekisteri.batchimport.ImportStatus
import fi.vm.sade.hakurekisteri.integration.organisaatio.Oppilaitos
import fi.vm.sade.hakurekisteri.integration.henkilo.SavedHenkilo
import fi.vm.sade.hakurekisteri.integration.henkilo.SaveHenkilo
import fi.vm.sade.hakurekisteri.batchimport.ImportBatch
import fi.vm.sade.hakurekisteri.integration.organisaatio.OppilaitosResponse
import scala.util.Try


object PerustiedotSiirtoLoadBenchmark extends PerformanceTest.Microbenchmark {

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
  </perustiedot>, Some("eranTunniste"), "perustiedot", lahde, BatchState.READY, ImportStatus()).identify(UUID.randomUUID())

  val batchGen = Gen.single[SerializableBatch]("batch")(new SerializableBatch(batch))


  trait Registers extends Serializable {


    var systemHolder: Option[ActorSystem] = None
    var importBatchActorHolder: Option[ActorRef] = None
    var suoritusrekisteriHolder: Option[ActorRef] = None
    var opiskelijarekisteriHolder: Option[ActorRef] = None
    var henkiloActorHolder: Option[ActorRef] = None
    var organisaatioActorHolder: Option[ActorRef] = None



    def start {
      if (systemHolder.isDefined) {
        system.shutdown()
      }
      systemHolder = Some(ActorSystem(s"perf-test-${UUID.randomUUID()}"))
      startActors
    }

    def startActors:Unit

    def system = systemHolder.get
    def importBatchActor = importBatchActorHolder.get
    def suoritusrekisteri = suoritusrekisteriHolder.get
    def opiskelijarekisteri = opiskelijarekisteriHolder.get
    def henkiloActor = henkiloActorHolder.get
    def organisaatioActor = organisaatioActorHolder.get


  }

  val PerustiedotImportProps = (regs: Registers, b:ImportBatch with Identified[UUID]) =>  Props(new PerustiedotProcessingActor(regs.importBatchActor, regs.henkiloActor, regs.suoritusrekisteri, regs.opiskelijarekisteri, regs.organisaatioActor)(b))

  import akka.pattern.ask

  implicit val timeout: Timeout = 1.minute


  performance of "PerustiedotProcessing" in {

    measure method "processSingle" in {

      val registers = new Registers{
        override def startActors {
          importBatchActorHolder = Some(system.actorOf(Props[BatchActor]))
          suoritusrekisteriHolder = Some(system.actorOf(Props(new SuoritusActor())))
          opiskelijarekisteriHolder = Some(system.actorOf(Props(new OpiskelijaActor())))
          henkiloActorHolder = Some(system.actorOf(Props[TestHenkiloActor]))
          organisaatioActorHolder = Some(system.actorOf(Props[TestOrganisaatioActor]))
        }
      }

      using(batchGen) setUp {
        _ =>
          registers.start
      } tearDown {
        _ =>
          registers.system.shutdown()
          registers.system.awaitTermination()
      } in {
        b =>
          processSingleBatch(registers, b)
      }


    }

    measure method "processSingleWithHenkilotAndOrganisaatiot" in {

      val registers = new Registers{
        override def startActors {
          importBatchActorHolder = Some(system.actorOf(Props[BatchActor]))
          suoritusrekisteriHolder = Some(system.actorOf(Props(new SuoritusActor())))
          opiskelijarekisteriHolder = Some(system.actorOf(Props(new OpiskelijaActor())))
          val endPoint: Endpoint = new Endpoint {
            override def request(er: EndpointRequest): (Int, List[(String, String)], String) = (200, List(), "1.2.246.562.24.123")
          }
          val asyncProvider =  new DelayingProvider(endPoint, 20.milliseconds)(system.dispatcher, system.scheduler)
          val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/authentication-service"), Some(new AsyncHttpClient(asyncProvider)))(system.dispatcher, system)


          henkiloActorHolder = Some(system.actorOf(Props(new HenkiloActor(client))))

          val orgEndPoint = new Endpoint {
            override def request(er: EndpointRequest): (Int, List[(String, String)], String) = er match {
              case EndpointRequest("http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true", _, _ )  => (200, List(), "{\"numHits\":1,\"organisaatiot\":[{\"oid\":\"1.2.246.562.5.05127\",\"nimi\":{},\"oppilaitosKoodi\":\"05127\"}]}")
              case EndpointRequest("http://localhost/organisaatio-service/rest/organisaatio/05127",_,_) => (200, List(), "{\"oid\":\"1.2.246.562.5.05127\",\"nimi\":{},\"oppilaitosKoodi\":\"05127\"}")
              case default => (404, List(), "Not Found")
            }
          }
          val orgProvider = new DelayingProvider(orgEndPoint, 20.milliseconds)(system.dispatcher, system.scheduler)
          val organisaatioClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/organisaatio-service"), Some(new AsyncHttpClient(orgProvider)))(system.dispatcher, system)
          organisaatioActorHolder = Some(system.actorOf(Props(new OrganisaatioActor(organisaatioClient))))
        }
      }

      using(batchGen) setUp {
        _ =>
          registers.start
      } tearDown {
        _ =>
          registers.system.shutdown()
          registers.system.awaitTermination()
      } in {
        b =>
          processSingleBatch(registers, b)
      }


    }
  }


  def processSingleBatch(registers: PerustiedotSiirtoLoadBenchmark.Registers with Object {def startActorsUnit}, b: PerustiedotSiirtoLoadBenchmark.SerializableBatch): Any = {
    val doneFut = registers.importBatchActor ? AlertEnd(b.batch.externalId.get)
    registers.system.actorOf(PerustiedotImportProps(registers, b.batch))
    Await.result(doneFut, Duration.Inf)
  }

  case class AlertEnd(id: String)

  class BatchActor extends Actor {

    var alerts = Map[String, ActorRef]()
    override def receive: Actor.Receive = {
      case AlertEnd(id) =>  alerts = alerts + (id -> sender)
      case ImportBatch(_, Some(id), _ , _ , BatchState.DONE, _) if alerts.contains(id) => alerts(id) ! "done"
      case ImportBatch(_, Some(id), _ , _ , BatchState.FAILED, _) if alerts.contains(id) => alerts(id) ! Failure(new RuntimeException("batch failed"))
    }
  }

  class TestActor extends Actor {
    override def receive: Actor.Receive = Map()
  }

  class TestHenkiloActor extends Actor with ActorLogging {


    implicit val ec:ExecutionContext = context.dispatcher

    override def receive: Actor.Receive = {

      case SaveHenkilo(_, tunniste) =>
        log.debug(s"saving $tunniste")
        sender ! SavedHenkilo("1.2.3.4.5", tunniste)

      case UpdateHenkilo(oid, _) =>
        log.debug(s"checking $oid")
        sender ! SavedHenkilo(oid, oid)
    }
  }

  class TestOrganisaatioActor extends Actor with ActorLogging {
    override def receive: Actor.Receive = {
      case Oppilaitos(koodi) =>
        log.debug(s"fetching $koodi")

        sender ! OppilaitosResponse(koodi, Organisaatio("1.2.3", Map("fi" -> "testikoulu"), None, Some(koodi), None, Seq()))

    }
  }


  class SerializableBatch(var batch:ImportBatch with Identified[UUID]) extends Serializable {

    @throws(classOf[IOException])
    private def writeObject(out: ObjectOutputStream): Unit = {
      val xml = batch.data.toString
      val serialized =(xml, batch.externalId, batch.batchType, batch.source, batch.state, batch.status, batch.id)
      out.writeObject(serialized)
    }

    @throws(classOf[IOException])
    private def readObject(in: ObjectInputStream): Unit = {
      val obj: AnyRef = in.readObject()
      obj match {
        case (xml:String, externalId: Option[String], batchType:String ,source: String, state: BatchState, status: ImportStatus, id:UUID) =>
          batch = ImportBatch(XML.loadString(xml), externalId, batchType, source, state, status).identify(id)
        case _ => sys.error("wrong object type")
      }
    }

    override def toString: String = batch.toString
  }

  class DelayingProvider( endpoint: Endpoint, delay: FiniteDuration)(implicit val ec: ExecutionContext, scheduler: Scheduler) extends CapturingProvider(endpoint) {
    override def executeScala[T](request: Request, handler: AsyncHandler[T]): Future[T] = {
      import akka.pattern.after
      after(delay, scheduler)(super.executeScala(request, handler))


    }
  }

}


