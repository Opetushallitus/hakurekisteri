import java.nio.file.Path

import _root_.support._
import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.integration.OphUrlProperties
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.arvosana.{ArvosanaResource, EmptyLisatiedotResource}
import fi.vm.sade.hakurekisteri.web.ensikertalainen.EnsikertalainenResource
import fi.vm.sade.hakurekisteri.web.hakija.{HakijaResource, HakijaResourceV2, HakijaResourceV3, HakijaResourceV4}
import fi.vm.sade.hakurekisteri.web.haku.HakuResource
import fi.vm.sade.hakurekisteri.web.integration.virta.{VirtaResource, VirtaSuoritusResource}
import fi.vm.sade.hakurekisteri.web.integration.ytl.YtlResource
import fi.vm.sade.hakurekisteri.web.jonotus.{AsiakirjaResource, SiirtotiedostojonoResource}
import fi.vm.sade.hakurekisteri.web.kkhakija.{KkHakijaResource, KkHakijaResourceV2, KkHakijaResourceV3}
import fi.vm.sade.hakurekisteri.web.koski.KoskiImporterResource
import fi.vm.sade.hakurekisteri.web.opiskelija.OpiskelijaResource
import fi.vm.sade.hakurekisteri.web.opiskeluoikeus.OpiskeluoikeusResource
import fi.vm.sade.hakurekisteri.web.oppija.OppijaResource
import fi.vm.sade.hakurekisteri.web.permission.PermissionResource
import fi.vm.sade.hakurekisteri.web.proxies._
import fi.vm.sade.hakurekisteri.web.rekisteritiedot.RekisteritiedotResource
import fi.vm.sade.hakurekisteri.web.rest.support._
import fi.vm.sade.hakurekisteri.web.restrictions.RestrictionsResource
import fi.vm.sade.hakurekisteri.web.suoritus.SuoritusResource
import fi.vm.sade.hakurekisteri.{Config, ProductionServerConfig}
import gui.GuiServlet
import javax.servlet.{DispatcherType, Servlet, ServletContext, ServletContextEvent}
import org.json4s._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.Swagger
import org.scalatra.{Handler, LifeCycle, ScalatraServlet}
import org.slf4j.LoggerFactory
import org.springframework.beans.MutablePropertyValues
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer
import org.springframework.core.io.FileSystemResource
import org.springframework.web.context._
import org.springframework.web.context.support.XmlWebApplicationContext
import org.springframework.web.filter.DelegatingFilterProxy
import siirto._

import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}

class ScalatraBootstrap extends LifeCycle {
  private val logger = LoggerFactory.getLogger(getClass)
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val system = ActorSystem("hakurekisteri")

  override def init(context: ServletContext) {
    logger.info(s"Scalatra init started...")
    OPHSecurity.init(context)
    val config = WebAppConfig.getConfig(context)
    implicit val security = Security(config)

    val journals = new DbJournals(config)

    val archiveScheduler = new ArchiveScheduler(journals.archiver)

    archiveScheduler.start(config.properties.getOrElse("suoritusrekisteri.db.archiveCronJob", "suoritusrekisteri.db.archiveCronJob property missing"))

    var integrations: Integrations = null
    val personAliasesProvider = new PersonAliasesProvider {
      override def enrichWithAliases(henkiloOids: Set[String]): Future[PersonOidsWithAliases] = integrations.oppijaNumeroRekisteri.enrichWithAliases(henkiloOids)
    }

    val registers = new BareRegisters(system, journals, journals.database, personAliasesProvider, config)

    integrations = Integrations(registers, system, config)

    val authorizedRegisters = new AuthorizedRegisters(registers, system, config, integrations.hakemusBasedPermissionChecker)

    config.productionServerConfig = new ProductionServerConfig(integrations, system, security)

    val koosteet = new BaseKoosteet(system, integrations, registers, config)

    context.setInitParameter(org.scalatra.EnvironmentKey, "production")
    if("DEVELOPMENT" != OphUrlProperties.getProperty("common.corsfilter.mode")) {
      context.initParameters(org.scalatra.CorsSupport.EnableKey) = "false"
    }

    val servlets = initServlets(config, registers, authorizedRegisters, integrations, koosteet)

    mountServlets(context)(servlets:_*)

    logger.info(s"Scalatra init... done")
  }

