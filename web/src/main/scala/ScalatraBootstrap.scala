import fi.vm.sade.hakurekisteri.web.integration.ytl.YtlResource
import java.nio.file.Path
import javax.servlet.{DispatcherType, Servlet, ServletContext, ServletContextEvent}

import _root_.support._
import akka.actor.{ActorSystem, Props}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.arvosana._
import fi.vm.sade.hakurekisteri.batchimport._
import fi.vm.sade.hakurekisteri.healthcheck.HealthcheckActor
import fi.vm.sade.hakurekisteri.opiskelija._
import fi.vm.sade.hakurekisteri.opiskeluoikeus._
import fi.vm.sade.hakurekisteri.rest.support.Workbook
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.web.arvosana.{ArvosanaSwaggerApi, CreateArvosanaCommand}
import fi.vm.sade.hakurekisteri.web.batchimport.ImportBatchResource
import fi.vm.sade.hakurekisteri.web.ensikertalainen.EnsikertalainenResource
import fi.vm.sade.hakurekisteri.web.hakija.HakijaResource
import fi.vm.sade.hakurekisteri.web.haku.HakuResource
import fi.vm.sade.hakurekisteri.web.healthcheck.HealthcheckResource
import fi.vm.sade.hakurekisteri.web.integration.virta.VirtaResource
import fi.vm.sade.hakurekisteri.web.kkhakija.KkHakijaResource
import fi.vm.sade.hakurekisteri.web.opiskelija.{CreateOpiskelijaCommand, OpiskelijaSwaggerApi}
import fi.vm.sade.hakurekisteri.web.opiskeluoikeus.{CreateOpiskeluoikeusCommand, OpiskeluoikeusSwaggerApi}
import fi.vm.sade.hakurekisteri.web.oppija.OppijaResource
import fi.vm.sade.hakurekisteri.web.proxies.{AuthenticationProxyResource, OrganizationProxyResource, LocalizationProxyResource}
import fi.vm.sade.hakurekisteri.web.rekisteritiedot.RekisteritiedotResource
import fi.vm.sade.hakurekisteri.web.rest.support._
import fi.vm.sade.hakurekisteri.web.suoritus.{CreateSuoritusCommand, SuoritusSwaggerApi}
import gui.GuiServlet
import org.scalatra.servlet.FileItem
import org.scalatra.swagger.Swagger
import org.scalatra.{Handler, LifeCycle}
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
import scala.concurrent.ExecutionContext

import scala.xml.Elem

class ScalatraBootstrap extends LifeCycle {
  import fi.vm.sade.hakurekisteri.Config._
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val system = ActorSystem("hakurekisteri")
  implicit val ec: ExecutionContext = system.dispatcher

