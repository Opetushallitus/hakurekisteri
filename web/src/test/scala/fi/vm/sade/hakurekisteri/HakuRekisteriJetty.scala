package fi.vm.sade.hakurekisteri

import java.io.IOException
import java.net.Socket
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.util.resource.ResourceCollection
import org.eclipse.jetty.webapp.WebAppContext
import org.scalatra.servlet.ScalatraListener
import scala.util.Random

object HakuRekisteriJetty extends App {
  new HakuRekisteriJetty(8080).start
}

object SharedJetty {
  private lazy val jetty = new HakuRekisteriJetty(config = Config.mockConfig)
  def start = jetty.start
  def port = jetty.port
}

class HakuRekisteriJetty(val port: Int = PortFinder.findFreeLocalPort, config: Config = Config.globalConfig) {
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

object PortFinder {
  def findFreeLocalPort: Int = {
    val range = 1024 to 60000
    val port = ((range(new Random().nextInt(range length))))
    if (isFreeLocalPort(port)) {
      port
    } else {
      findFreeLocalPort
    }
  }

  def isFreeLocalPort(port: Int): Boolean = {
    try {
      val socket = new Socket("127.0.0.1", port)
      socket.close()
      false
    } catch {
      case e: IOException => true
    }
  }
}