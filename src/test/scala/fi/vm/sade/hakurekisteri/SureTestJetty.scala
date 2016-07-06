package fi.vm.sade.hakurekisteri

import java.nio.charset.Charset

import fi.vm.sade.utils.Timer
import fi.vm.sade.utils.tcp.PortChecker
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.util.resource.ResourceCollection
import org.eclipse.jetty.webapp.WebAppContext
import org.h2.tools.RunScript
import org.scalatest.{BeforeAndAfterEach, Suite}
import org.scalatra.servlet.ScalatraListener
import org.scalatra.test.HttpComponentsClient

object SureTestJetty extends App {
  new SureTestJetty(8080).start
}

object SureTestJettyWithMocks extends App {
  new SureTestJetty(8080, config = Config.mockConfig).start
}

object SharedTestJetty {
  private val config = Config.mockConfig
  private lazy val jetty = new SureTestJetty(config = config)

  private def start = {
    Timer.timed("Jetty start") {
      if (!jetty.server.isRunning) {
        RunScript.execute(config.h2DatabaseUrl, "", "", "classpath:clear-h2.sql", Charset.forName("UTF-8"), false)
      }
      jetty.start
    }
  }

  def restart:Unit = {
    if (jetty.server.isRunning) {
      Timer.timed("Jetty stop") {
        jetty.server.stop
      }
    }
    start
  }

  def port: Int = jetty.port
}

class SureTestJetty(val port: Int = PortChecker.findFreeLocalPort, config: Config = Config.globalConfig) {
  val root = ProjectRootFinder.findProjectRoot()
  val contextPath = "/"
  val server = new Server(port)
  val context = new WebAppContext()

  context.setAttribute("hakurekisteri.config", config)
  // load files from main/src/webapp and /src/test/resources/front-mock-files for autoloading changes. scripts.js is loaded from target/classes/webapp/compiled/scripts.js
  context.setBaseResource(
    new ResourceCollection(Array(root + "/src/main/resources/webapp", root + "/target/classes/webapp", root + "/src/test/resources/front-mock-files")))
  context.setContextPath(contextPath)
  context.setInitParameter(org.scalatra.EnvironmentKey, "production")
  context.setInitParameter(org.scalatra.CorsSupport.EnableKey, "false")

  server.setHandler(context)

  def start: Server = {
    server.start
    server
  }
}

trait CleanSharedTestJettyBeforeEach extends BeforeAndAfterEach with HttpComponentsClient {
  this: Suite =>
  val port = SharedTestJetty.port
  val baseUrl = s"http://localhost:$port"

  override def beforeEach(): Unit = {
    SharedTestJetty.restart
    super.beforeEach()
  }
}