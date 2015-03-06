package fi.vm.sade.hakurekisteri

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.util.resource.ResourceCollection
import org.eclipse.jetty.webapp.WebAppContext

object JettyLauncher {
  def main(args: Array[String]) {
    new JettyLauncher(8080).start.join
  }
}

class JettyLauncher(val port: Int) {
  val server = new Server(port)
  val context = new WebAppContext()
  context.setBaseResource(
    new ResourceCollection(Array("./web/src/main/webapp", "./web/target/javascript")))
  context.setContextPath("/")
  context.setDescriptor("web/src/main/webapp/WEB-INF/web.xml")
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
