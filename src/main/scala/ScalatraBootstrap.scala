import fi.vm.sade.hakurekisteri._
import org.scalatra._
import javax.servlet.ServletContext

class ScalatraBootstrap extends LifeCycle {
  override def init(context: ServletContext) {

    //context.mount(new SuoritusServlet(Seq(new Suoritus("1.2.3", "KESKEN", "9", "2014", "K", "9D", "1.2.4"))), "/rest/v1/suoritukset")
  }
}
