package support

import fi.vm.sade.utils.slf4j.Logging
import javax.servlet.ServletConfig
import javax.servlet.http.HttpServletRequest
import org.apache.commons.collections4.EnumerationUtils
import org.eclipse.jetty.proxy.ProxyServlet
import org.eclipse.jetty.servlet.ServletHolder
import org.eclipse.jetty.webapp.WebAppContext

import scala.collection.JavaConverters._

class LocalDevProxyingServlet(proxyTarget: String) extends ProxyServlet.Transparent with Logging {
  override def init(config: ServletConfig): Unit = {
    val initParameters = EnumerationUtils
      .toList(config.getInitParameterNames)
      .asScala
      .map(name => s"$name == '${config.getInitParameter(name)}'")
      .mkString(", ")
    logger.info(s"Initialising for path $proxyTarget with config $initParameters")
    super.init(config)
  }

  override def rewriteTarget(clientRequest: HttpServletRequest): String = {
    logger.debug("Rewriting client request: " + clientRequest)
    super.rewriteTarget(clientRequest)
  }
}

object LocalDevProxyingServlet {
  def proxyRequests(mockApp: WebAppContext, proxySource: String, proxyTarget: String): Unit = {
    val proxyServletHolder = new ServletHolder(new LocalDevProxyingServlet(proxyTarget))
    proxyServletHolder.setInitParameter("proxyTo", proxyTarget)
    mockApp.addServlet(proxyServletHolder, proxySource)
  }
}
