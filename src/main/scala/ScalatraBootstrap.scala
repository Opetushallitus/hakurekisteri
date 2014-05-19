import _root_.akka.actor.{ActorRef, Actor, Props, ActorSystem}
import _root_.akka.camel.CamelExtension
import _root_.akka.dispatch.PinnedDispatcher
import _root_.akka.routing.BroadcastRouter
import _root_.akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana._
import fi.vm.sade.hakurekisteri.Audit
import fi.vm.sade.hakurekisteri.audit.{AuditLog, AuditUri}
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.healthcheck.{HealthcheckActor, HealthcheckResource}
import fi.vm.sade.hakurekisteri.henkilo._
import fi.vm.sade.hakurekisteri.henkilo.Henkilo
import fi.vm.sade.hakurekisteri.opiskelija._
import fi.vm.sade.hakurekisteri.organization.{FutureOrganizationHierarchy, OrganizationHierarchy}
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.suoritus._
import gui.GuiServlet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{TimeUnit, ThreadFactory, Executors}
import org.apache.activemq.camel.component.ActiveMQComponent
import org.scalatra._
import javax.servlet.{ServletContextEvent, DispatcherType, ServletContext}
import org.scalatra.swagger.Swagger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader
import org.springframework.beans.MutablePropertyValues
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer
import org.springframework.core.io.FileSystemResource
import org.springframework.web.context.support.XmlWebApplicationContext
import org.springframework.web.context._
import org.springframework.web.filter.DelegatingFilterProxy
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, ExecutionContext}
import scala.reflect.ClassTag
import scala.slick.driver.JdbcDriver.simple._
import scala.util.Try


class ScalatraBootstrap extends LifeCycle {

  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val system = ActorSystem()
  implicit val ec:ExecutionContext = system.dispatcher
  val jndiName = "java:comp/env/jdbc/suoritusrekisteri"
  val OPH = "1.2.246.562.10.00000000001"
  val organisaatioServiceUrlQa = "https://testi.virkailija.opintopolku.fi/organisaatio-service"
  val hakuappServiceUrlQa = "https://testi.virkailija.opintopolku.fi/haku-app"
  val koodistoServiceUrlQa = "https://testi.virkailija.opintopolku.fi/koodisto-service"



  private val NumThreads = 1000
  private val threadNumber = new AtomicInteger(1)
  lazy val webPool = Executors.newFixedThreadPool(NumThreads, new ThreadFactory() {
    override def newThread(r: Runnable): Thread = {
      new Thread(r, "webpool-" + threadNumber.getAndIncrement)
    }
  })

  lazy val webExec = ExecutionContext.fromExecutorService(webPool)

