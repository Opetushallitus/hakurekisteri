package support

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{Backoff, BackoffSupervisor}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.henkilo._
import fi.vm.sade.hakurekisteri.integration.koodisto.KoodistoActor
import fi.vm.sade.hakurekisteri.integration.kooste.{IKoosteService, KoosteService, KoosteServiceMock}
import fi.vm.sade.hakurekisteri.integration.organisaatio.{HttpOrganisaatioActor, MockOrganisaatioActor}
import fi.vm.sade.hakurekisteri.integration.parametrit.{HttpParameterActor, MockParameterActor}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{MockTarjontaActor, TarjontaActor}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{ValintarekisteriActor, ValintarekisteriQuery}
import fi.vm.sade.hakurekisteri.integration.valintatulos.ValintaTulosActor
import fi.vm.sade.hakurekisteri.integration.virta._
import fi.vm.sade.hakurekisteri.integration.ytl._
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, VirkailijaRestClient, _}
import fi.vm.sade.hakurekisteri.rest.support.Registers
import fi.vm.sade.hakurekisteri.tools.LambdaJob.lambdaJob
import fi.vm.sade.hakurekisteri.web.proxies.{HttpProxies, MockProxies, Proxies}
import org.quartz.CronScheduleBuilder._
import org.quartz.TriggerBuilder._
import org.quartz.impl.StdSchedulerFactory
import org.slf4j.LoggerFactory
import support.YtlRerunPolicy.rerunPolicy

import scala.concurrent.duration._
import scala.util.{Failure, Try}

trait Integrations {
  val hakuAppPermissionChecker: ActorRef
  val virta: ActorRef
  val virtaResource: ActorRef
  val henkilo: ActorRef
  val organisaatiot: ActorRef
  val hakemusService: IHakemusService
  val koosteService: IKoosteService
  val tarjonta: ActorRef
  val koodisto: ActorRef
  val ytl: ActorRef
  val ytlIntegration: YtlIntegration
  val ytlHttp: YtlHttpFetch
  val parametrit: ActorRef
  val valintaTulos: ActorRef
  val valintarekisteri: ActorRef
  val proxies: Proxies
  val hakemusClient: VirkailijaRestClient
  val oppijaNumeroRekisteri: IOppijaNumeroRekisteri
}

object Integrations {
  def apply(rekisterit: Registers,
            system: ActorSystem,
            config: Config): Integrations = config.mockMode match {
    case true => new MockIntegrations(rekisterit, system, config)
    case _ => new BaseIntegrations(rekisterit, system, config)
  }
}

class MockIntegrations(rekisterit: Registers, system: ActorSystem, config: Config) extends Integrations {
  override val virta: ActorRef = mockActor("virta", new DummyActor)
  override val virtaResource: ActorRef = mockActor("virtaResource", new MockVirtaResourceActor)
  override val valintaTulos: ActorRef = mockActor("valintaTulos", new DummyActor)
  override val valintarekisteri: ActorRef = mockActor("valintarekisteri", new Actor {
    override def receive: Receive = {
      case ValintarekisteriQuery(_, _) => sender ! Seq()
      case a => println(s"DummyActor($self): received $a")
    }
  })
  override val hakemusService = new HakemusServiceMock
  override val koosteService = new KoosteServiceMock
  override val koodisto: ActorRef = mockActor("koodisto", new DummyActor)
  override val organisaatiot: ActorRef = mockActor("organisaatiot", new MockOrganisaatioActor(config))
  override val parametrit: ActorRef = mockActor("parametrit", new MockParameterActor)
  override val henkilo: ActorRef = mockActor("henkilo", new MockHenkiloActor(config))
  override val tarjonta: ActorRef = mockActor("tarjonta", new MockTarjontaActor(config))
  override val ytl: ActorRef = system.actorOf(Props(new YtlActor(
    rekisterit.ytlSuoritusRekisteri,
    rekisterit.ytlArvosanaRekisteri,
    hakemusService,
    config.integrations.ytlConfig
  )), "ytl")
  val ytlFileSystem = YtlFileSystem(OphUrlProperties)
  override val ytlHttp = new YtlHttpFetch(OphUrlProperties, ytlFileSystem)
  override val ytlIntegration = new YtlIntegration(OphUrlProperties, ytlHttp, hakemusService, ytl)

