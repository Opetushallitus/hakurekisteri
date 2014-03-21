package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.hakurekisteri.hakija.Hakuehto.Hakuehto
import fi.vm.sade.hakurekisteri.hakija.Tyyppi.Tyyppi
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine, SwaggerSupport}
import org.scalatra.{AsyncResult, CorsSupport, FutureSupport}
import scala.concurrent.{Await, Future, ExecutionContext}
import akka.actor.{Actor, ActorSystem}
import _root_.akka.actor.{Actor, ActorRef, ActorSystem}
import _root_.akka.pattern.ask
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import scala.collection.immutable.IndexedSeq
import scala.concurrent.duration.Duration

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
      val q = HakijaQuery(
        params.get("haku"),
        params.get("organisaatio"),
        params.get("hakukohdekoodi"),
        Hakuehto withName params("hakuehto"),
        Tyyppi withName params("tyyppi"))

      logger.info("Query: " + q)

      new AsyncResult() {
        val is = hakijaActor ? q
      }
    }
  }

}

import akka.pattern.pipe

class HakijaActor(hakupalvelu: Hakupalvelu, organisaatiopalvelu: Organisaatiopalvelu) extends Actor {

  implicit val executionContext: ExecutionContext = context.dispatcher

  def receive = {
    case q: HakijaQuery => {
      def getFullHakemus(sh:SmallHakemus): Future[Option[Hakija]] = getHakemus(sh.oid).flatMap(constructHakija(_))
      val hakijatFuture: Future[Hakijat] = findHakemukset(q).flatMap(Future.traverse(_)(getFullHakemus)).map((s: Seq[Option[Hakija]]) => s.flatten).map(Hakijat(_))
      hakijatFuture pipeTo sender

    }
  }



  def findHakemukset = hakupalvelu.find(_)

  def getHakemus = hakupalvelu.get(_)


  def hakemus2Hakija(hakemus:FullHakemus):Future[Hakija]  = {
    val henkilotiedot = hakemus.answers.henkilotiedot
    toHakemus(hakemus).map(h => Hakija(henkilotiedot.Henkilotunnus, hakemus.personOid, henkilotiedot.Sukunimi, henkilotiedot.Etunimet, Option(henkilotiedot.Kutsumanimi).filter(_.trim.nonEmpty),
      henkilotiedot.lahiosoite, henkilotiedot.Postinumero, henkilotiedot.asuinmaa, henkilotiedot.kansalaisuus, Option(henkilotiedot.matkapuhelinnumero1).filter(_.trim.nonEmpty),
      None, Option(henkilotiedot.Sähköposti).filter(_.trim.nonEmpty), Option(henkilotiedot.kotikunta).filter(_.trim.nonEmpty),
      henkilotiedot.sukupuoli, henkilotiedot.aidinkieli, hakemus.answers.lisatiedot.lupaMarkkinointi, h))

  }

  def constructHakija(oh: Option[FullHakemus]): Future[Option[Hakija]] = {
    Future.sequence(oh.map(hakemus2Hakija))
  }

  def toHakemus(fullHakemus: FullHakemus): Future[Hakemus] = {
    val kt = fullHakemus.answers.koulutustausta
    toHakutoiveet(fullHakemus).map(ht => Hakemus(2014, "K", fullHakemus.oid, kt.lahtokoulu, None, kt.lahtoluokka, kt.luokkataso, kt.POHJAKOULUTUS, Option(kt.PK_PAATTOTODISTUSVUOSI.toShort),
      fullHakemus.answers.lisatiedot.lupaJulkaisu, None, None, None, None, None, ht))

  }

  def toHakutoiveet(fullHakemus: FullHakemus): Future[Seq[Hakutoive]] = {
    val ht = fullHakemus.answers.hakutoiveet
    val Pattern = "preference(\\d+)-Opetuspiste-id".r
    val notEmpty = "(.+)".r
    val opetusPisteet: Map[Short,String] = ht.collect {
        case (Pattern(n), notEmpty(opetusPisteId)) => (n.toShort, opetusPisteId)
      }
    Future.sequence(opetusPisteet.collect {
      case (v, opetusPisteId) => organisaatiopalvelu.get(opetusPisteId).map((o) => toHakutoive("",o.map(_.toimipistekoodi), o.flatMap(_.nimi.get("fi")), ht.get("preference" + v + "-Koulutus-id-aoIdentifier"), v))
    }).map(_.toSeq)
  }

  def toHakutoive(oppilaitos: String, opetuspiste: Option[String], opetuspisteennimi: Option[String], hakukohdekoodi: Option[String], jno: Short): Hakutoive = {
    Hakutoive(jno, oppilaitos, opetuspiste, opetuspisteennimi, hakukohdekoodi.get, None, None, None, None, None, None, None, None, None)
  }
}



case class Hakutoive(hakujno: Short, oppilaitos: String, opetuspiste: Option[String], opetuspisteennimi: Option[String], koulutus: String,
                     harkinnanvaraisuusperuste: Option[String], urheilijanammatillinenkoulutus: Option[String], yhteispisteet: Option[BigDecimal],
                     valinta: Option[String], vastaanotto: Option[String], lasnaolo: Option[String], terveys: Option[String], aiempiperuminen: Option[Boolean],
                     kaksoistutkinto: Option[Boolean])

case class Hakemus(vuosi: Short, kausi: String, hakemusnumero: String, lahtokoulu: Option[String], lahtokoulunnimi: Option[String], luokka: Option[String],
                   luokkataso: String, pohjakoulutus: String, todistusvuosi: Option[Short], julkaisulupa: Option[Boolean], yhteisetaineet: Option[BigDecimal],
                   lukiontasapisteet: Option[BigDecimal], lisapistekoulutus: Option[String], yleinenkoulumenestys: Option[BigDecimal],
                   painotettavataineet: Option[BigDecimal], hakutoiveet: Seq[Hakutoive])

case class Hakija(hetu: String, oppijanumero: String, sukunimi: String, etunimet: String, kutsumanimi: Option[String], lahiosoite: String,
                  postinumero: String, maa: String, kansalaisuus: String, matkapuhelin: Option[String], muupuhelin: Option[String], sahkoposti: Option[String],
                  kotikunta: Option[String], sukupuoli: String, aidinkieli: String, koulutusmarkkinointilupa: Boolean, hakemus: Hakemus)

case class Hakijat(hakijat: Seq[Hakija])

