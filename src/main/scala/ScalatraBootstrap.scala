import _root_.akka.actor.{Props, ActorSystem}
import fi.vm.sade.hakurekisteri.actor.SuoritusActor
import fi.vm.sade.hakurekisteri.rest.{HakurekisteriSwagger, SuoritusServlet, ResourcesApp}
import org.scalatra._
import javax.servlet.ServletContext



class ScalatraBootstrap extends LifeCycle {

  implicit val swagger = new HakurekisteriSwagger

  override def init(context: ServletContext) {
    val system = ActorSystem()
    val actor = system.actorOf(Props(new SuoritusActor(Seq())))
    context mount(new SuoritusServlet(system, actor), "/rest/v1/suoritukset")
    context mount (new ResourcesApp, "/api-docs/*")
  }
}
