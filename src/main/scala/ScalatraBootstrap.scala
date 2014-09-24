import fi.vm.sade.hakurekisteri.oppija.OppijaResource
import java.util.concurrent.atomic.AtomicInteger

import _root_.akka.camel.CamelExtension
import _root_.akka.routing.BroadcastRouter
import fi.vm.sade.hakurekisteri.integration.audit.AuditUri
import fi.vm.sade.hakurekisteri.integration.haku.{HakuResource, HakuActor}
import fi.vm.sade.hakurekisteri.integration.parametrit.ParameterActor
import fi.vm.sade.hakurekisteri.integration.ytl.{YTLConfig, KokelasRequest, YtlActor}
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.{ThreadFactory, Executors, TimeUnit}
import javax.servlet.{Servlet, DispatcherType, ServletContext, ServletContextEvent}

import _root_.akka.actor.{ActorRef, ActorSystem, Props}
import _root_.akka.util.Timeout
import com.stackmob.newman.{HttpClient, ApacheHttpClient}
import fi.vm.sade.hakurekisteri.arvosana._
import fi.vm.sade.hakurekisteri.ensikertalainen.{EnsikertalainenQuery, EnsikertalainenActor, EnsikertalainenResource}
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.healthcheck.{HealthcheckActor, HealthcheckResource}
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.tarjonta.TarjontaActor
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.koodisto.KoodistoActor
import fi.vm.sade.hakurekisteri.integration.organisaatio.OrganisaatioActor
import fi.vm.sade.hakurekisteri.integration.sijoittelu.SijoitteluActor
import fi.vm.sade.hakurekisteri.integration.virta.{VirtaConfig, VirtaClient, VirtaActor}
import fi.vm.sade.hakurekisteri.opiskelija._
import fi.vm.sade.hakurekisteri.opiskeluoikeus._
import fi.vm.sade.hakurekisteri.organization.{FutureOrganizationHierarchy, OrganizationHierarchy}
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.suoritus._
import gui.GuiServlet
import org.apache.activemq.camel.component.ActiveMQComponent
import org.apache.http.conn.ClientConnectionManager
import org.apache.http.impl.NoConnectionReuseStrategy
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.impl.conn.{SchemeRegistryFactory, PoolingClientConnectionManager}
import org.apache.http.params.HttpConnectionParams
import org.scalatra._
import org.scalatra.swagger.Swagger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanDefinition
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
  import AuthorizedRegisters._
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val system = ActorSystem("hakurekisteri")
  implicit val ec:ExecutionContext = system.dispatcher

  override def init(context: ServletContext) {
    OPHSecurity init context

    val journals = new DbJournals(jndiName)
    val registers = new BareRegisters(system, journals)
    val authorizedRegisters = filter(registers) withAuthorizationDataFrom organisaatioSoapServiceUrl

    val sanity = system.actorOf(Props(new PerusopetusSanityActor(koodistoServiceUrl, registers.suoritusRekisteri, journals.arvosanaJournal)), "perusopetus-sanity")

    val integrations = new BaseIntegrations(virtaConfig, henkiloConfig, tarjontaConfig, organisaatioConfig, sijoitteluConfig, parameterConfig, hakemusConfig, ytlConfig, koodistoConfig, registers, system)


    val koosteet = new BaseKoosteet(system, integrations, registers)

    val healthcheck = system.actorOf(Props(new HealthcheckActor(authorizedRegisters.arvosanaRekisteri, authorizedRegisters.opiskelijaRekisteri, authorizedRegisters.opiskeluoikeusRekisteri, authorizedRegisters.suoritusRekisteri, integrations.ytl ,  integrations.hakemukset, koosteet.ensikertalainen)), "healthcheck")



    mountServlets(context) (
      "/" -> new GuiServlet,
      "/healthcheck" -> new HealthcheckResource(healthcheck),
      "/rest/v1/api-docs/*" -> new ResourcesApp,
      "/rest/v1/arvosanat" -> new HakurekisteriResource[Arvosana, CreateArvosanaCommand](authorizedRegisters.arvosanaRekisteri, ArvosanaQuery(_)) with ArvosanaSwaggerApi with HakurekisteriCrudCommands[Arvosana, CreateArvosanaCommand] with SpringSecuritySupport,
      "/rest/v1/ensikertalainen" -> new EnsikertalainenResource(koosteet.ensikertalainen),
      "/rest/v1/haut" -> new HakuResource(koosteet.haut),
      "/rest/v1/hakijat" -> new HakijaResource(koosteet.hakijat),
      "/rest/v1/opiskelijat" -> new HakurekisteriResource[Opiskelija, CreateOpiskelijaCommand](authorizedRegisters.opiskelijaRekisteri, OpiskelijaQuery(_)) with OpiskelijaSwaggerApi with HakurekisteriCrudCommands[Opiskelija, CreateOpiskelijaCommand] with SpringSecuritySupport,
      "/rest/v1/oppijat" -> new OppijaResource(registers, integrations.hakemukset, koosteet.ensikertalainen),
      "/rest/v1/opiskeluoikeudet" -> new HakurekisteriResource[Opiskeluoikeus, CreateOpiskeluoikeusCommand](authorizedRegisters.opiskeluoikeusRekisteri, OpiskeluoikeusQuery(_)) with OpiskeluoikeusSwaggerApi with HakurekisteriCrudCommands[Opiskeluoikeus, CreateOpiskeluoikeusCommand] with SpringSecuritySupport,
      "/rest/v1/suoritukset" -> new HakurekisteriResource[Suoritus, CreateSuoritusCommand](authorizedRegisters.suoritusRekisteri, SuoritusQuery(_)) with SuoritusSwaggerApi with HakurekisteriCrudCommands[Suoritus, CreateSuoritusCommand] with SpringSecuritySupport,
      "/sanity" -> new SanityResource(sanity)
    )
  }

  def mountServlets(context: ServletContext)(servlets: (String, Servlet with Handler)*) = {
    implicit val sc = context
    for (
      (path, servlet) <- servlets
    ) mountServlet(servlet, path)

  }

  def mountServlet(servlet: Servlet with Handler, path: String = "/")(implicit context: ServletContext) {
    val s = Option(context.addServlet(servlet.getClass.getName, servlet))
    s foreach (d => {
      d.setLoadOnStartup(1)
      d.setAsyncSupported(true)
    })
    context.mount(servlet, path)
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

trait Journals {
  val suoritusJournal: SuoritusJournal
  val opiskelijaJournal: OpiskelijaJournal
  val opiskeluoikeusJournal: OpiskeluoikeusJournal
  val arvosanaJournal: ArvosanaJournal
}

class DbJournals(jndiName:String) extends Journals {
  val database = Try(Database.forName(jndiName)).recover {
    case _: javax.naming.NoInitialContextException => Database.forURL("jdbc:h2:file:data/sample", driver = "org.h2.Driver")
  }.get

  override val suoritusJournal = new SuoritusJournal(database)
  override val opiskelijaJournal = new OpiskelijaJournal(database)
  override val opiskeluoikeusJournal = new OpiskeluoikeusJournal(database)
  override val arvosanaJournal = new ArvosanaJournal(database)
}



class BareRegisters(system: ActorSystem, journals: Journals) extends Registers {
  override val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(journals.suoritusJournal)), "suoritukset")
  override val opiskelijaRekisteri = system.actorOf(Props(new OpiskelijaActor(journals.opiskelijaJournal)), "opiskelijat")
  override val opiskeluoikeusRekisteri = system.actorOf(Props(new OpiskeluoikeusActor(journals.opiskeluoikeusJournal)), "opiskeluoikeudet")
  override val arvosanaRekisteri = system.actorOf(Props(new ArvosanaActor(journals.arvosanaJournal)), "arvosanat")
}

