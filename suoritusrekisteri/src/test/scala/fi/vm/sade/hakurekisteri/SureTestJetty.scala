package fi.vm.sade.hakurekisteri

import java.io.File

import fi.vm.sade.hakurekisteri.tools.ItPostgres
import fi.vm.sade.utils.Timer
import fi.vm.sade.utils.slf4j.Logging
import fi.vm.sade.utils.tcp.PortChecker
import org.apache.commons.lang3.StringUtils
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ContextHandlerCollection
import org.eclipse.jetty.util.resource.ResourceCollection
import org.eclipse.jetty.webapp.WebAppContext
import org.scalatest.{BeforeAndAfterEach, Suite}
import org.scalatra.test.HttpComponentsClient
import support.LocalDevProxyingServlet

object SureTestJetty extends App {
  new SureTestJetty(8080).start()
}

object SureTestJettyWithMocks extends App {
  ItPostgres.start()
  new SureTestJetty(8080, config = new MockConfig).start()
}

object SharedTestJetty extends Logging {
  logger.info("Object getting initialised...")
  System.setProperty("suoritusrekisteri.it.postgres.port", ItPostgres.port.toString)
  private val config = new MockConfig
  private lazy val jetty = new SureTestJetty(config = config)
  logger.info("...object initialised.")

  private def start() = {
    logger.info("Starting...")
    Timer.timed("Jetty start") {
      if (!jetty.server.isRunning) {
        logger.info("Not running, let's start ItPostgres...")
        ItPostgres.start()
        logger.info("...ItPostgres started.")
      }
      logger.info("Starting Jetty...")
      jetty.start()
      logger.info("...Jetty Started.")
    }
  }

  def restart(): Unit = {
    logger.info("Restarting...")
    if (jetty.server.isRunning) {
      Timer.timed("Jetty stop") {
        logger.info("restart() found was running, let's stop it...")
        jetty.server.stop()
        logger.info("...jetty.server.stop called by restart() returned, resetting ItPostgres...")
        ItPostgres.reset()
        logger.info("...ItPostgres.reset() called by restart() returned.")
      }
    }
    logger.info("restart() calling start()...")
    start()
    logger.info("...start() returned, restart() complete.")
  }

  def port: Int = jetty.port
}

class SureTestJetty(
  val port: Int = PortChecker.findFreeLocalPort,
  config: Config = Config.globalConfig
) {
  val root: File = ProjectRootFinder.findProjectRoot()

  val suoritusrekisteriApp = new WebAppContext()
  suoritusrekisteriApp.setAttribute("hakurekisteri.config", config)
  suoritusrekisteriApp.setBaseResource(
    new ResourceCollection(
      Array(
        root + "/suoritusrekisteri/src/main/resources/webapp",
        root + "/suoritusrekisteri/target/classes/webapp"
      )
    )
  )
  suoritusrekisteriApp.setContextPath("/suoritusrekisteri")
  suoritusrekisteriApp.setInitParameter(org.scalatra.EnvironmentKey, "production")
  suoritusrekisteriApp.setInitParameter(org.scalatra.CorsSupport.EnableKey, "false")

  val mockApp = new WebAppContext()
  mockApp.setAttribute("hakurekisteri.config", config)
  mockApp.setBaseResource(
    new ResourceCollection(Array(root + "/suoritusrekisteri/src/test/resources/front-mock-files"))
  )
  mockApp.setContextPath("/")
  mockApp.setInitParameter(
    org.scalatra.servlet.ScalatraListener.LifeCycleKey,
    "SuoritusrekisteriMocksBootstrap"
  )

  val devOnrPath: String = System.getProperty("sure.local.development.onr")
  if (StringUtils.isNotBlank(devOnrPath)) {
    LocalDevProxyingServlet.proxyRequests(mockApp, "/oppijanumerorekisteri-service/*", devOnrPath)
  }

  mockApp.setInitParameter(org.scalatra.EnvironmentKey, "production")
  mockApp.setInitParameter(org.scalatra.CorsSupport.EnableKey, "false")

  val server = new Server(port)
  val contexts = new ContextHandlerCollection()
  contexts.setHandlers(Array(suoritusrekisteriApp, mockApp))
  server.setHandler(contexts)

  def start(): Server = {
    server.start()
    server
  }
}

trait CleanSharedTestJettyBeforeEach extends BeforeAndAfterEach with HttpComponentsClient {
  this: Suite =>
  val port: Int = SharedTestJetty.port
  val baseUrl = s"http://localhost:$port"

  override def beforeEach(): Unit = {
    SharedTestJetty.restart()
    super.beforeEach()
  }
}
