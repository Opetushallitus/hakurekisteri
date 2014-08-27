import java.io.{Reader, File, InputStream}
import java.nio.file.{Path, Files, Paths}
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.servlet.{DispatcherType, ServletContext, ServletContextEvent}

import _root_.akka.actor.{ActorRef, ActorSystem, Props}
import _root_.akka.util.Timeout
import com.stackmob.newman.ApacheHttpClient
import fi.vm.sade.hakurekisteri.arvosana._
import fi.vm.sade.hakurekisteri.ensikertalainen.EnsikertalainenResource
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.healthcheck.{HealthcheckActor, HealthcheckResource}
import fi.vm.sade.hakurekisteri.henkilo._
import fi.vm.sade.hakurekisteri.integration.hakemus.{AkkaHakupalvelu, ReloadHaku, HakemusActor}
import fi.vm.sade.hakurekisteri.integration.tarjonta.TarjontaActor
import fi.vm.sade.hakurekisteri.integration.{VirkailijaRestClient, henkilo}
import fi.vm.sade.hakurekisteri.integration.koodisto.RestKoodistopalvelu
import fi.vm.sade.hakurekisteri.integration.organisaatio.{OrganisaatioActor, RestOrganisaatiopalvelu}
import fi.vm.sade.hakurekisteri.integration.sijoittelu.{RestSijoittelupalvelu, SijoitteluActor}
import fi.vm.sade.hakurekisteri.integration.virta.{VirtaConfig, VirtaClient, VirtaActor}
import fi.vm.sade.hakurekisteri.opiskelija._
import fi.vm.sade.hakurekisteri.opiskeluoikeus._
import fi.vm.sade.hakurekisteri.organization.{FutureOrganizationHierarchy, OrganizationHierarchy}
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.suoritus._
import gui.GuiServlet
import org.scalatra._
import org.scalatra.swagger.Swagger
import org.slf4j.LoggerFactory
import org.springframework.beans.MutablePropertyValues
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer
import org.springframework.core.io.FileSystemResource
import org.springframework.web.context._
import org.springframework.web.context.support.XmlWebApplicationContext
import org.springframework.web.filter.DelegatingFilterProxy

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.slick.driver.JdbcDriver.simple._
import scala.util.Try





class ScalatraBootstrap extends LifeCycle {

  import Config._
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val system = ActorSystem("hakurekisteri")
  implicit val ec:ExecutionContext = system.dispatcher

