package support

import java.util.concurrent.TimeUnit
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{AskableActorRef, Backoff, BackoffSupervisor}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory.InMemoryCacheFactory
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.haku.{
  HakuActor,
  HakuAggregatorActor,
  HakuAggregatorActorRef,
  MockHakuAggregatorActor
}
import fi.vm.sade.hakurekisteri.integration.hakukohde.{
  HakukohdeAggregatorActor,
  HakukohdeAggregatorActorRef,
  MockHakukohdeAggregatorActor
}
import fi.vm.sade.hakurekisteri.integration.hakukohderyhma.{
  HakukohderyhmaService,
  HakukohderyhmaServiceMock,
  IHakukohderyhmaService
}
import fi.vm.sade.hakurekisteri.integration.henkilo._
import fi.vm.sade.hakurekisteri.integration.koodisto.{
  KoodistoActor,
  KoodistoActorRef,
  MockKoodistoActor
}
import fi.vm.sade.hakurekisteri.integration.kooste.{
  IKoosteService,
  KoosteService,
  KoosteServiceMock
}
import fi.vm.sade.hakurekisteri.integration.koski._
import fi.vm.sade.hakurekisteri.integration.kouta.{
  KoutaInternalActor,
  KoutaInternalActorRef,
  MockKoutaInternalActor
}
import fi.vm.sade.hakurekisteri.integration.organisaatio.{
  HttpOrganisaatioActor,
  MockOrganisaatioActor,
  OrganisaatioActorRef
}
import fi.vm.sade.hakurekisteri.integration.parametrit.{
  HttpParameterActor,
  MockParameterActor,
  ParametritActorRef
}
import fi.vm.sade.hakurekisteri.integration.pistesyotto.PistesyottoService
import fi.vm.sade.hakurekisteri.integration.tarjonta.{
  MockTarjontaActor,
  TarjontaActor,
  TarjontaActorRef
}
import fi.vm.sade.hakurekisteri.integration.valintalaskentatulos.{
  IValintalaskentaTulosService,
  ValintalaskentaTulosService,
  ValintalaskentaTulosServiceMock
}
import fi.vm.sade.hakurekisteri.integration.valintaperusteet.{
  IValintaperusteetService,
  ValintaperusteetService,
  ValintaperusteetServiceMock
}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{
  ValintarekisteriActor,
  ValintarekisteriActorRef,
  ValintarekisteriQuery
}
import fi.vm.sade.hakurekisteri.integration.valintatulos.{ValintaTulosActor, ValintaTulosActorRef}
import fi.vm.sade.hakurekisteri.integration.valpas.ValpasIntergration
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
import fi.vm.sade.hakurekisteri.integration.ytl.YtlRerunPolicy

import java.util.Date
import scala.compat.Platform
import scala.concurrent.duration._
import scala.util.Try

trait Integrations {
  val hakemusBasedPermissionChecker: HakemusBasedPermissionCheckerActorRef
  val virta: VirtaActorRef
  val virtaResource: VirtaResourceActorRef
  val henkilo: HenkiloActorRef
  val organisaatiot: OrganisaatioActorRef
  val hakemusService: IHakemusService
  val koosteService: IKoosteService
  val tarjonta: TarjontaActorRef
  val koutaInternal: KoutaInternalActorRef
  val hakuAggregator: HakuAggregatorActorRef
  val hakukohdeAggregator: HakukohdeAggregatorActorRef
  val haut: ActorRef
  val koodisto: KoodistoActorRef
  val ytlKokelasPersister: YtlKokelasPersister
  val valpasIntegration: ValpasIntergration
  val ytlHttp: YtlHttpFetch
  val parametrit: ParametritActorRef
  val valintaTulos: ValintaTulosActorRef
  val valintarekisteri: ValintarekisteriActorRef
  val proxies: Proxies
  val hakemusClient: VirkailijaRestClient
  val valintalaskentaClient: VirkailijaRestClient
  val oppijaNumeroRekisteri: IOppijaNumeroRekisteri
  val koskiService: IKoskiService
  val valintaperusteetService: IValintaperusteetService
  val pistesyottoService: PistesyottoService
  val hakukohderyhmaService: IHakukohderyhmaService
  val valintalaskentaTulosService: IValintalaskentaTulosService
  val ytlFetchActor: YtlFetchActorRef
}