class AuthorizedRegisters(organisaatioSoapServiceUrl: String, unauthorized: Registers, system: ActorSystem) extends Registers {
  import _root_.akka.pattern.ask
  import scala.reflect.runtime.universe._
  implicit val ec:ExecutionContext = system.dispatcher

  def authorizer[A <: Resource[I] : ClassTag: Manifest, I](guarded: ActorRef, orgFinder: A => String): ActorRef = {
    val resource = typeOf[A].typeSymbol.name.toString.toLowerCase
    system.actorOf(Props(new OrganizationHierarchy[A, I](organisaatioSoapServiceUrl, guarded, (i: A) => Seq(orgFinder(i)))), s"$resource-authorizer")
  }

  override val suoritusRekisteri = authorizer[Suoritus, UUID](unauthorized.suoritusRekisteri, (suoritus) => suoritus.myontaja)
  override val opiskelijaRekisteri = authorizer[Opiskelija, UUID](unauthorized.opiskelijaRekisteri, (opiskelija) => opiskelija.oppilaitosOid)
  override val opiskeluoikeusRekisteri = authorizer[Opiskeluoikeus, UUID](unauthorized.opiskeluoikeusRekisteri, (opiskeluoikeus) => opiskeluoikeus.myontaja)
  override val arvosanaRekisteri =  system.actorOf(Props(new FutureOrganizationHierarchy[Arvosana, UUID](organisaatioSoapServiceUrl, unauthorized.arvosanaRekisteri, (arvosana) => unauthorized.suoritusRekisteri.?(arvosana.suoritus)(Timeout(300, TimeUnit.SECONDS)).mapTo[Option[Suoritus]].map(_.map((s) => Seq(s.myontaja, s.source, arvosana.source)).getOrElse(Seq())))), "arvosana-authorizer")
}

