import _root_.akka.actor.{Props, ActorSystem}
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.healthcheck.{HealthcheckActor, HealthcheckResource}
import fi.vm.sade.hakurekisteri.henkilo._
import fi.vm.sade.hakurekisteri.henkilo.Henkilo
import fi.vm.sade.hakurekisteri.opiskelija._
import fi.vm.sade.hakurekisteri.organization.OrganizationHierarchy
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.suoritus._
import gui.GuiServlet
import org.scalatra._
import javax.servlet.{ServletContextEvent, DispatcherType, ServletContext}
import org.scalatra.swagger.Swagger
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
import scala.concurrent.ExecutionContext
import scala.slick.driver.JdbcDriver.simple._
import scala.util.Try


class ScalatraBootstrap extends LifeCycle {

  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val system = ActorSystem()
  implicit val ec:ExecutionContext = system.dispatcher
  val jndiName = "java:comp/env/jdbc/suoritusrekisteri"
  val OPH = "1.2.246.562.10.00000000001"
  val serviceUrlQa = "https://testi.virkailija.opintopolku.fi/organisaatio-service"

  override def init(context: ServletContext) {
    OPHSecurity init context
    val orgServiceUrl = OPHSecurity.config.properties.get("cas.service.organisaatio-service").getOrElse(serviceUrlQa) + "/services/organisaatioService"
    val database = Try(Database.forName(jndiName)).recover {
      case _: javax.naming.NoInitialContextException => Database.forURL("jdbc:h2:file:data/sample", driver = "org.h2.Driver")
    }.get
    val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(new SuoritusJournal(database))))
    val filteredSuoritusRekisteri = system.actorOf(Props(new OrganizationHierarchy[Suoritus](orgServiceUrl ,suoritusRekisteri, (suoritus) => suoritus.komoto.tarjoaja )))

    val opiskelijaRekisteri = system.actorOf(Props(new OpiskelijaActor(new OpiskelijaJournal(database))))
    val filteredOpiskelijaRekisteri = system.actorOf(Props(new OrganizationHierarchy[Opiskelija](orgServiceUrl,opiskelijaRekisteri, (opiskelija) => opiskelija.oppilaitosOid )))

    val henkiloRekisteri = system.actorOf(Props(new HenkiloActor(new HenkiloJournal(database))))
    val filteredHenkiloRekisteri =  system.actorOf(Props(new OrganizationHierarchy[Henkilo](orgServiceUrl,henkiloRekisteri, (henkilo) => OPH )))

    val healthcheck = system.actorOf(Props(new HealthcheckActor(filteredSuoritusRekisteri, filteredOpiskelijaRekisteri)))

    val hakijat = system.actorOf(Props(new HakijaActor(new RestHakupalvelu, new RestOrganisaatiopalvelu)))

    context mount(new HakurekisteriResource[Suoritus, CreateSuoritusCommand](filteredSuoritusRekisteri, SuoritusQuery(_)) with SuoritusSwaggerApi with HakurekisteriCrudCommands[Suoritus, CreateSuoritusCommand] with SpringSecuritySupport, "/rest/v1/suoritukset")
    context mount(new HakurekisteriResource[Opiskelija, CreateOpiskelijaCommand](filteredOpiskelijaRekisteri, OpiskelijaQuery(_)) with OpiskelijaSwaggerApi with HakurekisteriCrudCommands[Opiskelija, CreateOpiskelijaCommand] with SpringSecuritySupport, "/rest/v1/opiskelijat")
    context mount(new HakurekisteriResource[Henkilo, CreateHenkiloCommand](filteredHenkiloRekisteri, HenkiloQuery(_)) with HenkiloSwaggerApi with HakurekisteriCrudCommands[Henkilo, CreateHenkiloCommand] with SpringSecuritySupport, "/rest/v1/henkilot")
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
      resolve(converted.mapValues((s) => "€\\{(.*?)\\}".r replaceAllIn (s, m => {converted.getOrElse(m.group(1), "€{" + (m.group(1)) + "}") })))
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
