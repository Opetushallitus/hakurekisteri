package gui

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.json.JacksonJsonSupport


class GuiServlet()(implicit val system: ActorSystem) extends HakuJaValintarekisteriStack with JacksonJsonSupport {
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  override protected implicit def jsonFormats: Formats = DefaultFormats

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

  get("/") {
    contentType = "application/json"
    oidit
  }

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