object AuthorizedRegisters {
  def filter(unauthorized: Registers): AuthorizerBuilder = new AuthorizerBuilder(unauthorized)

  class AuthorizerBuilder(registers: Registers) {
    def withAuthorizationDataFrom(url: String)(implicit system: ActorSystem) = new AuthorizedRegisters(url, registers, system)
  }
}

class AuditedRegisters(amqUrl: String, amqQueue: String, authorizedRegisters: Registers, system: ActorSystem) extends Registers {
  val log = LoggerFactory.getLogger(getClass)

  //audit
  val camel = CamelExtension(system)
  val broker = "activemq"
  camel.context.addComponent(broker, ActiveMQComponent.activeMQComponent(amqUrl))
  implicit val audit = AuditUri(broker, amqQueue)

  log.debug(s"AuditLog using uri: $amqUrl")

  val suoritusRekisteri = getBroadcastForLogger[Suoritus, UUID](authorizedRegisters.suoritusRekisteri)
  val opiskelijaRekisteri = getBroadcastForLogger[Opiskelija, UUID](authorizedRegisters.opiskelijaRekisteri)
  val opiskeluoikeusRekisteri = getBroadcastForLogger[Opiskeluoikeus, UUID](authorizedRegisters.opiskeluoikeusRekisteri)
  val arvosanaRekisteri = getBroadcastForLogger[Arvosana, UUID](authorizedRegisters.arvosanaRekisteri)

  import fi.vm.sade.hakurekisteri.integration.audit.AuditLog
  import scala.reflect.runtime.universe._

  def getBroadcastForLogger[A <: Resource[I]: TypeTag: ClassTag, I: TypeTag: ClassTag](rekisteri: ActorRef) = {
    system.actorOf(Props.empty.withRouter(BroadcastRouter(routees = List(rekisteri, system.actorOf(Props(new AuditLog[A, I](typeOf[A].typeSymbol.name.toString)).withDispatcher("akka.hakurekisteri.audit-dispatcher"), typeOf[A].typeSymbol.name.toString.toLowerCase+"-audit") ))))
  }
}

trait Integrations {
  val virta: ActorRef
  val henkilo: ActorRef
  val organisaatiot: ActorRef
  val sijoittelu: ActorRef
  val hakemukset: ActorRef
  val tarjonta: ActorRef
  val koodisto: ActorRef
  val ytl: ActorRef
  val parametrit: ActorRef
}

