import _root_.akka.actor.{Props, ActorSystem}
import fi.vm.sade.hakurekisteri.opiskelija.{OpiskelijaQuery, Opiskelija, OpiskelijaActor, OpiskelijaSwaggerApi}
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriResource, ResourcesApp, HakurekisteriSwagger}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusQuery, Suoritus, SuoritusSwaggerApi, SuoritusActor}
import gui.GuiServlet
import org.scalatra._
import javax.servlet.ServletContext
import org.scalatra.swagger.Swagger


class ScalatraBootstrap extends LifeCycle {

  implicit val swagger:Swagger = new HakurekisteriSwagger
  implicit val system = ActorSystem()

  override def init(context: ServletContext) {
    val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(Seq())))
    val opiskelijaRekisteri = system.actorOf(Props(new OpiskelijaActor(Seq())))
    context mount(new HakurekisteriResource[Suoritus](suoritusRekisteri, SuoritusQuery(_)) with SuoritusSwaggerApi, "/rest/v1/suoritukset")
    context mount(new HakurekisteriResource[Opiskelija](opiskelijaRekisteri, OpiskelijaQuery(_)) with OpiskelijaSwaggerApi, "/rest/v1/opiskelijat")
    context mount(new ResourcesApp, "/rest/v1/api-docs/*")
    context mount(classOf[GuiServlet], "/")
  }
}