trait OvaraIntegrations {
  val tarjonta: TarjontaActorRef
  val haut: ActorRef
  val valintarekisteri: ValintarekisteriActorRef
  val hakemusService: IHakemusService
  val koosteService: IKoosteService
  val oppijaNumeroRekisteri: IOppijaNumeroRekisteri
}

object Integrations {
  def apply(rekisterit: Registers, system: ActorSystem, config: Config): Integrations =
    config.mockMode match {
      case true => new MockIntegrations(rekisterit, system, config)
      case _    => new BaseIntegrations(rekisterit, system, config)
    }
}

class MockIntegrations(rekisterit: Registers, system: ActorSystem, config: Config)
    extends Integrations {
  override val virta: VirtaActorRef = new VirtaActorRef(mockActor("virta", new DummyActor))
  override val virtaResource: VirtaResourceActorRef = new VirtaResourceActorRef(
    mockActor("virtaResource", new MockVirtaResourceActor)
  )
  override val valintarekisteri: ValintarekisteriActorRef = new ValintarekisteriActorRef(
    mockActor(
      "valintarekisteri",
      new Actor {
        override def receive: Receive = {
          case ValintarekisteriQuery(_, _) => sender ! Seq()
          case a                           => println(s"DummyActor($self): received $a")
        }
      }
    )
  )
  override val hakemusService = new HakemusServiceMock
  override val koskiService = new KoskiServiceMock
  override val koosteService = new KoosteServiceMock
  override val valintalaskentaTulosService = new ValintalaskentaTulosServiceMock
  override val valintaperusteetService = new ValintaperusteetServiceMock
  override val hakukohderyhmaService = new HakukohderyhmaServiceMock
  override val koodisto: KoodistoActorRef = new KoodistoActorRef(
    mockActor("koodisto", new MockKoodistoActor())
  )
  override val organisaatiot: OrganisaatioActorRef = new OrganisaatioActorRef(
    mockActor("organisaatiot", new MockOrganisaatioActor(config))
  )
  override val parametrit: ParametritActorRef = new ParametritActorRef(
    mockActor("parametrit", new MockParameterActor(config = config)(system))
  )
  override val henkilo: HenkiloActorRef = new HenkiloActorRef(
    mockActor("henkilo", new MockHenkiloActor(config))
  )
  override val tarjonta: TarjontaActorRef = new TarjontaActorRef(
    mockActor("tarjonta", new MockTarjontaActor(config)(system))
  )
  override val koutaInternal: KoutaInternalActorRef = new KoutaInternalActorRef(
    mockActor("koutaInternal", new MockKoutaInternalActor(koodisto, config))
  )
  override val hakuAggregator: HakuAggregatorActorRef = new HakuAggregatorActorRef(
    mockActor("hakuAggregator", new MockHakuAggregatorActor(tarjonta, koutaInternal, config))
  )
  override val hakukohdeAggregator: HakukohdeAggregatorActorRef = new HakukohdeAggregatorActorRef(
    mockActor(
      "hakukohdeAggregator",
      new MockHakukohdeAggregatorActor(tarjonta, koutaInternal, config)
    )
  )

  override val oppijaNumeroRekisteri: IOppijaNumeroRekisteri = MockOppijaNumeroRekisteri
  override val ytlKokelasPersister = new YtlKokelasPersister(
    system,
    rekisterit.ytlSuoritusRekisteri,
    rekisterit.ytlArvosanaRekisteri,
    hakemusService,
    config.ytlSyncTimeout,
    config.ytlSyncRetries
  )
  val ytlFileSystem = YtlFileSystem(OphUrlProperties)
  override val ytlHttp = new YtlHttpFetch(OphUrlProperties, ytlFileSystem)
  val mockFailureEmailSender = new MockFailureEmailSender

  override val ytlFetchActor: YtlFetchActorRef = new YtlFetchActorRef(
    mockActor(
      "ytlFetchActor",
      new YtlFetchActor(
        properties = OphUrlProperties,
        ytlHttp,
        hakemusService,
        oppijaNumeroRekisteri,
        ytlKokelasPersister,
        mockFailureEmailSender,
        config
      )
    )
  )

  val haut: ActorRef = system.actorOf(
    Props(
      new HakuActor(hakuAggregator, koskiService, parametrit, ytlFetchActor, config)
    ),
    "haut"
  )
  val valintaTulos: ValintaTulosActorRef = new ValintaTulosActorRef(
    mockActor("valintaTulos", new DummyActor)
  )

  override val proxies = new MockProxies
  override val hakemusClient = null
  override val valintalaskentaClient = null
  override val pistesyottoService = null
  override val valpasIntegration =
    new ValpasIntergration(
      pistesyottoService,
      valintalaskentaClient,
      organisaatiot,
      koodisto,
      tarjonta,
      koutaInternal,
      haut,
      valintaTulos,
      hakemusService
    )

  private def mockActor(name: String, actor: => Actor) = system.actorOf(Props(actor), name)

  override val hakemusBasedPermissionChecker: HakemusBasedPermissionCheckerActorRef =
    new HakemusBasedPermissionCheckerActorRef(system.actorOf(Props(new Actor {
      override def receive: Receive = { case a: HasPermission =>
        sender ! true
      }
    })))
}