  override def init(context: ServletContext) {
    OPHSecurity init context
    val orgServiceUrl = OPHSecurity.config.properties.get("cas.service.organisaatio-service").getOrElse(organisaatioServiceUrlQa) + "/services/organisaatioService"
    val database = Try(Database.forName(jndiName)).recover {
      case _: javax.naming.NoInitialContextException => Database.forURL("jdbc:h2:file:data/sample", driver = "org.h2.Driver")
    }.get


    val camel = CamelExtension(system)
    val amqUrl = OPHSecurity.config.properties.get("activemq.brokerurl").getOrElse("failover:tcp://luokka.hard.ware.fi:61616")
    val broker = "activemq"
    camel.context.addComponent(broker, ActiveMQComponent.activeMQComponent(amqUrl))
    val alog = LoggerFactory.getLogger(classOf[AuditLog])

    implicit val audit = AuditUri(broker, OPHSecurity.config.properties.get("activemq.queue.name.log").getOrElse("Sade.Log"))
    alog.debug(s"AuditLog using uri: $amqUrl")

    def getBroadcastForLogger[A](rekisteri: ActorRef) = {

      system.actorOf(Props.empty.withRouter(BroadcastRouter(routees = List(rekisteri, system.actorOf(Props(new AuditLog[A](classOf[A].getName)).withDispatcher("audit-dispatcher"))))))
    }

    def authorizer[A : ClassTag](guarded: ActorRef, orgFinder: A => String): ActorRef = {
      val resource = classOf[A].getName.toLowerCase
      system.actorOf(Props(new OrganizationHierarchy[A](orgServiceUrl, guarded, orgFinder)), s"$resource-authorizer")
    }

    val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(new SuoritusJournal(database))), "suoritukset")
    val filteredSuoritusRekisteri = authorizer[Suoritus](suoritusRekisteri, (suoritus) => suoritus.myontaja)



    val opiskelijaRekisteri = system.actorOf(Props(new OpiskelijaActor(new OpiskelijaJournal(database))), "opiskelijat")
    val filteredOpiskelijaRekisteri = system.actorOf(Props(new OrganizationHierarchy[Opiskelija](orgServiceUrl,opiskelijaRekisteri, (opiskelija) => opiskelija.oppilaitosOid )), "opiskelijat-authorizer")

    val henkiloRekisteri = system.actorOf(Props(new HenkiloActor(new HenkiloJournal(database))), "henkilot")
    val filteredHenkiloRekisteri =  system.actorOf(Props(new OrganizationHierarchy[Henkilo](orgServiceUrl, henkiloRekisteri, (henkilo) => OPH )), "henkilo-authorizer")

    val arvosanaRekisteri = system.actorOf(Props(new ArvosanaActor(new ArvosanaJournal(database))), "arvosanat")

    import _root_.akka.pattern.ask
    val filteredArvosanaRekisteri =  system.actorOf(Props(new FutureOrganizationHierarchy[Arvosana](orgServiceUrl, arvosanaRekisteri, (arvosana) => suoritusRekisteri.?(arvosana.suoritus)(Timeout(300, TimeUnit.SECONDS)).mapTo[Option[Suoritus]].map(_.map(_.myontaja).getOrElse("")))), "arvosana-authorizer")

    val healthcheck = system.actorOf(Props(new HealthcheckActor(filteredSuoritusRekisteri, filteredOpiskelijaRekisteri)), "healthcheck")

    val hakuappServiceUrl = OPHSecurity.config.properties.get("cas.service.haku").getOrElse(hakuappServiceUrlQa)
    val organisaatioServiceUrl = OPHSecurity.config.properties.get("cas.service.organisaatio-service").getOrElse(organisaatioServiceUrlQa)
    val koodistoServiceUrl = OPHSecurity.config.properties.get("cas.service.koodisto-service").getOrElse(koodistoServiceUrlQa)
    val organisaatiopalvelu: RestOrganisaatiopalvelu = new RestOrganisaatiopalvelu(organisaatioServiceUrl)(webExec)
    val organisaatiot = system.actorOf(Props(new OrganisaatioActor(organisaatiopalvelu)))
    val maxApplications = OPHSecurity.config.properties.get("suoritusrekisteri.hakijat.max.applications").getOrElse("2000").toInt
    val hakijat = system.actorOf(Props(new HakijaActor(new RestHakupalvelu(hakuappServiceUrl, maxApplications)(webExec), organisaatiot, new RestKoodistopalvelu(koodistoServiceUrl)(webExec))))

    context mount(new HakurekisteriResource[Suoritus, CreateSuoritusCommand](getBroadcastForLogger[Suoritus](filteredSuoritusRekisteri), SuoritusQuery(_)) with SuoritusSwaggerApi with HakurekisteriCrudCommands[Suoritus, CreateSuoritusCommand] with SpringSecuritySupport, "/rest/v1/suoritukset")
    context mount(new HakurekisteriResource[Opiskelija, CreateOpiskelijaCommand](getBroadcastForLogger[Opiskelija](filteredOpiskelijaRekisteri), OpiskelijaQuery(_)) with OpiskelijaSwaggerApi with HakurekisteriCrudCommands[Opiskelija, CreateOpiskelijaCommand] with SpringSecuritySupport, "/rest/v1/opiskelijat")
    context mount(new HakurekisteriResource[Henkilo, CreateHenkiloCommand](getBroadcastForLogger[Henkilo](filteredHenkiloRekisteri), HenkiloQuery(_)) with HenkiloSwaggerApi with HakurekisteriCrudCommands[Henkilo, CreateHenkiloCommand] with SpringSecuritySupport, "/rest/v1/henkilot")
    context mount(new HakurekisteriResource[Arvosana, CreateArvosanaCommand](getBroadcastForLogger[Arvosana](filteredArvosanaRekisteri), ArvosanaQuery(_)) with ArvosanaSwaggerApi with HakurekisteriCrudCommands[Arvosana, CreateArvosanaCommand] with SpringSecuritySupport, "/rest/v1/arvosanat")
    context mount(new HakijaResource(hakijat), "/rest/v1/hakijat")
    context mount(new HealthcheckResource(healthcheck), "/healthcheck")
    context mount(new ResourcesApp, "/rest/v1/api-docs/*")
    context mount(classOf[GuiServlet], "/")
  }




  override def destroy(context: ServletContext) {
    system.shutdown()
    system.awaitTermination(15.seconds)
    OPHSecurity.destroy(context)
  }
}


object OPHSecurity extends ContextLoader with LifeCycle {

  val config = OPHConfig(
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

case class OPHConfig(props:(String, String)*) extends XmlWebApplicationContext {


  val propertyLocations = Seq("override.properties", "suoritusrekisteri.properties", "common.properties")

  val localProperties = (new java.util.Properties /: Map(props: _*)) {case (newProperties, (k,v)) => newProperties.put(k,v); newProperties}

  val homeDir = sys.props.get("user.home").getOrElse("")

  val ophConfDir = homeDir + "/oph-configuration/"

  setConfigLocation("file:" + ophConfDir + "security-context-backend.xml")

  val resources = for {
    file <- propertyLocations.reverse
  } yield new FileSystemResource(ophConfDir + file)

  def properties: Map[String, String] =  {
    import scala.collection.JavaConversions._
    val rawMap = resources.map((fsr) => {val prop = new java.util.Properties; prop.load(fsr.getInputStream); Map(prop.toList: _*)}).
      reduce(_ ++ _)

    resolve(rawMap)
  }

  def resolve(source: Map[String, String]):Map[String,String] = {
    val converted = source.mapValues(_.replace("${","€{"))
    val unResolved = Set(converted.map((s) => (for (found <- "€\\{(.*?)\\}".r findAllMatchIn s._2) yield found.group(1)).toList).reduce(_ ++ _):_*)
    val unResolvable = unResolved.filter((s) => converted.get(s).isEmpty)
    if ((unResolved -- unResolvable).isEmpty)
      converted.mapValues(_.replace("€{","${"))
    else
      resolve(converted.mapValues((s) => "€\\{(.*?)\\}".r replaceAllIn (s, m => {converted.getOrElse(m.group(1), "€{" + m.group(1) + "}") })))
  }

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
