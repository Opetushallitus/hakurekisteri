package gui

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.json4s.{DefaultFormats, Formats}
import org.json4s.JsonAST.JValue
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.scalate.ScalateSupport
import org.fusesource.scalate.layout.DefaultLayoutStrategy
import org.fusesource.scalate.TemplateEngine
import javax.servlet.http.HttpServletRequest
import scala.collection.mutable
import org.fusesource.scalate.util.{StringResource, Resource, ResourceLoader}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.Config


class GuiServlet()(implicit val system: ActorSystem) extends HakuJaValintarekisteriStack with JacksonJsonSupport {
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  lazy val oidit = GuiOidit(
    yotutkintoKomoOid = Config.yotutkintoKomoOid,
    perusopetusKomoOid = Config.perusopetusKomoOid,
    lisaopetusKomoOid = Config.lisaopetusKomoOid,
    ammattistarttiKomoOid = Config.ammattistarttiKomoOid,
    valmentavaKomoOid = Config.valmentavaKomoOid,
    ammatilliseenvalmistavaKomoOid = Config.ammatilliseenvalmistavaKomoOid,
    ulkomainenkorvaavaKomoOid = Config.ulkomainenkorvaavaKomoOid,
    lukioKomoOid = Config.lukioKomoOid,
    ammatillinenKomoOid = Config.ammatillinenKomoOid,
    lukioonvalmistavaKomoOid = Config.lukioonvalmistavaKomoOid,
    ylioppilastutkintolautakunta = Config.ytlOrganisaatioOid
  )

  get("/rest/v1/komo") {
    contentType="application/json"
    oidit
  }

  get("/") {
    new java.io.File(servletContext.getResource("/index.html").getFile)
  }

  override protected implicit def jsonFormats: Formats = DefaultFormats
}

case class GuiOidit(yotutkintoKomoOid: String,
                    perusopetusKomoOid: String,
                    lisaopetusKomoOid: String,
                    ammattistarttiKomoOid: String,
                    valmentavaKomoOid: String,
                    ammatilliseenvalmistavaKomoOid: String,
                    ulkomainenkorvaavaKomoOid: String,
                    lukioKomoOid: String,
                    ammatillinenKomoOid: String,
                    lukioonvalmistavaKomoOid: String,
                    ylioppilastutkintolautakunta: String)
