package gui

import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import org.scalatra.scalate.ScalateSupport
import org.fusesource.scalate.layout.DefaultLayoutStrategy
import org.fusesource.scalate.TemplateEngine
import javax.servlet.http.HttpServletRequest
import scala.collection.mutable


class GuiServlet extends HakuJaValintarekisteriStack with ScalateSupport {
  /* wire up the precompiled templates */
  override protected def defaultTemplatePath: List[String] = List("/WEB-INF/templates/views")

  override protected def createTemplateEngine(config: ConfigT) = {
    val engine = super.createTemplateEngine(config)
    engine.layoutStrategy = new DefaultLayoutStrategy(engine,
      TemplateEngine.templateTypes.map("/WEB-INF/templates/layouts/default." + _): _*)
    engine.packagePrefix = "templates"
    engine
  }

  /* end wiring up the precompiled templates */

  override protected def templateAttributes(implicit request: HttpServletRequest): mutable.Map[String, Any] = {
    super.templateAttributes ++ mutable.Map.empty // Add extra attributes here, they need bindings in the build file
  }

  get("/") {
    contentType="text/html"
    jade("/index")
  }

  get("/templates/muokkaa") {
    contentType="text/html"
    jade("/muokkaa", "layout" -> "")
  }

  get("/templates/suoritukset") {
    contentType="text/html"
    jade("/suoritukset", "layout" -> "")
  }

  notFound {
    logger.warn("location not found, resolving template")
    // remove content type in case it was set through an action
    contentType = null
    // Try to render a ScalateTemplate if no route matched
    findTemplate(requestPath) map {
      path =>
        logger.warn("finding template")
        contentType = "text/html"
        layoutTemplate(path)
    } orElse serveStaticResource() getOrElse resourceNotFound()
  }
}
