package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.hakurekisteri.hakija.Hakuehto.Hakuehto
import fi.vm.sade.hakurekisteri.hakija.Tyyppi.Tyyppi
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine, SwaggerSupport}
import org.scalatra.{AsyncResult, CorsSupport, FutureSupport}
import scala.concurrent.ExecutionContext
import akka.actor.{Actor, ActorSystem}
import _root_.akka.actor.{Actor, ActorRef, ActorSystem}
import _root_.akka.pattern.ask
import akka.util.Timeout
import java.util.concurrent.TimeUnit

class HakijaResource(hakijaActor: ActorRef)(implicit system: ActorSystem, sw: Swagger) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with SwaggerSupport with FutureSupport with CorsSupport {
  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout = Timeout(60, TimeUnit.SECONDS)
  override protected def applicationDescription: String = "Hakeneiden ja valittujen rajapinta."
  override protected implicit def swagger: SwaggerEngine[_] = sw

  before() {
    contentType = formats("json")
  }

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  get("/") {
    new AsyncResult() {
      // TODO lisää HakijaQuery tähän
      val is = hakijaActor ? Hakuehto.Kaikki
    }
  }

}

class HakijaActor(implicit system: ActorSystem) extends Actor {
  def receive = {
    // TODO toimintalogiikka HakijaQueryn perusteella
    case Hakuehto.Kaikki => {
      sender ! Hakijat(Seq())
    }
  }
}



object Hakuehto extends Enumeration {
  type Hakuehto = Value
  val Kaikki, Hyväksytyt, Vastaanottaneet = Value
}

// TODO tyyppimuunnin, joka muuntaa oletusmuodon (JSON) XML- tai Excel-muotoon
object Tyyppi extends Enumeration {
  type Tyyppi = Value
  val Xml, Excel, Json = Value
}

case class HakijaQuery(haku: String, organisaatio: String, hakukohdekoodi: String, hakuehto: Hakuehto, tyyppi: Tyyppi)



case class Hakutoive(hakujno: Short, oppilaitos: String, opetuspiste: String, opetuspisteennimi: String, koulutus: String,
                     harkinnanvaraisuusperuste: String, urheilijanammatillinenkoulutus: String, yhteispisteet: BigDecimal,
                     valinta: String, vastaanotto: String, lasnaolo: String, terveys: String, aiempiperuminen: Boolean,
                     kaksoistutkinto: Boolean)

case class Hakemus(vuosi: Short, kausi: String, hakemusnumero: String, lahtokoulu: String, lahtokoulunnimi: String, luokka: String,
                   luokkataso: String, pohjakoulutus: String, todistusvuosi: Short, julkaisulupa: Boolean, yhteisetaineet: BigDecimal,
                   lukiontasapisteet: BigDecimal, lisapistekoulutus: String, yleinenkoulumenestys: BigDecimal,
                   painotettavataineet: BigDecimal, hakutoiveet: Seq[Hakutoive])

case class Hakija(hetu: String, oppijanumero: String, sukunimi: String, etunimet: String, kutsumanimi: String, lahiosoite: String,
                  postinumero: String, maa: String, kansalaisuus: String, matkapuhelin: String, muupuhelin: String, sahkoposti: String,
                  kotikunta: String, sukupuoli: String, aidinkieli: String, koulutusmarkkinointilupa: String, hakemus: Hakemus)

case class Hakijat(hakijat: Seq[Hakija])