class BaseIntegrations(rekisterit: Registers, system: ActorSystem, config: Config)
    extends Integrations {
  private val logger = LoggerFactory.getLogger(getClass)
  logger.info(s"Initializing BaseIntegrations started...")
  val restEc = ExecutorUtil.createExecutor(10, "rest-client-pool")
  val koosteEc = ExecutorUtil.createExecutor(10, "kooste-client-pool")
  val laskentaEc = ExecutorUtil.createExecutor(10, "valintalaskenta-client-pool")
  val pisteEc = ExecutorUtil.createExecutor(10, "pistesyotto-client-pool")
  val vtsEc = ExecutorUtil.createExecutor(5, "valinta-tulos-client-pool")
  val vrEc = ExecutorUtil.createExecutor(10, "valintarekisteri-client-pool")
  val virtaEc = ExecutorUtil.createExecutor(1, "virta-client-pool")
  val virtaResourceEc = ExecutorUtil.createExecutor(5, "virta-resource-client-pool")
  val koskiResourceEc = ExecutorUtil.createExecutor(5, "koski-resource-client-pool")

  system.registerOnTermination(() => {
    restEc.shutdown()
    koosteEc.shutdown()
    vtsEc.shutdown()
    laskentaEc.shutdown()
    pisteEc.shutdown()
    vrEc.shutdown()
    virtaEc.shutdown()
    restEc.awaitTermination(3, TimeUnit.SECONDS)
    vtsEc.awaitTermination(3, TimeUnit.SECONDS)
    laskentaEc.awaitTermination(3, TimeUnit.SECONDS)
    pisteEc.awaitTermination(3, TimeUnit.SECONDS)
    vrEc.awaitTermination(3, TimeUnit.SECONDS)
    virtaEc.awaitTermination(3, TimeUnit.SECONDS)
    virtaResourceEc.awaitTermination(3, TimeUnit.SECONDS)
    koskiResourceEc.awaitTermination(3, TimeUnit.SECONDS)
  })

  private val tarjontaClient =
    new VirkailijaRestClient(config.integrations.tarjontaConfig, None)(restEc, system)
  private val koutaInternalClient =
    new VirkailijaRestClient(
      config.integrations.koutaInternalConfig,
      None,
      jSessionName = "session",
      serviceUrlSuffix = "/auth/login"
    )(
      restEc,
      system
    )
  private val organisaatioClient =
    new VirkailijaRestClient(config.integrations.organisaatioConfig, None)(restEc, system)
  private val koodistoClient =
    new VirkailijaRestClient(config.integrations.koodistoConfig, None)(restEc, system)
  val hakemusClient =
    new VirkailijaRestClient(config.integrations.hakemusConfig.serviceConf, None)(restEc, system)
  val ataruHakemusClient = new VirkailijaRestClient(
    config.integrations.ataruConfig,
    None,
    jSessionName = "ring-session",
    serviceUrlSuffix = "/auth/cas"
  )(restEc, system)
  private val koosteClient =
    new VirkailijaRestClient(config.integrations.koosteConfig, None)(koosteEc, system)
  private val parametritClient =
    new VirkailijaRestClient(config.integrations.parameterConfig, None)(restEc, system)
  private val valintatulosClient =
    new VirkailijaRestClient(config.integrations.valintaTulosConfig, None)(vtsEc, system)
  private val pistesyottoClient =
    new VirkailijaRestClient(
      config.integrations.pistesyottoConfig,
      None,
      jSessionName = "ring-session",
      serviceUrlSuffix = "/auth/cas"
    )(pisteEc, system)
  val valintalaskentaClient =
    new VirkailijaRestClient(
      config.integrations.valintalaskentaConfig,
      None
    )(laskentaEc, system)
  private val valintarekisteriClient =
    new VirkailijaRestClient(config.integrations.valintarekisteriConfig, None)(vrEc, system)
  private val koskiClient =
    new VirkailijaRestClient(config.integrations.koskiConfig, None)(koskiResourceEc, system)
  private val hakuAppPermissionCheckerClient = new VirkailijaRestClient(
    config.integrations.hakemusConfig.serviceConf.copy(
      casUrl = None,
      user = None,
      password = None
    ),
    None
  )(restEc, system)
  private val ataruPermissionCheckerClient = new VirkailijaRestClient(
    config.integrations.ataruConfig,
    None,
    jSessionName = "ring-session",
    serviceUrlSuffix = "/auth/cas"
  )(restEc, system)
  private val onrClient =
    new VirkailijaRestClient(config.integrations.oppijaNumeroRekisteriConfig, None)(restEc, system)
  private val valintaperusteetClient = new VirkailijaRestClient(
    config.integrations.valintaperusteetServiceConfig,
    None
  )(restEc, system)
  private val hakukohderyhmaClient = new VirkailijaRestClient(
    config.integrations.hakukohderyhmaPalveluConfig,
    None,
    jSessionName = "ring-session",
    serviceUrlSuffix = "/auth/cas"
  )(restEc, system)
  val pistesyottoService = new PistesyottoService(pistesyottoClient)
  def getSupervisedActorFor(props: Props, name: String) = system.actorOf(
    BackoffSupervisor.props(
      Backoff.onStop(
        props,
        childName = name,
        minBackoff = 3.seconds,
        maxBackoff = 30.seconds,
        randomFactor = 0.2
      )
    ),
    name
  )

  val cacheFactory = CacheFactory.apply(OphUrlProperties)(system)
  val tarjonta: TarjontaActorRef = new TarjontaActorRef(
    getSupervisedActorFor(
      Props(new TarjontaActor(tarjontaClient, config, cacheFactory)),
      "tarjonta"
    )
  )
  val koodisto = new KoodistoActorRef(
    system.actorOf(Props(new KoodistoActor(koodistoClient, config, cacheFactory)), "koodisto")
  )
  val koutaInternal: KoutaInternalActorRef = new KoutaInternalActorRef(
    getSupervisedActorFor(
      Props(new KoutaInternalActor(koodisto, koutaInternalClient, config)),
      "koutaInternal"
    )
  )
  val hakuAggregator: HakuAggregatorActorRef = new HakuAggregatorActorRef(
    getSupervisedActorFor(
      Props(new HakuAggregatorActor(tarjonta, koutaInternal, config)),
      "hakuAggregator"
    )
  )
  val hakukohdeAggregator: HakukohdeAggregatorActorRef = new HakukohdeAggregatorActorRef(
    getSupervisedActorFor(
      Props(new HakukohdeAggregatorActor(tarjonta, koutaInternal, config)),
      "hakukohdeAggregator"
    )
  )
  val organisaatiot = new OrganisaatioActorRef(
    getSupervisedActorFor(
      Props(new HttpOrganisaatioActor(organisaatioClient, config, cacheFactory)),
      "organisaatio"
    )
  )
  val henkilo = new HenkiloActorRef(
    system.actorOf(
      Props(new fi.vm.sade.hakurekisteri.integration.henkilo.HttpHenkiloActor(onrClient, config)),
      "henkilo"
    )
  )
  override val oppijaNumeroRekisteri: IOppijaNumeroRekisteri =
    new OppijaNumeroRekisteri(onrClient, system, config)
  val hakemusService = new HakemusService(
    hakemusClient,
    ataruHakemusClient,
    hakukohdeAggregator,
    koutaInternal,
    organisaatiot,
    oppijaNumeroRekisteri,
    koodisto,
    config,
    cacheFactory,
    maxOidsChunkSize = config.properties
      .getOrElse("suoritusrekisteri.hakemusservice.max.oids.chunk.size", "150")
      .toInt
  )(system)
  val koskiDataHandler = new KoskiDataHandler(
    rekisterit.suoritusRekisteri,
    rekisterit.arvosanaRekisteri,
    rekisterit.opiskelijaRekisteri
  )(system.dispatcher)
  val koskiService =
    new KoskiService(koskiClient, oppijaNumeroRekisteri, hakemusService, koskiDataHandler, config)(
      system
    )
  val koosteService = new KoosteService(koosteClient)(system)
  val valintalaskentaTulosService = new ValintalaskentaTulosService(valintalaskentaClient)(system)
  val valintaperusteetService = new ValintaperusteetService(valintaperusteetClient)(system)
  val hakukohderyhmaService = new HakukohderyhmaService(hakukohderyhmaClient)(system)
  val parametrit = new ParametritActorRef(
    system.actorOf(Props(new HttpParameterActor(parametritClient, config)), "parametrit")
  )
  val valintarekisteri = new ValintarekisteriActorRef(
    system.actorOf(
      Props(new ValintarekisteriActor(valintarekisteriClient, config)),
      "valintarekisteri"
    )
  )
  val ytlKokelasPersister = new YtlKokelasPersister(
    system,
    rekisterit.ytlSuoritusRekisteri,
    rekisterit.ytlArvosanaRekisteri,
    hakemusService,
    config.ytlSyncTimeout,
    config.ytlSyncRetries
  )
  val ytlFileSystem = YtlFileSystem(OphUrlProperties)
  override val ytlHttp = new YtlHttpFetch(OphUrlProperties, ytlFileSystem)
  val realFailureEmailSender = new RealFailureEmailSender(config)

  val ytlFetchActor = YtlFetchActorRef(
    system.actorOf(
      Props(
        new YtlFetchActor(
          properties = OphUrlProperties,
          ytlHttp,
          hakemusService,
          oppijaNumeroRekisteri,
          ytlKokelasPersister,
          realFailureEmailSender,
          config
        )
      ),
      "ytlFetchActor"
    )
  )

  val haut: ActorRef = system.actorOf(
    Props(
      new HakuActor(hakuAggregator, koskiService, parametrit, ytlFetchActor, config)
    ),
    "haut"
  )
  val valintaTulos: ValintaTulosActorRef = ValintaTulosActorRef(
    getSupervisedActorFor(
      Props(new ValintaTulosActor(haut, valintatulosClient, config, cacheFactory)),
      "valintaTulos"
    )
  )

  override val valpasIntegration =
    new ValpasIntergration(
      pistesyottoService,
      valintalaskentaClient,
      organisaatiot,
      koodisto,
      tarjonta,
      koutaInternal,
      haut,
      valintaTulos,
      hakemusService
    )

  private val virtaClient = new VirtaClient(
    config = config.integrations.virtaConfig,
    apiVersion =
      config.properties.getOrElse("suoritusrekisteri.virta.apiversio", VirtaClient.version105)
  )(virtaEc, system)
  private val virtaResourceClient = new VirtaClient(
    config = config.integrations.virtaConfig,
    apiVersion =
      config.properties.getOrElse("suoritusrekisteri.virta.apiversio", VirtaClient.version106)
  )(virtaResourceEc, system)
  val virta = new VirtaActorRef(
    system.actorOf(
      Props(
        new VirtaActor(
          virtaClient,
          organisaatiot,
          rekisterit.suoritusRekisteri,
          rekisterit.opiskeluoikeusRekisteri,
          config
        )
      ),
      "virta"
    )
  )
  val virtaResource = new VirtaResourceActorRef(
    system.actorOf(Props(new VirtaResourceActor(virtaResourceClient, config)), "virtaResource")
  )
  val proxies = new HttpProxies(valintarekisteriClient)

  val arvosanaTrigger: Trigger =
    IlmoitetutArvosanatTrigger(rekisterit.suoritusRekisteri, rekisterit.arvosanaRekisteri)(
      system.dispatcher
    )

  val ytlTrigger: Trigger = Trigger {
    (hakemus: HakijaHakemus, personOidsWithAliases: PersonOidsWithAliases) =>
      {
        (hakemus.hetu, hakemus.personOid) match {
          case (Some(hetu), Some(personOid)) =>
            ytlFetchActor.actor ! YtlSyncSingle(
              personOid,
              "ytlTrigger_" + hakemus.oid,
              Some(hakemus.applicationSystemId)
            )
          case _ =>
            val noOid =
              s"Skipping YTL update as hakemus (${hakemus.oid}) doesn't have person OID and/or SSN!"
            logger.warn(noOid)
        }

      }
  }

  hakemusService.addTrigger(arvosanaTrigger)
  hakemusService.addTrigger(ytlTrigger)

  val hoursToBacktrack: Int =
    OphUrlProperties.getOrElse("suoritusrekisteri.modifiedhakemukset.backtrack.hours", "1").toInt
  implicit val scheduler = system.scheduler
  hakemusService.processModifiedHakemukset(modifiedAfter =
    new Date(Platform.currentTime - TimeUnit.HOURS.toMillis(hoursToBacktrack))
  )

  val quartzScheduler = StdSchedulerFactory.getDefaultScheduler()
  if (!quartzScheduler.isStarted) {
    quartzScheduler.start()
  }

  val ytlSyncAllEnabled = OphUrlProperties.getProperty("ytl.http.syncAllEnabled").toBoolean
  val syncAllCronExpression = OphUrlProperties.getProperty("ytl.http.syncAllCronJob")
  val rerunSync = YtlRerunPolicy.rerunPolicy(syncAllCronExpression, ytlFetchActor)
  if (ytlSyncAllEnabled) {
    quartzScheduler.scheduleJob(
      lambdaJob(rerunSync),
      newTrigger().startNow().withSchedule(cronSchedule(syncAllCronExpression)).build()
    )
    logger.info(s"Scheduled Ytl syncAll jobs (cron expression=$syncAllCronExpression)")
  } else {
    logger.info(s"Not scheduled syncAll jobs because it is not enabled")
  }

  if (KoskiUtil.updateKkHaut || KoskiUtil.updateToisenAsteenHaut) {
    logger.info(
      s"Enabled automatic Koski-integrations: updateKkHaut=${KoskiUtil.updateKkHaut}, "
        + s"updateToisenAsteenHaut=${KoskiUtil.updateToisenAsteenHaut}"
        + s"updateJatkuvatHaut=${KoskiUtil.updateJatkuvatHaut}"
    )
    val koskiCronJob = OphUrlProperties.getProperty("suoritusrekisteri.koski.update.cronJob")
    if (KoskiUtil.updateKkHaut) {
      quartzScheduler.scheduleJob(
        lambdaJob(koskiService.updateAktiivisetKkAsteenHaut()),
        newTrigger().startNow().withSchedule(cronSchedule(koskiCronJob)).build()
      )
    }
    if (KoskiUtil.updateToisenAsteenHaut) {
      // refreshChangedOppijasFromKoski is bound to toisen asteen haut
      koskiService.refreshChangedOppijasFromKoski()
      quartzScheduler.scheduleJob(
        lambdaJob(koskiService.updateAktiivisetToisenAsteenHaut()),
        newTrigger().startNow().withSchedule(cronSchedule(koskiCronJob)).build()
      )
    }
    if (KoskiUtil.updateJatkuvatHaut) {
      quartzScheduler.scheduleJob(
        lambdaJob(koskiService.updateAktiivisetToisenAsteenJatkuvatHaut()),
        newTrigger().startNow().withSchedule(cronSchedule(koskiCronJob)).build()
      )
    }
  } else {
    logger.info("Automatic Koski-integrations has been disabled by env parameters.")
  }

  if (KoskiUtil.koskiImporterResourceInUse) {
    logger.info("Manual Koski-integration api is available for rekisterinpitäjät")
  } else {
    logger.info("Manual Koski-integration api is disabled by env parameter")
  }

  override val hakemusBasedPermissionChecker: HakemusBasedPermissionCheckerActorRef =
    new HakemusBasedPermissionCheckerActorRef(
      system.actorOf(
        Props(
          new HakemusBasedPermissionCheckerActor(
            hakuAppPermissionCheckerClient,
            ataruPermissionCheckerClient,
            organisaatiot,
            config
          )
        )
      )
    )

  logger.info(s"Initializing BaseIntegrations ... done!")
}

