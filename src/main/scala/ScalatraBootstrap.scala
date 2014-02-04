import _root_.akka.actor.{Props, ActorSystem}
import fi.vm.sade.hakurekisteri.opiskelija.OpiskelijaServlet
import fi.vm.sade.hakurekisteri.rest.support.{ResourcesApp, HakurekisteriSwagger}
import fi.vm.sade.hakurekisteri.suoritus.SuoritusActor
import gui.GuiServlet
import org.scalatra._
import javax.servlet.ServletContext



class ScalatraBootstrap extends LifeCycle {

  implicit val swagger = new HakurekisteriSwagger
  implicit val system = ActorSystem()

  override def init(context: ServletContext) {
    val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(Seq())))
    val opiskelijaRekisteri = system.actorOf(Props(new SuoritusActor(Seq())))
    //context mount(new SuoritusServlet(suoritusRekisteri), "/rest/v1/suoritukset")
    context mount(new OpiskelijaServlet(opiskelijaRekisteri), "/rest/v1/opiskelijat")
    context mount(new ResourcesApp, "/rest/v1/api-docs/*")
    context mount(classOf[GuiServlet], "/")
  }
}
