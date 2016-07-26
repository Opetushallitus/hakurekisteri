package fi.vm.sade.hakurekisteri

import ch.qos.logback.access.jetty.RequestLogImpl
import fi.vm.sade.hakurekisteri.integration.OphUrlProperties
import fi.vm.sade.properties.OphProperties
import org.eclipse.jetty.server.{RequestLog, Server}
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.util.resource.Resource

object SureStandaloneJetty extends App {
  new SureStandaloneJetty().start
}

class SureStandaloneJetty(config: Config = Config.globalConfig) {
  private val suoritusrekisteriApp = new ServletContextHandler()
  suoritusrekisteriApp.setAttribute("hakurekisteri.config", config)
  suoritusrekisteriApp.setBaseResource(Resource.newClassPathResource("/webapp"))
  suoritusrekisteriApp.setContextPath("/")
  suoritusrekisteriApp.setInitParameter(org.scalatra.EnvironmentKey, "production")
  suoritusrekisteriApp.setInitParameter(org.scalatra.CorsSupport.EnableKey, "false")

  private val port: Int = OphUrlProperties.require("suoritusrekisteri.port").toInt
  private val server = new Server(port)
  server.setHandler(suoritusrekisteriApp)
  server.setRequestLog(requestLog(OphUrlProperties))

  private def requestLog(properties: OphProperties): RequestLog = {
    val requestLog = new RequestLogImpl
    requestLog.setFileName(properties.getOrElse("logback.access", "src/main/resources/logback-access.xml"))
    requestLog
  }

  def start: Server = {
    server.start
    server
  }
}
