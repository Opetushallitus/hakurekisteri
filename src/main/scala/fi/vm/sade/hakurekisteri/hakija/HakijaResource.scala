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

object Hakuehto extends Enumeration {
  type Hakuehto = Value
  val Kaikki, Hyväksytyt, Vastaanottaneet = Value
}

// TODO tyyppimuunnin, joka muuntaa oletusmuodon (JSON) XML- tai Excel-muotoon
object Tyyppi extends Enumeration {
  type Tyyppi = Value
  val Xml, Excel, Json = Value
}

case class HakijaQuery(haku: Option[String], organisaatio: Option[String], hakukohdekoodi: Option[String], hakuehto: Hakuehto, tyyppi: Tyyppi)


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
    if (params("hakuehto") == null || params("tyyppi") == null)
      response.sendError(400)
    else {
      new AsyncResult() {
        val is = hakijaActor ? HakijaQuery(
          params.get("haku"),
          params.get("organisaatio"),
          params.get("hakukohdekoodi"),
          Hakuehto withName params("hakuehto"),
          Tyyppi withName params("tyyppi")
        )
      }
    }
  }

}

class HakijaActor(hakupalvelu: Hakupalvelu, henkilopalvelu: Henkilopalvelu)(implicit system: ActorSystem) extends Actor {
  def receive = {
    case q: HakijaQuery => {
      sender ! Hakijat(hakijat = findHakemukset(q).map((hakemus: Hakemus) => {
        findHenkilo(hakemus.personOid).map(_.toHakija(hakemus))
      }).flatten)
    }
  }

  def findHakemukset(q: HakijaQuery) = {
    hakupalvelu.find(q)
  }

  def findHenkilo(personOid: String) = {
    henkilopalvelu.find(personOid)
  }
}



case class Hakutoive(hakujno: Short, oppilaitos: String, opetuspiste: Option[String], opetuspisteennimi: Option[String], koulutus: String,
                     harkinnanvaraisuusperuste: Option[String], urheilijanammatillinenkoulutus: Option[String], yhteispisteet: Option[BigDecimal],
                     valinta: Option[String], vastaanotto: Option[String], lasnaolo: Option[String], terveys: Option[String], aiempiperuminen: Option[Boolean],
                     kaksoistutkinto: Option[Boolean])

case class Hakemus(vuosi: Short, kausi: String, hakemusnumero: String, lahtokoulu: Option[String], lahtokoulunnimi: Option[String], luokka: Option[String],
                   luokkataso: String, pohjakoulutus: String, todistusvuosi: Option[Short], julkaisulupa: Option[Boolean], yhteisetaineet: Option[BigDecimal],
                   lukiontasapisteet: Option[BigDecimal], lisapistekoulutus: Option[String], yleinenkoulumenestys: Option[BigDecimal],
                   painotettavataineet: Option[BigDecimal], hakutoiveet: Seq[Hakutoive], personOid: String)

class Henkilo(hetu: String, oppijanumero: String, sukunimi: String, etunimet: String, kutsumanimi: Option[String], lahiosoite: String,
                  postinumero: String, maa: String, kansalaisuus: String, matkapuhelin: Option[String], muupuhelin: Option[String], sahkoposti: Option[String],
                  kotikunta: Option[String], sukupuoli: String, aidinkieli: String, koulutusmarkkinointilupa: Boolean) {
  def toHakija(hakemus: Hakemus) = {
    Hakija(hetu, oppijanumero, sukunimi, etunimet, kutsumanimi, lahiosoite, postinumero, maa, kansalaisuus, matkapuhelin, muupuhelin, sahkoposti,
      kotikunta, sukupuoli, aidinkieli, koulutusmarkkinointilupa, hakemus)
  }
}

case class Hakija(hetu: String, oppijanumero: String, sukunimi: String, etunimet: String, kutsumanimi: Option[String], lahiosoite: String,
                  postinumero: String, maa: String, kansalaisuus: String, matkapuhelin: Option[String], muupuhelin: Option[String], sahkoposti: Option[String],
                  kotikunta: Option[String], sukupuoli: String, aidinkieli: String, koulutusmarkkinointilupa: Boolean, hakemus: Hakemus) extends Henkilo(hetu,
  oppijanumero, sukunimi, etunimet, kutsumanimi, lahiosoite, postinumero, maa, kansalaisuus, matkapuhelin, muupuhelin, sahkoposti, kotikunta, sukupuoli, aidinkieli,
  koulutusmarkkinointilupa)

case class Hakijat(hakijat: Seq[Hakija])