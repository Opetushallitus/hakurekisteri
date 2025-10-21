package fi.vm.sade.hakurekisteri

import ch.qos.logback.access.jetty.RequestLogImpl
import fi.vm.sade.hakurekisteri.integration.OphUrlProperties
import fi.vm.sade.properties.OphProperties
import org.eclipse.jetty.ee10.webapp.WebAppContext
import org.eclipse.jetty.server.{Connector, RequestLog, Server, ServerConnector}
import org.eclipse.jetty.util.thread.{QueuedThreadPool, ThreadPool}

object SureStandaloneJetty extends App {
  new SureStandaloneJetty().start
}

class SureStandaloneJetty(config: Config = Config.globalConfig) {
  private val suoritusrekisteriApp = new WebAppContext()
  suoritusrekisteriApp.setAttribute("hakurekisteri.config", config)
  suoritusrekisteriApp.setBaseResource(
    suoritusrekisteriApp.getResourceFactory.newClassLoaderResource("/webapp")
  )
  suoritusrekisteriApp.setContextPath("/suoritusrekisteri")
  suoritusrekisteriApp.setInitParameter(org.scalatra.EnvironmentKey, "production")
  suoritusrekisteriApp.setInitParameter(org.scalatra.CorsSupport.EnableKey, "false")

  private val port: Int = OphUrlProperties.require("suoritusrekisteri.port").toInt

  val threadPool: ThreadPool = new QueuedThreadPool(200, 10, 60000)
  val server = new Server(threadPool)

  server.setHandler(suoritusrekisteriApp)
  server.setRequestLog(requestLog(OphUrlProperties))

  val serverConnector = new ServerConnector(server)
  serverConnector.setPort(port)
  server.setConnectors(Array[Connector](serverConnector))

  private def requestLog(properties: OphProperties): RequestLog = {
    val requestLog = new RequestLogImpl
    val logbackAccess = properties.getOrElse("logback.access", null)
    if (logbackAccess != null) {
      requestLog.setFileName(logbackAccess)
    } else {
      println(
        "SureStandaloneJetty: Jetty access log is printed to console, use -Dlogback.access to set configuration file"
      )
      requestLog.setResource("/logback-access.xml")
    }
    requestLog.start()
    requestLog
  }

  def start: Server = {
    println("SureStandaloneJetty: starting server at http://localhost:" + port)
    server.start
    server
  }
}
