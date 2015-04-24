package fi.vm.sade.hakurekisteri

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.util.resource.ResourceCollection
import org.eclipse.jetty.webapp.WebAppContext
import org.scalatra.servlet.ScalatraListener

object HakuRekisteriJetty extends App {
  new HakuRekisteriJetty(8080).start
}

class HakuRekisteriJetty(port: Int, config: Config = Config.globalConfig) {
  val root = ProjectRootFinder.findProjectRoot()
  val contextPath = "/"
  val server = new Server(port)
  val context = new WebAppContext()

  context.setAttribute("hakurekisteri.config", config)
  context.setBaseResource(
    new ResourceCollection(Array(root + "/web/src/main/webapp", root + "/web/target/javascript", root + "/web/src/test/front-mock-files")))
  context.setContextPath("/")
  context.setDescriptor(root + "/web/src/main/webapp/WEB-INF/web.xml")
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