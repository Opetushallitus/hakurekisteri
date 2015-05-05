package fi.vm.sade.hakurekisteri

import java.nio.charset.Charset

import fi.vm.sade.utils.Timer
import fi.vm.sade.utils.tcp.PortChecker
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.util.resource.ResourceCollection
import org.eclipse.jetty.webapp.WebAppContext
import org.h2.tools.RunScript
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}
import org.scalatra.servlet.ScalatraListener
import org.scalatra.test.HttpComponentsClient

object HakuRekisteriJetty extends App {
  new HakuRekisteriJetty(8080).start
}

object HakuRekisteriJettyWithMocks extends App {
  new HakuRekisteriJetty(8080, config = Config.mockConfig).start
}

object SharedJetty {
  private val config = Config.mockConfig
  private lazy val jetty = new HakuRekisteriJetty(config = config)

  def start = {
    Timer.timed("Jetty start") {
      if (!jetty.server.isRunning) {
        RunScript.execute(config.h2DatabaseUrl, "", "", "classpath:clear-h2.sql", Charset.forName("UTF-8"), false)
      }
      jetty.start
    }
  }

  def restart = {
    if (jetty.server.isRunning) {
      Timer.timed("Jetty stop") {
        jetty.server.stop
      }
    }
    start
  }

  def port = jetty.port
}

class HakuRekisteriJetty(val port: Int = PortChecker.findFreeLocalPort, config: Config = Config.globalConfig) {
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

trait CleanSharedJetty extends BeforeAndAfterAll with HttpComponentsClient {
  this: Suite =>
  val port = SharedJetty.port
  val baseUrl = s"http://localhost:$port"

  override def beforeAll() = {
    SharedJetty.restart
    super.beforeAll()
  }
}

trait CleanSharedJettyBeforeEach extends BeforeAndAfterEach with HttpComponentsClient {
  this: Suite =>
  val port = SharedJetty.port
  val baseUrl = s"http://localhost:$port"

  override def beforeEach() = {
    SharedJetty.restart
    super.beforeEach()
  }
}