class BaseIntegrations(virtaConfig: VirtaConfig,
                       henkiloConfig: ServiceConfig,
                       tarjontaConfig: ServiceConfig,
                       organisaatioConfig: ServiceConfig,
                       sijoitteluConfig: ServiceConfig,
                       parameterConfig: ServiceConfig,
                       hakemusConfig: HakemusConfig,
                       ytlConfig: Option[YTLConfig],
                       koodistoConfig: ServiceConfig,
                       rekisterit: Registers,
                       system: ActorSystem) extends Integrations {

  def getClient: HttpClient = getClient("default")
  
  val socketTimeout = 120000
  val connectionTimeout = 10000
  val maxConnections = 100

  def createApacheHttpClient: org.apache.http.client.HttpClient = {
    val connManager: ClientConnectionManager = {
      val cm = new PoolingClientConnectionManager()
      cm.setDefaultMaxPerRoute(maxConnections)
      cm.setMaxTotal(maxConnections)
      cm
    }

    val client = new DefaultHttpClient(connManager)
    val httpParams = client.getParams
    HttpConnectionParams.setConnectionTimeout(httpParams, connectionTimeout)
    HttpConnectionParams.setSoTimeout(httpParams, socketTimeout)
    HttpConnectionParams.setStaleCheckingEnabled(httpParams, false)
    HttpConnectionParams.setSoKeepalive(httpParams, true)
    client.setReuseStrategy(new NoConnectionReuseStrategy())
    client
  }

  def getClient(poolName:String = "default"): HttpClient = {
    if (poolName == "default") new ApacheHttpClient(createApacheHttpClient)()
    else {
      val threadNumber = new AtomicInteger(1)
      val pool = Executors.newFixedThreadPool(8, new ThreadFactory() {
        override def newThread(r: Runnable): Thread = {
          new Thread(r, poolName + "-" + threadNumber.getAndIncrement)
        }
      })
      new ApacheHttpClient(createApacheHttpClient)(ExecutionContext.fromExecutorService(pool))
    }
  }

  implicit val ec: ExecutionContext = system.dispatcher

  val tarjonta = system.actorOf(Props(new TarjontaActor(new VirkailijaRestClient(tarjontaConfig)(getClient, ec))), "tarjonta")

  val organisaatiot = system.actorOf(Props(new OrganisaatioActor(new VirkailijaRestClient(organisaatioConfig)(getClient, ec))))

  val virta = system.actorOf(Props(new VirtaActor(new VirtaClient(virtaConfig)(getClient("virta"), ec), organisaatiot)), "virta")

  val henkilo = system.actorOf(Props(new fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloActor(new VirkailijaRestClient(henkiloConfig)(getClient, ec))), "henkilo")

  val sijoittelu = system.actorOf(Props(new SijoitteluActor(new VirkailijaRestClient(sijoitteluConfig)(getClient, ec))))

  val hakemukset = system.actorOf(Props(new HakemusActor(new VirkailijaRestClient(hakemusConfig.serviceConf)(getClient, ec), hakemusConfig.maxApplications)), "hakemus")

  val ytl = system.actorOf(Props(new YtlActor(henkilo, rekisterit.suoritusRekisteri: ActorRef, rekisterit.arvosanaRekisteri: ActorRef, hakemukset, ytlConfig)), "ytl")

  val koodisto = system.actorOf(Props(new KoodistoActor(new VirkailijaRestClient(koodistoConfig)(getClient, ec))), "koodisto")

  val parametrit = system.actorOf(Props(new ParameterActor(new VirkailijaRestClient(parameterConfig)(getClient(), ec))))
}

trait Koosteet {
  val hakijat: ActorRef
  val ensikertalainen: ActorRef
  val haut: ActorRef
}

class BaseKoosteet(system: ActorSystem, integrations: Integrations, registers: Registers) extends Koosteet {
  implicit val ec: ExecutionContext = system.dispatcher

  val hakijat = system.actorOf(Props(new HakijaActor(new AkkaHakupalvelu(integrations.hakemukset), integrations.organisaatiot, integrations.koodisto, integrations.sijoittelu)), "hakijat")

  override val ensikertalainen: ActorRef = system.actorOf(Props(new EnsikertalainenActor(registers.suoritusRekisteri, registers.opiskeluoikeusRekisteri, integrations.virta, integrations.henkilo, integrations.tarjonta, integrations.hakemukset)), "ensikertalainen")

  val haut = system.actorOf(Props(new HakuActor(integrations.tarjonta, integrations.parametrit, integrations.hakemukset, integrations.sijoittelu, integrations.ytl)))


}