import _root_.akka.actor.{Props, ActorSystem}
import fi.vm.sade.hakurekisteri.opiskelija.{OpiskelijaQuery, Opiskelija, OpiskelijaActor, OpiskelijaSwaggerApi}
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriResource, ResourcesApp, HakurekisteriSwagger}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusQuery, Suoritus, SuoritusSwaggerApi, SuoritusActor}
import gui.GuiServlet
import java.util
import java.util.Properties
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


class ScalatraBootstrap extends LifeCycle {

  implicit val swagger:Swagger = new HakurekisteriSwagger
  implicit val system = ActorSystem()

  override def init(context: ServletContext) {
    OPHSecurity init context

    val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(Seq())))
    val opiskelijaRekisteri = system.actorOf(Props(new OpiskelijaActor(Seq())))
    context mount(new HakurekisteriResource[Suoritus](suoritusRekisteri, SuoritusQuery(_)) with SuoritusSwaggerApi, "/rest/v1/suoritukset")
    context mount(new HakurekisteriResource[Opiskelija](opiskelijaRekisteri, OpiskelijaQuery(_)) with OpiskelijaSwaggerApi, "/rest/v1/opiskelijat")
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

  val cleanupListener = new ContextCleanupListener

  override def init(context: ServletContext) {

    initWebApplicationContext(context)

    val security = context.addFilter("springSecurityFilterChain", classOf[DelegatingFilterProxy])
    security.addMappingForUrlPatterns(util.EnumSet.of(DispatcherType.REQUEST,DispatcherType.FORWARD), true, "/*")
  }


  override def destroy(context: ServletContext) {
    closeWebApplicationContext(context)
    cleanupListener.contextDestroyed(new ServletContextEvent(context))
  }

  override def createWebApplicationContext(sc: ServletContext): WebApplicationContext = OPHConfig(
    "cas_mode" -> "front",
    "cas_key" -> "suoritusrekisteri",
    "spring_security_default_access" -> "hasRole('ROLE_APP_SUORITUSREKISTERI')",
    "cas_service" -> "${cas.service.suoritusrekisteri}",
    "cas_callback_url" -> "${cas.callback.suoritusrekisteri}"
  )


}

case class OPHConfig(props:(String, String)*) extends XmlWebApplicationContext {


  val propertyLocations = Seq("override.properties", "suoritusrekisteri.properties", "common.properties")

  val localProperties = (new Properties /: Map(props: _*)) {case (props, (k,v)) => props.put(k,v); props}

  val homeDir = sys.props.get("user.home").getOrElse("")

  val ophConfDir = homeDir + "/oph-configuration/"

  setConfigLocation("file:" + ophConfDir + "security-context-backend.xml")

  val resources = for {
    file <- propertyLocations.reverse
  } yield new FileSystemResource(ophConfDir + file)


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
