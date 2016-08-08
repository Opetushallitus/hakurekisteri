package fi.vm.sade.hakurekisteri

import java.nio.charset.Charset

import fi.vm.sade.hakurekisteri.tools.ItPostgres
import fi.vm.sade.utils.Timer
import fi.vm.sade.utils.tcp.{PortChecker}
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ContextHandlerCollection
import org.eclipse.jetty.util.resource.ResourceCollection
import org.eclipse.jetty.webapp.WebAppContext
import org.h2.tools.RunScript
import org.scalatest.{BeforeAndAfterEach, Suite}
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
  private val itPostgres = new ItPostgres(config.postgresPortChooser)

  private def start = {
    Timer.timed("Jetty start") {
      if (!jetty.server.isRunning) {
        itPostgres.start()
      }
      jetty.start
    }
  }

  def restart:Unit = {
    if (jetty.server.isRunning) {
      Timer.timed("Jetty stop") {
        itPostgres.reset()
        jetty.server.stop
      }
    }
    start
  }

  def port: Int = jetty.port
}

class SureTestJetty(val port: Int = PortChecker.findFreeLocalPort, config: Config = Config.globalConfig) {
  val root = ProjectRootFinder.findProjectRoot()

  val suoritusrekisteriApp = new WebAppContext()
  suoritusrekisteriApp.setAttribute("hakurekisteri.config", config)
  suoritusrekisteriApp.setBaseResource(new ResourceCollection(Array(root + "/src/main/resources/webapp", root + "/target/classes/webapp")))
  suoritusrekisteriApp.setContextPath("/suoritusrekisteri")
  suoritusrekisteriApp.setInitParameter(org.scalatra.EnvironmentKey, "production")
  suoritusrekisteriApp.setInitParameter(org.scalatra.CorsSupport.EnableKey, "false")

  val mockApp = new WebAppContext()
  mockApp.setAttribute("hakurekisteri.config", config)
  mockApp.setBaseResource(new ResourceCollection(Array(root + "/src/test/resources/front-mock-files")))
  mockApp.setContextPath("/")
  mockApp.setInitParameter(org.scalatra.servlet.ScalatraListener.LifeCycleKey, "SuoritusrekisteriMocksBootstrap")
  mockApp.setInitParameter(org.scalatra.EnvironmentKey, "production")
  mockApp.setInitParameter(org.scalatra.CorsSupport.EnableKey, "false")

  val server = new Server(port)
  val contexts = new ContextHandlerCollection()
  contexts.setHandlers(Array(suoritusrekisteriApp, mockApp))
  server.setHandler(contexts)

  def start: Server = {
    server.start
    server
  }
}

trait CleanSharedTestJettyBeforeEach extends BeforeAndAfterEach with HttpComponentsClient {
  this: Suite =>
  val port = SharedTestJetty.port
  val baseUrl = s"http://localhost:$port"
  System.setProperty("valintatulos.it.postgres.port", "55432")

  override def beforeEach(): Unit = {
    SharedTestJetty.restart
    super.beforeEach()
  }
}