  override def init(context: ServletContext) {
    OPHSecurity init context
    val database = Try(Database.forName(jndiName)).recover {
      case _: javax.naming.NoInitialContextException => Database.forURL("jdbc:h2:file:data/sample", driver = "org.h2.Driver")
    }.get

    //val camel = CamelExtension(system)
    //val broker = "activemq"
    //camel.context.addComponent(broker, ActiveMQComponent.activeMQComponent(amqUrl))
    val log = LoggerFactory.getLogger(getClass)

    //log.debug(s"AuditLog using uri: $amqUrl")

    import scala.reflect.runtime.universe._

    def getBroadcastForLogger[A <: Resource[I]: TypeTag: ClassTag, I](rekisteri: ActorRef) = {
      // system.actorOf(Props.empty.withRouter(BroadcastRouter(routees = List(rekisteri, system.actorOf(Props(new AuditLog[A](typeOf[A].typeSymbol.name.toString)).withDispatcher("akka.hakurekisteri.audit-dispatcher"), typeOf[A].typeSymbol.name.toString.toLowerCase+"-audit") ))))
      rekisteri
    }

    def authorizer[A <: Resource[I] : ClassTag: Manifest, I](guarded: ActorRef, orgFinder: A => String): ActorRef = {
      val resource = typeOf[A].typeSymbol.name.toString.toLowerCase
      system.actorOf(Props(new OrganizationHierarchy[A, I](organisaatioSoapServiceUrl, guarded, orgFinder)), s"$resource-authorizer")
    }

    val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(new SuoritusJournal(database))), "suoritukset")
    val filteredSuoritusRekisteri = authorizer[Suoritus, UUID](suoritusRekisteri, (suoritus) => suoritus.myontaja)

    val opiskelijaRekisteri = system.actorOf(Props(new OpiskelijaActor(new OpiskelijaJournal(database))), "opiskelijat")
    val filteredOpiskelijaRekisteri = system.actorOf(Props(new OrganizationHierarchy[Opiskelija, UUID](organisaatioSoapServiceUrl,opiskelijaRekisteri, (opiskelija) => opiskelija.oppilaitosOid)), "opiskelijat-authorizer")

    val opiskeluoikeusRekisteri = system.actorOf(Props(new OpiskeluoikeusActor(new OpiskeluoikeusJournal(database))), "opiskeluoikeudet")
    val filteredOpiskeluoikeusRekisteri = system.actorOf(Props(new OrganizationHierarchy[Opiskeluoikeus, UUID](organisaatioSoapServiceUrl, opiskeluoikeusRekisteri, (opiskeluoikeus) => opiskeluoikeus.myontaja)), "opiskeluoikeus-authorizer")

    val henkiloRekisteri = system.actorOf(Props(new HenkiloActor(new HenkiloJournal(database))), "henkilot")
    val filteredHenkiloRekisteri =  system.actorOf(Props(new OrganizationHierarchy[Henkilo, UUID](organisaatioSoapServiceUrl, henkiloRekisteri, (henkilo) => OPH )), "henkilo-authorizer")

    val arvosanaRekisteri = system.actorOf(Props(new ArvosanaActor(new ArvosanaJournal(database))), "arvosanat")

    import _root_.akka.pattern.ask
    val filteredArvosanaRekisteri =  system.actorOf(Props(new FutureOrganizationHierarchy[Arvosana, UUID](organisaatioSoapServiceUrl, arvosanaRekisteri, (arvosana) => suoritusRekisteri.?(arvosana.suoritus)(Timeout(300, TimeUnit.SECONDS)).mapTo[Option[Suoritus]].map(_.map(_.myontaja).getOrElse("")))), "arvosana-authorizer")

    val organisaatiopalvelu: RestOrganisaatiopalvelu = new RestOrganisaatiopalvelu(organisaatioServiceUrl)
    val organisaatiot = system.actorOf(Props(new OrganisaatioActor(organisaatiopalvelu)))

    val sijoittelu = system.actorOf(Props(new SijoitteluActor(new RestSijoittelupalvelu(serviceAccessUrl, sijoitteluServiceUrl, serviceUser, servicePassword), "1.2.246.562.5.2013080813081926341927")))
    val hakemukset = system.actorOf(Props(new HakemusActor(serviceAccessUrl, hakuappServiceUrl, maxApplications, serviceUser, servicePassword)), "hakemus")

    val healthcheck = system.actorOf(Props(new HealthcheckActor(filteredSuoritusRekisteri, filteredOpiskelijaRekisteri, hakemukset)), "healthcheck")

    system.scheduler.schedule(1.second, 2.hours, hakemukset, ReloadHaku("1.2.246.562.5.2013080813081926341927"))
    system.scheduler.schedule(5.minutes, 2.hours, hakemukset, ReloadHaku("1.2.246.562.5.2014022711042555034240"))

    val hakijat = system.actorOf(Props(new HakijaActor(new AkkaHakupalvelu(hakemukset), organisaatiot, new RestKoodistopalvelu(koodistoServiceUrl), sijoittelu)))

    val sanity = system.actorOf(Props(new PerusopetusSanityActor(koodistoServiceUrl, suoritusRekisteri, new ArvosanaJournal(database))), "perusopetus-sanity")

    val httpClient = new ApacheHttpClient()
    val virtaConfig = VirtaConfig(serviceUrl = virtaServiceUrl, jarjestelma = virtaJarjestelma, tunnus = virtaTunnus, avain = virtaAvain)
    val tarjontaClient = new VirkailijaRestClient(serviceUrl = tarjontaServiceUrl)(httpClient, ec)
    val tarjontaActor = system.actorOf(Props(new TarjontaActor(tarjontaClient)), "tarjonta")
    val virta = system.actorOf(Props(new VirtaActor(new VirtaClient(virtaConfig)(httpClient, ec), organisaatiopalvelu, tarjontaActor)), "virta")
    val henkiloClient = new VirkailijaRestClient(serviceAccessUrl = serviceAccessUrl, serviceUrl = henkiloServiceUrl, user = serviceUser, password = servicePassword)(httpClient, ec)
    val henkiloActor = system.actorOf(Props(new henkilo.HenkiloActor(henkiloClient)), "henkilo")

    context mount(new HakurekisteriResource[Suoritus, CreateSuoritusCommand](getBroadcastForLogger[Suoritus, UUID](filteredSuoritusRekisteri), SuoritusQuery(_)) with SuoritusSwaggerApi with HakurekisteriCrudCommands[Suoritus, CreateSuoritusCommand] with SpringSecuritySupport, "/rest/v1/suoritukset")
    context mount(new HakurekisteriResource[Opiskelija, CreateOpiskelijaCommand](getBroadcastForLogger[Opiskelija, UUID](filteredOpiskelijaRekisteri), OpiskelijaQuery(_)) with OpiskelijaSwaggerApi with HakurekisteriCrudCommands[Opiskelija, CreateOpiskelijaCommand] with SpringSecuritySupport, "/rest/v1/opiskelijat")
    context mount(new HakurekisteriResource[Opiskeluoikeus, CreateOpiskeluoikeusCommand](getBroadcastForLogger[Opiskeluoikeus, UUID](filteredOpiskeluoikeusRekisteri), OpiskeluoikeusQuery(_)) with OpiskeluoikeusSwaggerApi with HakurekisteriCrudCommands[Opiskeluoikeus, CreateOpiskeluoikeusCommand] with SpringSecuritySupport, "/rest/v1/opiskeluoikeudet")
    context mount(new HakurekisteriResource[Henkilo, CreateHenkiloCommand](getBroadcastForLogger[Henkilo, UUID](filteredHenkiloRekisteri), HenkiloQuery(_)) with HenkiloSwaggerApi with HakurekisteriCrudCommands[Henkilo, CreateHenkiloCommand] with SpringSecuritySupport, "/rest/v1/henkilot")
    context mount(new HakurekisteriResource[Arvosana, CreateArvosanaCommand](getBroadcastForLogger[Arvosana, UUID](filteredArvosanaRekisteri), ArvosanaQuery(_)) with ArvosanaSwaggerApi with HakurekisteriCrudCommands[Arvosana, CreateArvosanaCommand] with SpringSecuritySupport, "/rest/v1/arvosanat")
    context mount(new HakijaResource(hakijat), "/rest/v1/hakijat")
    context mount(new EnsikertalainenResource(suoritusRekisteri, opiskeluoikeusRekisteri, virta, henkiloActor, tarjontaActor), "/rest/v1/ensikertalainen")
    context mount(new HealthcheckResource(healthcheck), "/healthcheck")
    context mount(new ResourcesApp, "/rest/v1/api-docs/*")
    context mount(new SanityResource(sanity), "/sanity")
    context mount(classOf[GuiServlet], "/")
  }

  override def destroy(context: ServletContext) {
    system.shutdown()
    system.awaitTermination(15.seconds)
    OPHSecurity.destroy(context)
  }
}


object OPHSecurity extends ContextLoader with LifeCycle {
  val config = OPHConfig(Config.ophConfDir,
    Config.propertyLocations,
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
  setConfigLocation("file:" + confDir + "security-context-backend.xml")


  val resources:Seq[FileSystemResource] = for (
    fileName <- propertyFiles.reverse
  ) yield new FileSystemResource(confDir.resolve(fileName).toAbsolutePath.toString)

  val placeholder = Bean(
    classOf[PropertySourcesPlaceholderConfigurer],
    "localOverride" -> true,
    "properties" -> localProperties,
    "locations" -> resources.toArray
  )

  override def initBeanDefinitionReader(beanDefinitionReader: XmlBeanDefinitionReader) {
    beanDefinitionReader.getRegistry.registerBeanDefinition("propertyPlaceHolder", placeholder)
  }

  object Bean {
    def apply[C,A,B](clazz:Class[C], props: (A,B)*) = {
      val definition = new RootBeanDefinition(clazz)
      definition.setPropertyValues(new MutablePropertyValues(Map(props: _*).asJava))
      definition
    }
  }
}
