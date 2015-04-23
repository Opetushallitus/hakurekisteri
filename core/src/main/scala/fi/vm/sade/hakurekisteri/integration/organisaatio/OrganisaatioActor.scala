package fi.vm.sade.hakurekisteri.integration.organisaatio

import java.net.URLEncoder

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.{FutureCache, PreconditionFailedException, VirkailijaRestClient}
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

abstract class OrganisaatioActor(config: Config) extends Actor with ActorLogging {

  implicit val executionContext: ExecutionContext = context.dispatcher

  val maxRetries = config.integrations.organisaatioConfig.httpClientMaxRetries
  val timeToLive = config.integrations.organisaatioCacheHours.hours

  object Refresh

  val refresh = context.system.scheduler.schedule(timeToLive.minus(15.minutes), timeToLive.minus(15.minutes), self, Refresh)

  private val cache: FutureCache[String, Organisaatio] = new FutureCache[String, Organisaatio](timeToLive.toMillis)
  private var oppilaitoskoodiIndex: Map[String, String] = Map()

  def fetchAll()

  def find(oid: String): Future[Option[Organisaatio]] = {
    if (cache.contains(oid)) cache.get(oid).map(Some(_))
    else findDirect(oid)
  }

  def findDirect(tunniste: String): Future[Option[Organisaatio]]

def saveOrganisaatiot(s: Seq[Organisaatio]): Unit = {
    s.foreach(org => {
      cache +(org.oid, Future.successful(org))
      if (org.oppilaitosKoodi.isDefined) oppilaitoskoodiIndex = oppilaitoskoodiIndex + (org.oppilaitosKoodi.get -> org.oid)
      if (org.children.nonEmpty) saveOrganisaatiot(org.children)
    })
  }

  override def preStart(): Unit = {
    fetchAll()
  }

  override def postStop(): Unit = {
    refresh.cancel()
  }

  def findByOppilaitoskoodi(koodi: String): Future[Option[Organisaatio]] = {
    oppilaitoskoodiIndex.get(koodi) match {
      case Some(oid) => find(oid)
      case None => findDirect(koodi)
    }
  }

  override def receive: Receive = {
    case Refresh => fetchAll()

    case s: OrganisaatioResponse =>
      saveOrganisaatiot(s.organisaatiot)
      log.info(s"all saved to cache: ${cache.size}")

    case Failure(t: OrganisaatioFetchFailedException) =>
      log.error("organisaatio refresh failed, retrying in 1 minute", t.t)
      context.system.scheduler.scheduleOnce(1.minute, self, Refresh)

    case Failure(t: Throwable) =>
      log.error("error in organisaatio actor", t)

    case oid: String => find(oid) pipeTo sender

    case Oppilaitos(koodi) =>
      findByOppilaitoskoodi(koodi).flatMap {
        case Some(oppilaitos) => Future.successful(OppilaitosResponse(koodi, oppilaitos))
        case None => Future.failed(OppilaitosNotFoundException(koodi))
      } pipeTo sender
  }

}

class OrganisaatioHttpActor(organisaatioClient: VirkailijaRestClient, config: Config) extends OrganisaatioActor(config) {

  override def fetchAll(): Unit = {
    val f = organisaatioClient.readObject[OrganisaatioResponse](s"/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true", 200).recover {
      case t: Throwable => OrganisaatioFetchFailedException(t)
    }
    f pipeTo self
  }

  def findDirect(tunniste: String): Future[Option[Organisaatio]] = {
    organisaatioClient.readObject[Organisaatio](s"/rest/organisaatio/${URLEncoder.encode(tunniste, "UTF-8")}", 200, maxRetries).map(Option(_)).recoverWith {
      case p: PreconditionFailedException if p.responseCode == 204 => log.warning(s"organisaatio not found with tunniste $tunniste"); Future.successful(None)
    }
  }
}

class MockOrganisaatio(config: Config) extends OrganisaatioActor(config) {
  implicit val formats = DefaultFormats

  override def fetchAll(): Unit = {
    val json = parse("""{
                         "numHits": 1,
                         "organisaatiot": [
                           {
                             "oid": "1.2.246.562.10.39644336305",
                             "alkuPvm": 694216800000,
                             "parentOid": "1.2.246.562.10.80381044462",
                             "parentOidPath": "1.2.246.562.10.39644336305/1.2.246.562.10.80381044462/1.2.246.562.10.00000000001",
                             "oppilaitosKoodi": "06345",
                             "oppilaitostyyppi": "oppilaitostyyppi_11#1",
                             "match": true,
                             "nimi": {
                               "fi": "Pikkaralan ala-aste"
                             },
                             "kieletUris": [
                               "oppilaitoksenopetuskieli_1#1"
                             ],
                             "kotipaikkaUri": "kunta_564",
                             "children": [],
                             "organisaatiotyypit": [
                               "OPPILAITOS"
                             ],
                             "aliOrganisaatioMaara": 0
                           }
                         ]
                       }""")
    json.extract[OrganisaatioResponse]
  }