  //noinspection ScalaStyle
  private def initServlets(config: Config,
                           registers: BareRegisters,
                           authorizedRegisters: AuthorizedRegisters,
                           integrations: Integrations,
                           koosteet: BaseKoosteet)(implicit security: Security): List[((String, String), ScalatraServlet)] = List(
    ("/rest/v1/komo", "komo") -> new GuiServlet,
    ("/rest/v1/properties", "properties") -> new FrontPropertiesServlet,
    ("/permission/checkpermission", "permission/checkpermission") -> new PermissionResource(suoritusActor = registers.suoritusRekisteri, opiskelijaActor = registers.opiskelijaRekisteri, hakemusBasedPermissionCheckerActor = integrations.hakemusBasedPermissionChecker),
    ("/rest/v1/api-docs/*", "rest/v1/api-docs/*") -> new ResourcesApp(java.lang.Boolean.valueOf(config.properties.getOrElse("suoritusrekisteri.swagger.https", "false"))),
    ("/rest/v1/arvosanat", "rest/v1/arvosanat") -> new ArvosanaResource(authorizedRegisters.arvosanaRekisteri, authorizedRegisters.suoritusRekisteri),
    ("/rest/v1/ensikertalainen", "rest/v1/ensikertalainen") -> new EnsikertalainenResource(koosteet.ensikertalainen, integrations.hakemusService),
    ("/rest/v1/haut", "rest/v1/haut") -> new HakuResource(integrations.haut, integrations.hakemusService),
    ("/asiakirja", "asiakirja") -> new AsiakirjaResource(koosteet.siirtotiedostojono),
    ("/siirtotiedostojono", "siirtotiedostojono") -> new SiirtotiedostojonoResource(koosteet.siirtotiedostojono),
    ("/rest/v1/hakijat", "rest/v1/hakijat") -> new HakijaResource(koosteet.hakijat),
    ("/rest/v2/hakijat", "rest/v2/hakijat") -> new HakijaResourceV2(koosteet.hakijat),
    ("/rest/v3/hakijat", "rest/v3/hakijat") -> new HakijaResourceV3(koosteet.hakijat),
    ("/rest/v4/hakijat", "rest/v4/hakijat") -> new HakijaResourceV4(koosteet.hakijat),
    ("/rest/v1/kkhakijat", "rest/v1/kkhakijat") -> new KkHakijaResource(koosteet.kkHakijaService),
    ("/rest/v2/kkhakijat", "rest/v2/kkhakijat") -> new KkHakijaResourceV2(koosteet.kkHakijaService, config),
    ("/rest/v3/kkhakijat", "rest/v3/kkhakijat") -> new KkHakijaResourceV3(koosteet.kkHakijaService, config),
    ("/rest/v1/opiskelijat", "rest/v1/opiskelijat") -> new OpiskelijaResource(authorizedRegisters.opiskelijaRekisteri),
    ("/rest/v1/oppijat", "rest/v1/oppijat") -> new OppijaResource(authorizedRegisters, integrations.hakemusService, koosteet.ensikertalainen, integrations.oppijaNumeroRekisteri),
    ("/rest/v1/opiskeluoikeudet", "rest/v1/opiskeluoikeudet") -> new OpiskeluoikeusResource(authorizedRegisters.opiskeluoikeusRekisteri),
    ("/rest/v1/suoritukset", "rest/v1/suoritukset") -> new SuoritusResource(authorizedRegisters.suoritusRekisteri, integrations.parametrit, integrations.koodisto),
    ("/rest/v1/virta/henkilot", "rest/v1/virta/henkilot") -> new VirtaSuoritusResource(integrations.virtaResource, integrations.hakemusBasedPermissionChecker, integrations.oppijaNumeroRekisteri),
    ("/rest/v1/rajoitukset", "rest/v1/rajoitukset") -> new RestrictionsResource(integrations.parametrit),
    ("/rest/v1/rekisteritiedot", "rest/v1/rekisteritiedot") -> new RekisteritiedotResource(authorizedRegisters, integrations.hakemusService, koosteet.ensikertalainen, integrations.oppijaNumeroRekisteri),
    ("/rest/v1/tyhjalisatiedollisetarvosanat", "rest/v1/tyhjalisatiedollisetarvosanat") -> new EmptyLisatiedotResource(authorizedRegisters.arvosanaRekisteri),
    ("/schemas", "schema") -> new SchemaServlet(Perustiedot, PerustiedotKoodisto, Arvosanat, ArvosanatKoodisto),
    ("/virta", "virta") -> new VirtaResource(koosteet.virtaQueue), // Continuous Virta queue processing
    ("/ytl", "ytl") -> new YtlResource(integrations.ytlIntegration),
    ("/vastaanottotiedot", "vastaanottotiedot") -> new VastaanottotiedotProxyServlet(integrations.proxies.vastaanottotiedot, system, config),
    ("/hakurekisteri-validator", "hakurekister-validator") -> new ValidatorJavascriptServlet,
    ("/rest/v1/koskiimporter", "koski-importer") -> new KoskiImporterResource(integrations.koskiService, config)
  )

