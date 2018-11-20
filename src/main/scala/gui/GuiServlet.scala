package gui

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.json.JacksonJsonSupport


class GuiServlet(implicit val system: ActorSystem) extends HakuJaValintarekisteriStack with JacksonJsonSupport {
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  override protected implicit def jsonFormats: Formats = DefaultFormats

  lazy val oidit = GuiOidit(
    yotutkintoKomoOid = Oids.yotutkintoKomoOid,
    perusopetusKomoOid = Oids.perusopetusKomoOid,
    lisaopetusKomoOid = Oids.lisaopetusKomoOid,
    lisaopetusTalousKomoOid = Oids.lisaopetusTalousKomoOid,
    ammattistarttiKomoOid = Oids.ammattistarttiKomoOid,
    valmentavaKomoOid = Oids.valmentavaKomoOid,
    ammatilliseenvalmistavaKomoOid = Oids.ammatilliseenvalmistavaKomoOid,
    ulkomainenkorvaavaKomoOid = Oids.ulkomainenkorvaavaKomoOid,
    lukioKomoOid = Oids.lukioKomoOid,
    ammatillinenKomoOid = Oids.ammatillinenKomoOid,
    ammatillinentutkintoKomoOid = Oids.ammatillinentutkintoKomoOid,
    erikoisammattitutkintoKomoOid = Oids.erikoisammattitutkintoKomoOid,
    lukioonvalmistavaKomoOid = Oids.lukioonvalmistavaKomoOid,
    ylioppilastutkintolautakunta = Oids.ytlOrganisaatioOid,
    kansanopistoKomoOid = Oids.kansanopistoKomoOid,
    valmaKomoOid = Oids.valmaKomoOid,
    telmaKomoOid = Oids.telmaKomoOid,
    perusopetuksenOppiaineenOppimaaraOid = Oids.perusopetuksenOppiaineenOppimaaraOid
  )

  get("/") {
    contentType = "application/json"
    oidit
  }

}

case class GuiOidit(yotutkintoKomoOid: String,
                    perusopetusKomoOid: String,
                    lisaopetusKomoOid: String,
                    lisaopetusTalousKomoOid: String,
                    ammattistarttiKomoOid: String,
                    valmentavaKomoOid: String,
                    ammatilliseenvalmistavaKomoOid: String,
                    ulkomainenkorvaavaKomoOid: String,
                    lukioKomoOid: String,
                    ammatillinenKomoOid: String,
                    ammatillinentutkintoKomoOid: String,
                    erikoisammattitutkintoKomoOid: String,
                    lukioonvalmistavaKomoOid: String,
                    ylioppilastutkintolautakunta: String,
                    kansanopistoKomoOid: String,
                    valmaKomoOid: String,
                    telmaKomoOid: String,
                    perusopetuksenOppiaineenOppimaaraOid: String)
