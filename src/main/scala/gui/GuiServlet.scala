package gui

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import org.scalatra.scalate.ScalateSupport
import org.fusesource.scalate.layout.DefaultLayoutStrategy
import org.fusesource.scalate.TemplateEngine
import javax.servlet.http.HttpServletRequest
import scala.collection.mutable
import org.fusesource.scalate.util.{StringResource, Resource, ResourceLoader}


class GuiServlet()(implicit val system: ActorSystem) extends HakuJaValintarekisteriStack with ScalateSupport {
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  /* wire up the precompiled templates */
  override protected def defaultTemplatePath: List[String] = List("/WEB-INF/templates/views")

  override protected def createTemplateEngine(config: ConfigT) = {
    val engine = super.createTemplateEngine(config)
    engine.layoutStrategy = new DefaultLayoutStrategy(engine,
      TemplateEngine.templateTypes.map("/WEB-INF/templates/layouts/default." + _): _*)
    engine.packagePrefix = "templates"
    val loader = engine.resourceLoader
    engine.resourceLoader = new ResourceLoader {
      def resource(uri: String): Option[Resource] = uri match {
        case "/index.jade" => Some(new StringResource(uri, ""))
        case default => loader.resource(uri)
      }
    }
    engine
  }

  /* end wiring up the precompiled templates */

  override protected def templateAttributes(implicit request: HttpServletRequest): mutable.Map[String, Any] = {
    super.templateAttributes ++ mutable.Map.empty // Add extra attributes here, they need bindings in the build file
  }

  get("/") {
    contentType="text/html"
    jade("/index.jade")

  }

  get("/templates/:template") {
    contentType="text/html"
    val attributes = (Map("layout" -> "") ++ params).toSeq
    try jade("/" + params("template"), attributes:_*)
    catch {
      case te: org.fusesource.scalate.TemplateException  => pass()
      case nf: org.fusesource.scalate.util.ResourceNotFoundException => pass()
    }
  }

  notFound {
    logger.warning("location not found, resolving template")
    // remove content type in case it was set through an action
    contentType = null
    // Try to render a ScalateTemplate if no route matched
    findTemplate(requestPath) map {
      path =>
        logger.warning("finding template")
        contentType = "text/html"
        layoutTemplate(path)
    } orElse serveStaticResource() getOrElse resourceNotFound()
  }
}
