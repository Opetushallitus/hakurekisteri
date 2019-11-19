package fi.vm.sade.hakurekisteri.integration.valintaperusteet

import akka.actor.ActorSystem
import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.Future

case class ValintatapajononTiedot(oid: String,
                                  tyyppi: Option[String]) {

  def tyyppiToReadable(): String = {
    tyyppi.getOrElse("") match {
      case "valintatapajono_av" => "Avoin väylä"
      case "valintatapajono_km" => "Kilpailumenestys"
      case "valintatapajono_kp" => "Koepisteet"
      case "valintatapajono_m" => "Muu"
      case "valintatapajono_tv" => "Todistusvalinta"
      case "valintatapajono_yp" => "Yhteispisteet"
      case _ => "tuntematon"
    }
  }
}

trait IValintaperusteetService {
  def getValintatapajonot(jonoOids: Set[String]): Future[Seq[ValintatapajononTiedot]]
}

class ValintaperusteetService(restClient: VirkailijaRestClient)(implicit val system: ActorSystem) extends IValintaperusteetService {
  private val logger = Logging.getLogger(system, this)

  override def getValintatapajonot(jonoOids: Set[String]): Future[Seq[ValintatapajononTiedot]] = {
    if (jonoOids.nonEmpty) {
      logger.info("Getting jonotietos from valintaperusteet for jonos: " + jonoOids)
      restClient.postObject[Set[String], Seq[ValintatapajononTiedot]]("valintaperusteet.valintatapajonosByOids")(200, jonoOids)
    } else {
      logger.info("Empty list of jonoOids provided for getValintatapajonot.")
      Future.successful(Seq.empty)
    }

  }
}

class ValintaperusteetServiceMock extends IValintaperusteetService {
  override def getValintatapajonot(jonoOids: Set[String]): Future[Seq[ValintatapajononTiedot]] = Future.successful(jonoOids.map(oid => ValintatapajononTiedot(oid, Some("valintatapajono_m"))).toSeq)
}
