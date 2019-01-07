package support

import java.util.Date
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{Backoff, BackoffSupervisor}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.henkilo._
import fi.vm.sade.hakurekisteri.integration.koodisto.{KoodistoActor, KoodistoActorRef}
import fi.vm.sade.hakurekisteri.integration.kooste.{IKoosteService, KoosteService, KoosteServiceMock}
import fi.vm.sade.hakurekisteri.integration.koski._
import fi.vm.sade.hakurekisteri.integration.organisaatio.{HttpOrganisaatioActor, MockOrganisaatioActor, OrganisaatioActorRef}
import fi.vm.sade.hakurekisteri.integration.parametrit.{HttpParameterActor, MockParameterActor, ParametritActorRef}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{MockTarjontaActor, TarjontaActor, TarjontaActorRef}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{ValintarekisteriActor, ValintarekisteriActorRef, ValintarekisteriQuery}
import fi.vm.sade.hakurekisteri.integration.valintatulos.{ValintaTulosActor, ValintaTulosActorRef}
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
  val hakemusBasedPermissionChecker: HakemusBasedPermissionCheckerActorRef
  val virta: VirtaActorRef
  val virtaResource: VirtaResourceActorRef
  val henkilo: HenkiloActorRef
  val organisaatiot: OrganisaatioActorRef
  val hakemusService: IHakemusService
  val koosteService: IKoosteService
  val tarjonta: TarjontaActorRef
  val koodisto: KoodistoActorRef
  val ytl: ActorRef
  val ytlIntegration: YtlIntegration
  val ytlHttp: YtlHttpFetch
  val parametrit: ParametritActorRef
  val valintaTulos: ValintaTulosActorRef
  val valintarekisteri: ValintarekisteriActorRef
  val proxies: Proxies
  val hakemusClient: VirkailijaRestClient
  val oppijaNumeroRekisteri: IOppijaNumeroRekisteri
  val koskiService: IKoskiService
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
  override val virta: VirtaActorRef = new VirtaActorRef(mockActor("virta", new DummyActor))
  override val virtaResource: VirtaResourceActorRef = new VirtaResourceActorRef(mockActor("virtaResource", new MockVirtaResourceActor))
  override val valintaTulos: ValintaTulosActorRef = new ValintaTulosActorRef(mockActor("valintaTulos", new DummyActor))
  override val valintarekisteri: ValintarekisteriActorRef = new ValintarekisteriActorRef(mockActor("valintarekisteri", new Actor {
    override def receive: Receive = {
      case ValintarekisteriQuery(_, _) => sender ! Seq()
      case a => println(s"DummyActor($self): received $a")
    }
  }))
  override val hakemusService = new HakemusServiceMock
  override val koskiService = new KoskiServiceMock
  override val koosteService = new KoosteServiceMock
  override val koodisto: KoodistoActorRef = new KoodistoActorRef(mockActor("koodisto", new DummyActor))
  override val organisaatiot: OrganisaatioActorRef = new OrganisaatioActorRef(mockActor("organisaatiot", new MockOrganisaatioActor(config)))
  override val parametrit: ParametritActorRef = new ParametritActorRef(mockActor("parametrit", new MockParameterActor()(system)))
  override val henkilo: HenkiloActorRef = new HenkiloActorRef(mockActor("henkilo", new MockHenkiloActor(config)))
  override val tarjonta: TarjontaActorRef = new TarjontaActorRef(mockActor("tarjonta", new MockTarjontaActor(config)(system)))
  override val oppijaNumeroRekisteri: IOppijaNumeroRekisteri = MockOppijaNumeroRekisteri
  override val ytl: ActorRef = system.actorOf(Props(new YtlActor(
    rekisterit.ytlSuoritusRekisteri,
    rekisterit.ytlArvosanaRekisteri,
    hakemusService,
    config.integrations.ytlConfig
  )), "ytl")
  val ytlFileSystem = YtlFileSystem(OphUrlProperties)
  override val ytlHttp = new YtlHttpFetch(OphUrlProperties, ytlFileSystem)
  override val ytlIntegration = new YtlIntegration(OphUrlProperties, ytlHttp, hakemusService, oppijaNumeroRekisteri, ytl, config)

  override val proxies = new MockProxies
  override val hakemusClient = null

  private def mockActor(name: String, actor: => Actor) = system.actorOf(Props(actor), name)

  override val hakemusBasedPermissionChecker: HakemusBasedPermissionCheckerActorRef = new HakemusBasedPermissionCheckerActorRef(
    system.actorOf(Props(new Actor {
      override def receive: Receive = {
      case a: HasPermission => sender ! true
      }
    })))
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
  val koskiResourceEc = ExecutorUtil.createExecutor(5, "koski-resource-client-pool")

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
    koskiResourceEc.awaitTermination(3, TimeUnit.SECONDS)
  })

  private val tarjontaClient = new VirkailijaRestClient(config.integrations.tarjontaConfig, None)(restEc, system)
  private val organisaatioClient = new VirkailijaRestClient(config.integrations.organisaatioConfig, None)(restEc, system)
  private val koodistoClient = new VirkailijaRestClient(config.integrations.koodistoConfig, None)(restEc, system)
  val hakemusClient = new VirkailijaRestClient(config.integrations.hakemusConfig.serviceConf, None)(restEc, system)
  val ataruHakemusClient = new VirkailijaRestClient(config.integrations.ataruConfig, None, jSessionName = "ring-session",
    serviceUrlSuffix = "/auth/cas")(restEc, system)
  private val koosteClient = new VirkailijaRestClient(config.integrations.koosteConfig, None)(restEc, system)
  private val parametritClient = new VirkailijaRestClient(config.integrations.parameterConfig, None)(restEc, system)
  private val valintatulosClient = new VirkailijaRestClient(config.integrations.valintaTulosConfig, None)(vtsEc, system)
  private val valintarekisteriClient = new VirkailijaRestClient(config.integrations.valintarekisteriConfig, None)(vrEc, system)
  private val koskiClient = new VirkailijaRestClient(config.integrations.koskiConfig, None)(koskiResourceEc, system)
  private val hakuAppPermissionCheckerClient = new VirkailijaRestClient(config.integrations.hakemusConfig.serviceConf.copy(
    casUrl = None, user = None, password = None
  ), None)(restEc, system)
  private val ataruPermissionCheckerClient = new VirkailijaRestClient(config.integrations.ataruConfig, None, jSessionName = "ring-session",
    serviceUrlSuffix = "/auth/cas")(restEc, system)
  private val onrClient = new VirkailijaRestClient(config.integrations.oppijaNumeroRekisteriConfig, None)(restEc, system)

  def getSupervisedActorFor(props: Props, name: String) = system.actorOf(BackoffSupervisor.props(
    Backoff.onStop(
      props,
      childName = name,
      minBackoff = 3.seconds,
      maxBackoff = 30.seconds,
      randomFactor = 0.2
    )), name)

  val cacheFactory = CacheFactory.apply(OphUrlProperties)(system)
  val tarjonta: TarjontaActorRef = new TarjontaActorRef(getSupervisedActorFor(Props(new TarjontaActor(tarjontaClient, config, cacheFactory)), "tarjonta"))
  val organisaatiot = new OrganisaatioActorRef(getSupervisedActorFor(Props(new HttpOrganisaatioActor(organisaatioClient, config, cacheFactory)), "organisaatio"))
  val henkilo = new HenkiloActorRef(system.actorOf(Props(new fi.vm.sade.hakurekisteri.integration.henkilo.HttpHenkiloActor(onrClient, config)), "henkilo"))
  override val oppijaNumeroRekisteri: IOppijaNumeroRekisteri = new OppijaNumeroRekisteri(onrClient, system)
  val hakemusService = new HakemusService(hakemusClient, ataruHakemusClient, tarjonta, organisaatiot, oppijaNumeroRekisteri)(system)
  val koskiArvosanaHandler = new KoskiArvosanaHandler(rekisterit.suoritusRekisteri, rekisterit.arvosanaRekisteri, rekisterit.opiskelijaRekisteri)(system.dispatcher)
  val koskiService = new KoskiService(koskiClient, oppijaNumeroRekisteri, hakemusService, koskiArvosanaHandler)(system)
  val koosteService = new KoosteService(koosteClient)(system)
  val koodisto = new KoodistoActorRef(system.actorOf(Props(new KoodistoActor(koodistoClient, config, cacheFactory)), "koodisto"))
  val parametrit = new ParametritActorRef(system.actorOf(Props(new HttpParameterActor(parametritClient)), "parametrit"))
  val valintaTulos = new ValintaTulosActorRef(getSupervisedActorFor(Props(new ValintaTulosActor(valintatulosClient, config, cacheFactory)), "valintaTulos"))
  val valintarekisteri = new ValintarekisteriActorRef(system.actorOf(Props(new ValintarekisteriActor(valintarekisteriClient, config)), "valintarekisteri"))
  val ytl = system.actorOf(Props(new YtlActor(
    rekisterit.ytlSuoritusRekisteri,
    rekisterit.ytlArvosanaRekisteri,
    hakemusService,
    config.integrations.ytlConfig
  )), "ytl")
  val ytlFileSystem = YtlFileSystem(OphUrlProperties)
  override val ytlHttp = new YtlHttpFetch(OphUrlProperties, ytlFileSystem)
  val ytlIntegration = new YtlIntegration(OphUrlProperties, ytlHttp, hakemusService, oppijaNumeroRekisteri, ytl, config)
  private val virtaClient = new VirtaClient(
    config = config.integrations.virtaConfig,
    apiVersion = config.properties.getOrElse("suoritusrekisteri.virta.apiversio", VirtaClient.version105)
  )(virtaEc, system)
  private val virtaResourceClient = new VirtaClient(
    config = config.integrations.virtaConfig,
    apiVersion = config.properties.getOrElse("suoritusrekisteri.virta.apiversio", VirtaClient.version106)
  )(virtaResourceEc, system)
  val virta = new VirtaActorRef(system.actorOf(Props(new VirtaActor(virtaClient, organisaatiot, rekisterit.suoritusRekisteri, rekisterit.opiskeluoikeusRekisteri)), "virta"))
  val virtaResource = new VirtaResourceActorRef(system.actorOf(Props(new VirtaResourceActor(virtaResourceClient)), "virtaResource"))
  val proxies = new HttpProxies(valintarekisteriClient)

  val arvosanaTrigger: Trigger = IlmoitetutArvosanatTrigger(rekisterit.suoritusRekisteri, rekisterit.arvosanaRekisteri)(system.dispatcher)

  val ytlTrigger: Trigger = Trigger { (hakemus: HakijaHakemus, personOidsWithAliases: PersonOidsWithAliases) =>
    Try(ytlIntegration.sync(hakemus, personOidsWithAliases.intersect(hakemus.personOid.toSet)))
  }

  hakemusService.addTrigger(arvosanaTrigger)
  hakemusService.addTrigger(ytlTrigger)

  implicit val scheduler = system.scheduler
  hakemusService.processModifiedHakemukset()


  /*val traverseStart: Long  = 1514764800000L//System.currentTimeMillis() - TimeUnit.DAYS.toMillis(16)
  if (Try(config.properties.getOrElse("suoritusrekisteri.use.koski.integration", "true").toBoolean).getOrElse(true)) {
    val delay: FiniteDuration = 1.minute
    koskiService.processModifiedKoski(refreshFrequency = delay)
    koskiService.traverseKoskiDataInChunks(timeToWaitUntilNextBatch = delay, searchWindowStartTime = new Date(traverseStart))
  }*/

  val quartzScheduler = StdSchedulerFactory.getDefaultScheduler()
  quartzScheduler.start()

  val syncAllCronExpression = OphUrlProperties.getProperty("ytl.http.syncAllCronJob")
  val rerunSync = rerunPolicy(syncAllCronExpression, ytlIntegration)
  quartzScheduler.scheduleJob(lambdaJob(rerunSync),
    newTrigger().startNow().withSchedule(cronSchedule(syncAllCronExpression)).build());
  override val hakemusBasedPermissionChecker: HakemusBasedPermissionCheckerActorRef = new HakemusBasedPermissionCheckerActorRef(system.actorOf(Props(new HakemusBasedPermissionCheckerActor(hakuAppPermissionCheckerClient, ataruPermissionCheckerClient, organisaatiot))))

}

trait TypedActorRef {
  val actor: ActorRef
}