  def mountServlets(context: ServletContext)(servlets: ((String, String), Servlet with Handler)*) {
    implicit val sc = context
    for (((path, name), servlet) <- servlets) context.mount(handler = servlet, urlPattern = path, name = name, loadOnStartup = 1)
  }

  override def destroy(context: ServletContext) {
    import scala.concurrent.duration._

    Await.result(system.terminate(), 15.seconds)

    OPHSecurity.destroy(context)
  }
}

object WebAppConfig {
  def getConfig(context: ServletContext): Config = {
    Option(context.getAttribute("hakurekisteri.config").asInstanceOf[Config]).getOrElse(Config.globalConfig)
  }
}

object OPHSecurity extends ContextLoader with LifeCycle {
  val cleanupListener = new ContextCleanupListener

  override def init(context: ServletContext) {
    initWebApplicationContext(context)

    val security = context.addFilter("springSecurityFilterChain", classOf[DelegatingFilterProxy])
    security.addMappingForUrlPatterns(java.util.EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC), true, "/*")
    security.setAsyncSupported(true)
  }

  override def destroy(context: ServletContext) {
    closeWebApplicationContext(context)
    cleanupListener.contextDestroyed(new ServletContextEvent(context))
  }

  override def createWebApplicationContext(sc: ServletContext): WebApplicationContext = {
    val config = WebAppConfig.getConfig(sc)
    OPHConfig(config.ophConfDir,
      config.propertyLocations,
      "cas_mode" -> "front",
      "cas_key" -> "suoritusrekisteri",
      "spring_security_default_access" -> "hasRole('ROLE_APP_SUORITUSREKISTERI')",
      "cas_service" -> "${cas.service.suoritusrekisteri}",
      "cas_callback_url" -> "${cas.callback.suoritusrekisteri}"
    )
  }
}

case class OPHConfig(confDir: Path, propertyFiles: Seq[String], props:(String, String)*) extends XmlWebApplicationContext {
  val localProperties = (new java.util.Properties /: Map(props: _*)) {case (newProperties, (k,v)) => newProperties.put(k,v); newProperties}
  setConfigLocation("file:" + confDir + "/security-context-backend.xml")

  val resources: Seq[FileSystemResource] = for (
    fileName <- propertyFiles.reverse
  ) yield new FileSystemResource(confDir.resolve(fileName).toAbsolutePath.toString)

  val placeholder = Bean[PropertySourcesPlaceholderConfigurer](
    "localOverride" -> true,
    "properties" -> localProperties,
    "locations" -> resources.toArray
  )

  object Bean {
    def apply[C](props: (_, _)*)(implicit m: Manifest[C]): BeanDefinition = {
      val definition = new RootBeanDefinition(m.runtimeClass)
      definition.setPropertyValues(new MutablePropertyValues(Map(props: _*).asJava))
      definition
    }
  }

  override def initBeanDefinitionReader(beanDefinitionReader: XmlBeanDefinitionReader) {
    beanDefinitionReader.getRegistry.registerBeanDefinition("propertyPlaceHolder", placeholder)
  }
}

class FrontPropertiesServlet(implicit val system: ActorSystem) extends HakuJaValintarekisteriStack with JacksonJsonSupport {
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  override protected implicit def jsonFormats: Formats = DefaultFormats

  get("/") {
    contentType = "application/json"
    OphUrlProperties.frontProperties.asScala
  }
}