  override def findDirect(tunniste: String): Future[Option[Organisaatio]] = {
    val mockKoulu = parse("""{"oid": "1.2.246.562.10.39644336305",
                      "nimi": {"fi": "Pikkaralan ala-aste"},
                      "alkuPvm": "1992-01-01",
                      "postiosoite": {
                      "osoiteTyyppi": "posti",
                      "yhteystietoOid": "1.2.246.562.5.75344290822",
                      "postinumeroUri": "posti_90310",
                      "osoite": "Vasantie 121",
                      "postitoimipaikka": "OULU",
                      "ytjPaivitysPvm": null,
                      "lng": null,
                      "lap": null,
                      "coordinateType": null,
                      "osavaltio": null,
                      "extraRivi": null,
                      "maaUri": null
                      },
                      "parentOid": "1.2.246.562.10.80381044462",
                      "parentOidPath": "|1.2.246.562.10.00000000001|1.2.246.562.10.80381044462|",
                      "vuosiluokat": [],
                      "oppilaitosKoodi": "06345",
                      "kieletUris": ["oppilaitoksenopetuskieli_1#1"],
                      "oppilaitosTyyppiUri": "oppilaitostyyppi_11#1",
                      "yhteystiedot": [{
                      "kieli": "kieli_fi#1",
                      "id": "22913",
                      "yhteystietoOid": "1.2.246.562.5.11296174961",
                      "email": "kaisa.tahtinen@ouka.fi"
                      }, {
                      "tyyppi": "faksi",
                      "kieli": "kieli_fi#1",
                      "id": "22914",
                      "yhteystietoOid": "1.2.246.562.5.18105745956",
                      "numero": "08  5586 1582"
                      }, {
                      "tyyppi": "puhelin",
                      "kieli": "kieli_fi#1",
                      "id": "22915",
                      "yhteystietoOid": "1.2.246.562.5.364178776310",
                      "numero": "08  5586 9514"
                      }, {
                      "kieli": "kieli_fi#1",
                      "id": "22916",
                      "yhteystietoOid": "1.2.246.562.5.94533742915",
                      "www": "http://www.edu.ouka.fi/koulut/pikkarala"
                      }, {
                      "osoiteTyyppi": "posti",
                      "kieli": "kieli_fi#1",
                      "id": "22917",
                      "yhteystietoOid": "1.2.246.562.5.75344290822",
                      "osoite": "Vasantie 121",
                      "postinumeroUri": "posti_90310",
                      "postitoimipaikka": "OULU",
                      "ytjPaivitysPvm": null,
                      "coordinateType": null,
                      "lap": null,
                      "lng": null,
                      "osavaltio": null,
                      "extraRivi": null,
                      "maaUri": null
                      }, {
                      "osoiteTyyppi": "kaynti",
                      "kieli": "kieli_fi#1",
                      "id": "22918",
                      "yhteystietoOid": "1.2.246.562.5.58988409759",
                      "osoite": "Vasantie 121",
                      "postinumeroUri": "posti_90310",
                      "postitoimipaikka": "OULU",
                      "ytjPaivitysPvm": null,
                      "coordinateType": null,
                      "lap": null,
                      "lng": null,
                      "osavaltio": null,
                      "extraRivi": null,
                      "maaUri": null
                      }],
                      "kuvaus2": {},
                      "tyypit": ["Oppilaitos"],
                      "yhteystietoArvos": [],
                      "nimet": [{"nimi": {"fi": "Pikkaralan ala-aste"}, "alkuPvm": "1992-01-01", "version": 1}],
                      "ryhmatyypit": [],
                      "kayttoryhmat": [],
                      "kayntiosoite": {
                      "osoiteTyyppi": "kaynti",
                      "yhteystietoOid": "1.2.246.562.5.58988409759",
                      "postinumeroUri": "posti_90310",
                      "osoite": "Vasantie 121",
                      "postitoimipaikka": "OULU",
                      "ytjPaivitysPvm": null,
                      "lng": null,
                      "lap": null,
                      "coordinateType": null,
                      "osavaltio": null,
                      "extraRivi": null,
                      "maaUri": null
                      },
                      "kotipaikkaUri": "kunta_564",
                      "maaUri": "maatjavaltiot1_fin",
                      "version": 1,
                      "status": "AKTIIVINEN"
                      }""")

    Future.successful(Some(mockKoulu.extract[Organisaatio]))
  }

}

case class OrganisaatioResponse(numHits: Option[Int], organisaatiot: Seq[Organisaatio])

case class Oppilaitos(koodi: String)

case class OppilaitosResponse(koodi: String, oppilaitos: Organisaatio)

case class OppilaitosNotFoundException(koodi: String) extends Exception(s"Oppilaitosta ei löytynyt oppilaitoskoodilla $koodi.")

case class OrganisaatioFetchFailedException(t: Throwable) extends Exception(t)