  override def init(context: ServletContext) {
    OPHSecurity.init(context)

    val journals = new DbJournals(config.jndiName)
    val registers = new BareRegisters(system, journals)
    val authorizedRegisters = new AuthorizedRegisters(config.integrations.organisaatioSoapServiceUrl, registers, system, config)

    val integrations = Integrations(registers, system, config)

    val koosteet = new BaseKoosteet(system, integrations, registers, config)

    val healthcheck = system.actorOf(Props(new HealthcheckActor(authorizedRegisters.arvosanaRekisteri, authorizedRegisters.opiskelijaRekisteri, authorizedRegisters.opiskeluoikeusRekisteri, authorizedRegisters.suoritusRekisteri, authorizedRegisters.eraRekisteri, integrations.ytl, integrations.hakemukset, koosteet.ensikertalainen, koosteet.virtaQueue, config)), "healthcheck")

    val importBatchProcessing = system.actorOf(Props(new ImportBatchProcessingActor(authorizedRegisters.eraRekisteri, integrations.henkilo, authorizedRegisters.suoritusRekisteri, authorizedRegisters.opiskelijaRekisteri, integrations.organisaatiot, authorizedRegisters.arvosanaRekisteri, integrations.koodisto, config)), "importBatchProcessing")

    mountServlets(context)(
      ("/rest/v1/komo", "komo") -> new GuiServlet(config.oids),
      ("/healthcheck", "healthcheck") -> new HealthcheckResource(healthcheck),
      ("/rest/v1/siirto/arvosanat", "rest/v1/siirto/arvosanat") -> new ImportBatchResource(authorizedRegisters.eraRekisteri, integrations.parametrit, config, (foo) => ImportBatchQuery(None, None, None))("eranTunniste", ImportBatch.batchTypeArvosanat, "data", ArvosanatXmlConverter, Arvosanat, ArvosanatKoodisto) with SpringSecuritySupport,
      ("/rest/v1/siirto/perustiedot", "rest/v1/siirto/perustiedot") -> new ImportBatchResource(authorizedRegisters.eraRekisteri, integrations.parametrit, config, (foo) => ImportBatchQuery(None, None, None))("eranTunniste", ImportBatch.batchTypePerustiedot, "data", PerustiedotXmlConverter, Perustiedot, PerustiedotKoodisto) with SpringSecuritySupport,
      ("/rest/v1/api-docs/*", "rest/v1/api-docs/*") -> new ResourcesApp,
      ("/rest/v1/arvosanat", "rest/v1/arvosanat") -> new HakurekisteriResource[Arvosana, CreateArvosanaCommand](authorizedRegisters.arvosanaRekisteri, ArvosanaQuery(_)) with ArvosanaSwaggerApi with HakurekisteriCrudCommands[Arvosana, CreateArvosanaCommand] with SpringSecuritySupport,
      ("/rest/v1/ensikertalainen", "rest/v1/ensikertalainen") -> new EnsikertalainenResource(koosteet.ensikertalainen),
      ("/rest/v1/haut", "rest/v1/haut") -> new HakuResource(koosteet.haut),
      ("/rest/v1/hakijat", "rest/v1/hakijat") -> new HakijaResource(koosteet.hakijat),
      ("/rest/v1/kkhakijat", "rest/v1/kkhakijat") -> new KkHakijaResource(integrations.hakemukset, integrations.tarjonta, koosteet.haut, integrations.koodisto, registers.suoritusRekisteri, integrations.valintaTulos),
      ("/rest/v1/opiskelijat", "rest/v1/opiskelijat") -> new HakurekisteriResource[Opiskelija, CreateOpiskelijaCommand](authorizedRegisters.opiskelijaRekisteri, OpiskelijaQuery(_)) with OpiskelijaSwaggerApi with HakurekisteriCrudCommands[Opiskelija, CreateOpiskelijaCommand] with SpringSecuritySupport,
      ("/rest/v1/oppijat", "rest/v1/oppijat") -> new OppijaResource(authorizedRegisters, integrations.hakemukset, koosteet.ensikertalainen),
      ("/rest/v1/opiskeluoikeudet", "rest/v1/opiskeluoikeudet") -> new HakurekisteriResource[Opiskeluoikeus, CreateOpiskeluoikeusCommand](authorizedRegisters.opiskeluoikeusRekisteri, OpiskeluoikeusQuery(_)) with OpiskeluoikeusSwaggerApi with HakurekisteriCrudCommands[Opiskeluoikeus, CreateOpiskeluoikeusCommand] with SpringSecuritySupport,
      ("/rest/v1/suoritukset", "rest/v1/suoritukset") -> new HakurekisteriResource[Suoritus, CreateSuoritusCommand](authorizedRegisters.suoritusRekisteri, SuoritusQuery(_)) with SuoritusSwaggerApi with HakurekisteriCrudCommands[Suoritus, CreateSuoritusCommand] with SpringSecuritySupport,
      ("/rest/v1/rekisteritiedot", "rest/v1/rekisteritiedot") -> new RekisteritiedotResource(authorizedRegisters, config.oids),
      ("/schemas", "schema") -> new SchemaServlet(Perustiedot, PerustiedotKoodisto, Arvosanat, ArvosanatKoodisto),
      ("/virta", "virta") -> new VirtaResource(koosteet.virtaQueue, config.oids),
      ("/ytl", "ytl") -> new YtlResource(integrations.ytl),
      ("/lokalisointi", "lokalisointi") -> new LocalizationProxyResource,
      ("/organisaatio-service", "organisaatio") -> new OrganizationProxyResource(config, system),
      ("/authentication-service", "authentication") -> new AuthenticationProxyResource
    )

    context mount (new ValidatorJavascriptServlet, "/hakurekisteri-validator")
  }

  def mountServlets(context: ServletContext)(servlets: ((String, String), Servlet with Handler)*) = {
    implicit val sc = context
    for (((path, name), servlet) <- servlets) context.mount(handler = servlet, urlPattern = path, name = name, loadOnStartup = 1)
  }

  override def destroy(context: ServletContext) {
    import concurrent.duration._
    system.shutdown()
    system.awaitTermination(15.seconds)
    OPHSecurity.destroy(context)
  }
}

object OPHSecurity extends ContextLoader with LifeCycle {
  val config = OPHConfig(Config.config.ophConfDir,
    Config.config.propertyLocations,
    "cas_mode" -> "front",
    "cas_key" -> "suoritusrekisteri",
    "spring_security_default_access" -> "hasRole('ROLE_APP_SUORITUSREKISTERI')",
    "cas_service" -> "${cas.service.suoritusrekisteri}",
    "cas_callback_url" -> "${cas.callback.suoritusrekisteri}"
  )

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
    config
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