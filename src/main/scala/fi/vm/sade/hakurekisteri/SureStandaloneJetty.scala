package fi.vm.sade.hakurekisteri

import fi.vm.sade.hakurekisteri.integration.OphUrlProperties
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.util.resource.Resource
import org.eclipse.jetty.webapp.WebAppContext

object SureStandaloneJetty extends App {
  new SureStandaloneJetty().start
}

class SureStandaloneJetty(config: Config = Config.globalConfig) {
  private val suoritusrekisteriApp = new WebAppContext()
  suoritusrekisteriApp.setAttribute("hakurekisteri.config", config)
  suoritusrekisteriApp.setBaseResource(Resource.newClassPathResource("/webapp"))
  suoritusrekisteriApp.setContextPath("/")
  suoritusrekisteriApp.setInitParameter(org.scalatra.EnvironmentKey, "production")
  suoritusrekisteriApp.setInitParameter(org.scalatra.CorsSupport.EnableKey, "false")

  private val port: Int = OphUrlProperties.ophProperties.require("suoritusrekisteri.port").toInt
  private val server = new Server(port)
  server.setHandler(suoritusrekisteriApp)

  def start: Server = {
    server.start
    server
  }
}
