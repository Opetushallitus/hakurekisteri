package gui

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.{Oids, Config}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.json.JacksonJsonSupport


class GuiServlet(oids: Oids)(implicit val system: ActorSystem) extends HakuJaValintarekisteriStack with JacksonJsonSupport {
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  override protected implicit def jsonFormats: Formats = DefaultFormats

  lazy val oidit = GuiOidit(
    yotutkintoKomoOid = oids.yotutkintoKomoOid,
    perusopetusKomoOid = oids.perusopetusKomoOid,
    lisaopetusKomoOid = oids.lisaopetusKomoOid,
    ammattistarttiKomoOid = oids.ammattistarttiKomoOid,
    valmentavaKomoOid = oids.valmentavaKomoOid,
    ammatilliseenvalmistavaKomoOid = oids.ammatilliseenvalmistavaKomoOid,
    ulkomainenkorvaavaKomoOid = oids.ulkomainenkorvaavaKomoOid,
    lukioKomoOid = oids.lukioKomoOid,
    ammatillinenKomoOid = oids.ammatillinenKomoOid,
    lukioonvalmistavaKomoOid = oids.lukioonvalmistavaKomoOid,
    ylioppilastutkintolautakunta = oids.ytlOrganisaatioOid
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