//Sisältää lähinnä minimitoiminnallisuuden jotta saadaan muodostettua ensikertalaisuudet EnsikertalainenActorin avulla
//+ tämän tukitoiminnot. Muut toiminnallisuudet stubattu/null, eikä näitä ole tarkoitus käyttää mihinkään.
class OvaraBaseIntegrations(system: ActorSystem, config: Config) extends OvaraIntegrations {
  private val logger = LoggerFactory.getLogger(getClass)
  logger.info(s"Initializing OvaraIntegrations started...")
  val restEc = ExecutorUtil.createExecutor(10, "rest-client-pool")
  val vrEc = ExecutorUtil.createExecutor(10, "valintarekisteri-client-pool")

  system.registerOnTermination(() => {
    restEc.shutdown()
    vrEc.shutdown()
    restEc.awaitTermination(3, TimeUnit.SECONDS)
    vrEc.awaitTermination(3, TimeUnit.SECONDS)
  })

  private val tarjontaClient =
    new VirkailijaRestClient(config.integrations.tarjontaConfig, None)(restEc, system)
  private val koutaInternalClient =
    new VirkailijaRestClient(
      config.integrations.koutaInternalConfig,
      None,
      jSessionName = "session",
      serviceUrlSuffix = "/auth/login"
    )(
      restEc,
      system
    )
  private val parametritClient =
    new VirkailijaRestClient(config.integrations.parameterConfig, None)(restEc, system)
  private val organisaatioClient =
    new VirkailijaRestClient(config.integrations.organisaatioConfig, None)(restEc, system)
  private val koodistoClient =
    new VirkailijaRestClient(config.integrations.koodistoConfig, None)(restEc, system)
  val hakemusClient =
    new VirkailijaRestClient(config.integrations.hakemusConfig.serviceConf, None)(restEc, system)
  val ataruHakemusClient = new VirkailijaRestClient(
    config.integrations.ataruConfig,
    None,
    jSessionName = "ring-session",
    serviceUrlSuffix = "/auth/cas"
  )(restEc, system)
  private val valintarekisteriClient =
    new VirkailijaRestClient(config.integrations.valintarekisteriConfig, None)(vrEc, system)
  private val onrClient =
    new VirkailijaRestClient(config.integrations.oppijaNumeroRekisteriConfig, None)(restEc, system)