  override val proxies = new MockProxies
  override val hakemusClient = null

  private def mockActor(name: String, actor: => Actor) = system.actorOf(Props(actor), name)

  override val hakuAppPermissionChecker: ActorRef = system.actorOf(Props(new Actor {
    override def receive: Receive = {
      case a: HasPermission => sender ! true
    }
  }))
  override val oppijaNumeroRekisteri: IOppijaNumeroRekisteri = MockOppijaNumeroRekisteri
}


class BaseIntegrations(rekisterit: Registers,
                       system: ActorSystem,
                       config: Config) extends Integrations {
  private val logger = LoggerFactory.getLogger(getClass)
  val restEc = ExecutorUtil.createExecutor(10, "rest-client-pool")
  val vtsEc = ExecutorUtil.createExecutor(5, "valinta-tulos-client-pool")
  val vrEc = ExecutorUtil.createExecutor(10, "valintarekisteri-client-pool")
  val virtaEc = ExecutorUtil.createExecutor(1, "virta-client-pool")
  val virtaResourceEc = ExecutorUtil.createExecutor(5, "virta-resource-client-pool")

  system.registerOnTermination(() => {
    restEc.shutdown()
    vtsEc.shutdown()
    vrEc.shutdown()
    virtaEc.shutdown()
    restEc.awaitTermination(3, TimeUnit.SECONDS)
    vtsEc.awaitTermination(3, TimeUnit.SECONDS)
    vrEc.awaitTermination(3, TimeUnit.SECONDS)
    virtaEc.awaitTermination(3, TimeUnit.SECONDS)
    virtaResourceEc.awaitTermination(3, TimeUnit.SECONDS)
  })

  private val tarjontaClient = new VirkailijaRestClient(config.integrations.tarjontaConfig, None)(restEc, system)
  private val organisaatioClient = new VirkailijaRestClient(config.integrations.organisaatioConfig, None)(restEc, system)
  private val koodistoClient = new VirkailijaRestClient(config.integrations.koodistoConfig, None)(restEc, system)
  private val henkiloClient = new VirkailijaRestClient(config.integrations.henkiloConfig, None)(restEc, system)
  val hakemusClient = new VirkailijaRestClient(config.integrations.hakemusConfig.serviceConf, None)(restEc, system)
  val ataruHakemusClient = new VirkailijaRestClient(config.integrations.ataruConfig, None, jSessionName = "ring-session",
    serviceUrlSuffix = "/auth/cas")(restEc, system)
  private val koosteClient = new VirkailijaRestClient(config.integrations.koosteConfig, None)(restEc, system)
  private val parametritClient = new VirkailijaRestClient(config.integrations.parameterConfig, None)(restEc, system)
  private val valintatulosClient = new VirkailijaRestClient(config.integrations.valintaTulosConfig, None)(vtsEc, system)
  private val valintarekisteriClient = new VirkailijaRestClient(config.integrations.valintarekisteriConfig, None)(vrEc, system)
  private val hakuAppPermissionCheckerClient = new VirkailijaRestClient(config.integrations.hakemusConfig.serviceConf.copy(
    casUrl = None, user = None, password = None
  ), None)(restEc, system)

  def getSupervisedActorFor(props: Props, name: String) = system.actorOf(BackoffSupervisor.props(
    Backoff.onStop(
      props,
      childName = name,
      minBackoff = 3.seconds,
      maxBackoff = 30.seconds,
      randomFactor = 0.2
    )), name)

  val tarjonta = getSupervisedActorFor(Props(new TarjontaActor(tarjontaClient, config)), "tarjonta")
  val organisaatiot = getSupervisedActorFor(Props(new HttpOrganisaatioActor(organisaatioClient, config)), "organisaatio")
  val henkilo = system.actorOf(Props(new fi.vm.sade.hakurekisteri.integration.henkilo.HttpHenkiloActor(henkiloClient, config)), "henkilo")
  override val oppijaNumeroRekisteri: IOppijaNumeroRekisteri = new OppijaNumeroRekisteri(new VirkailijaRestClient(config.integrations.oppijaNumeroRekisteriConfig, None)(restEc, system), system)
  val hakemusService = new HakemusService(hakemusClient, oppijaNumeroRekisteri)(system)
  val koosteService = new KoosteService(koosteClient)(system)
  val koodisto = system.actorOf(Props(new KoodistoActor(koodistoClient, config)), "koodisto")
  val parametrit = system.actorOf(Props(new HttpParameterActor(parametritClient)), "parametrit")
  val valintaTulos = getSupervisedActorFor(Props(new ValintaTulosActor(valintatulosClient, config)), "valintaTulos")
  val valintarekisteri = system.actorOf(Props(new ValintarekisteriActor(valintarekisteriClient, config)), "valintarekisteri")
  val ytl = system.actorOf(Props(new YtlActor(
    rekisterit.ytlSuoritusRekisteri,
    rekisterit.ytlArvosanaRekisteri,
    hakemusService,
    config.integrations.ytlConfig
  )), "ytl")
  val ytlFileSystem = YtlFileSystem(OphUrlProperties)
  override val ytlHttp = new YtlHttpFetch(OphUrlProperties, ytlFileSystem)
  val ytlIntegration = new YtlIntegration(OphUrlProperties, ytlHttp, hakemusService, ytl)
  private val virtaClient = new VirtaClient(
    config = config.integrations.virtaConfig,
    apiVersion = config.properties.getOrElse("suoritusrekisteri.virta.apiversio", VirtaClient.version105)
  )(virtaEc, system)
  private val virtaResourceClient = new VirtaClient(
    config = config.integrations.virtaConfig,
    apiVersion = config.properties.getOrElse("suoritusrekisteri.virta.apiversio", VirtaClient.version106)
  )(virtaResourceEc, system)
  val virta = system.actorOf(Props(new VirtaActor(virtaClient, organisaatiot, rekisterit.suoritusRekisteri, rekisterit.opiskeluoikeusRekisteri)), "virta")
  val virtaResource = system.actorOf(Props(new VirtaResourceActor(virtaResourceClient)), "virtaResource")
  val proxies = new HttpProxies(valintarekisteriClient)

  val arvosanaTrigger: Trigger = IlmoitetutArvosanatTrigger(rekisterit.suoritusRekisteri, rekisterit.arvosanaRekisteri)(system.dispatcher)
  val ytlTrigger: Trigger = Trigger { (hakemus, personOidsWithAliases: PersonOidsWithAliases) => Try(ytlIntegration.sync(hakemus)) match {
      case Failure(e) =>
        logger.error(s"YTL sync failed for hakemus with OID ${hakemus.oid}", e)
      case _ => // pass
    }
  }
  hakemusService.addTrigger(arvosanaTrigger)
  hakemusService.addTrigger(ytlTrigger)

  implicit val scheduler = system.scheduler
  hakemusService.processModifiedHakemukset()

  val quartzScheduler = StdSchedulerFactory.getDefaultScheduler()
  quartzScheduler.start()

  val syncAllCronExpression = OphUrlProperties.getProperty("ytl.http.syncAllCronJob")
  val rerunSync = rerunPolicy(syncAllCronExpression, ytlIntegration)
  quartzScheduler.scheduleJob(lambdaJob(rerunSync),
    newTrigger().startNow().withSchedule(cronSchedule(syncAllCronExpression)).build());
  override val hakuAppPermissionChecker: ActorRef = system.actorOf(Props(new HakuAppPermissionCheckerActor(hakuAppPermissionCheckerClient, organisaatiot)))

}
