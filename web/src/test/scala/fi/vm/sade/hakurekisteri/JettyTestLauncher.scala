package fi.vm.sade.hakurekisteri

import javax.servlet.ServletContext

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.util.resource.ResourceCollection
import org.eclipse.jetty.webapp.WebAppContext
import org.scalatra.LifeCycle
import org.scalatra.servlet.ScalatraListener
import siirto.ValidatorJavascriptServlet

object JettyTestLauncher {
  def main(args: Array[String]) {
    new JettyTestLauncher(8080).start.join
  }
}

class JettyTestLauncher(val port: Int) {
  val server = new Server(port)
  val context = new WebAppContext()
  context.setBaseResource(
    new ResourceCollection(Array("./web/src/main/webapp", "./web/target/javascript", "./web/src/test/front-mock-files")))
  context.setContextPath("/")
  context.setDescriptor("web/src/test/webapp/WEB-INF/web.xml")
  context.setInitParameter(ScalatraListener.LifeCycleKey, "fi.vm.sade.hakurekisteri.TestScalatraBootstrap")
  context.addEventListener(new ScalatraListener)

  server.setHandler(context)

  def start = {
    server.start
    server
  }

  def withJetty[T](block: => T) = {
    val server = start
    try {
      block
    } finally {
      server.stop
    }
  }
}

class TestScalatraBootstrap extends LifeCycle {
  override def init(context: ServletContext) {
    context mount (new ValidatorJavascriptServlet, "/hakurekisteri-validator")
  }
}