  def getSupervisedActorFor(props: Props, name: String) = system.actorOf(
    BackoffSupervisor.props(
      Backoff.onStop(
        props,
        childName = name,
        minBackoff = 3.seconds,
        maxBackoff = 30.seconds,
        randomFactor = 0.2
      )
    ),
    name
  )
  private val koosteClient =
    new VirkailijaRestClient(config.integrations.koosteConfig, None)(restEc, system)
  val koosteService = new KoosteService(koosteClient)(system)
  val cacheFactory = new InMemoryCacheFactory

  val koodisto = new KoodistoActorRef(
    system.actorOf(Props(new KoodistoActor(koodistoClient, config, cacheFactory)), "koodisto")
  )
  val koutaInternal: KoutaInternalActorRef = new KoutaInternalActorRef(
    getSupervisedActorFor(
      Props(new KoutaInternalActor(koodisto, koutaInternalClient, config)),
      "koutaInternal"
    )
  )
  val tarjonta: TarjontaActorRef = new TarjontaActorRef(
    getSupervisedActorFor(
      Props(new TarjontaActor(tarjontaClient, config, cacheFactory)),
      "tarjonta"
    )
  )
  val hakuAggregator: HakuAggregatorActorRef = new HakuAggregatorActorRef(
    getSupervisedActorFor(
      Props(new HakuAggregatorActor(tarjonta, koutaInternal, config)),
      "hakuAggregator"
    )
  )
  val hakukohdeAggregator: HakukohdeAggregatorActorRef = new HakukohdeAggregatorActorRef(
    getSupervisedActorFor(
      Props(new HakukohdeAggregatorActor(tarjonta, koutaInternal, config)),
      "hakukohdeAggregator"
    )
  )
  val organisaatiot = OrganisaatioActorRef(
    getSupervisedActorFor(
      Props(new HttpOrganisaatioActor(organisaatioClient, config, cacheFactory)),
      "organisaatio"
    )
  )
  val parametrit: ParametritActorRef = new ParametritActorRef(
    system.actorOf(Props(new HttpParameterActor(parametritClient, config)), "parametrit")
  )
  val valintarekisteri: ValintarekisteriActorRef = ValintarekisteriActorRef(
    system.actorOf(
      Props(new ValintarekisteriActor(valintarekisteriClient, config)),
      "valintarekisteri"
    )
  )

