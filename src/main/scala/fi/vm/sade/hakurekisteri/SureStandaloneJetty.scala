package fi.vm.sade.hakurekisteri

import ch.qos.logback.access.jetty.RequestLogImpl
import fi.vm.sade.hakurekisteri.integration.OphUrlProperties
import fi.vm.sade.properties.OphProperties
import org.eclipse.jetty.server.{RequestLog, Server}
import org.eclipse.jetty.util.resource.Resource
import org.eclipse.jetty.webapp.WebAppContext

object SureStandaloneJetty extends App {
  new SureStandaloneJetty().start
}

class SureStandaloneJetty(config: Config = Config.globalConfig) {
  private val suoritusrekisteriApp = new WebAppContext()
  suoritusrekisteriApp.setAttribute("hakurekisteri.config", config)
  suoritusrekisteriApp.setBaseResource(Resource.newClassPathResource("/webapp"))
  suoritusrekisteriApp.setContextPath("/suoritusrekisteri")
  suoritusrekisteriApp.setInitParameter(org.scalatra.EnvironmentKey, "production")
  suoritusrekisteriApp.setInitParameter(org.scalatra.CorsSupport.EnableKey, "false")

  private val port: Int = OphUrlProperties.require("suoritusrekisteri.port").toInt
  private val server = new Server(port)
  server.setHandler(suoritusrekisteriApp)
  server.setRequestLog(requestLog(OphUrlProperties))

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