  val koskiService: IKoskiService =
    new KoskiServiceMock //huom. käytetään mockattua KoskiServicea ja YtlFetchActoria, jotka tarvitaan hakuActoria varten
  val ytlFetchActor = new YtlFetchActorRef(
    system.actorOf(Props(new DummyActor), "fake-ytl")
  )

  val haut: ActorRef = system.actorOf(
    Props(
      new HakuActor(hakuAggregator, koskiService, parametrit, ytlFetchActor, config)
    ),
    "haut"
  )

  override val oppijaNumeroRekisteri: IOppijaNumeroRekisteri =
    new OppijaNumeroRekisteri(onrClient, system, config)
  val hakemusService = new HakemusService(
    hakemusClient,
    ataruHakemusClient,
    hakukohdeAggregator,
    koutaInternal,
    organisaatiot,
    oppijaNumeroRekisteri,
    koodisto,
    config,
    cacheFactory,
    maxOidsChunkSize = config.properties
      .getOrElse("suoritusrekisteri.hakemusservice.max.oids.chunk.size", "150")
      .toInt
  )(system)

  val proxies = new HttpProxies(valintarekisteriClient)

  logger.info(s"Initializing OvaraIntegrations ... done!")
}

trait TypedActorRef {
  val actor: ActorRef
}

trait TypedAskableActorRef {
  val actor: AskableActorRef
}
