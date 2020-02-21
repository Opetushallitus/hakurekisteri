package fi.vm.sade.hakurekisteri.integration.valintaperusteet

import akka.actor.ActorSystem
import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future

case class ValintatapajononTiedot(oid: String, tyyppi: Option[String])

trait IValintaperusteetService {
  def getValintatapajonot(jonoOids: Set[String]): Future[Seq[ValintatapajononTiedot]]
}

class ValintaperusteetService(restClient: VirkailijaRestClient)(implicit val system: ActorSystem) extends IValintaperusteetService {

  private val logger = Logging.getLogger(system, this)

  private val MAX_VALINTATAPAJONOT_BATCH_SIZE = 5000

  override def getValintatapajonot(jonoOids: Set[String]): Future[Seq[ValintatapajononTiedot]] = {
    if (jonoOids.nonEmpty) {
      val batches: Seq[Set[String]] = jonoOids.grouped(MAX_VALINTATAPAJONOT_BATCH_SIZE).toSeq
      logger.info(s"Getting jonotietos from valintaperusteet for jonos: $jonoOids in ${batches.size} batches")
      Future.sequence(batches.map(oidBatch => restClient.postObject[Set[String], Seq[ValintatapajononTiedot]]("valintaperusteet.valintatapajonosByOids")(200, oidBatch)))
        .map(_.flatten.toSeq)
    } else {
      logger.info("Empty list of jonoOids provided for getValintatapajonot.")
      Future.successful(Seq.empty)
    }

  }
}

class ValintaperusteetServiceMock extends IValintaperusteetService {
  override def getValintatapajonot(jonoOids: Set[String]): Future[Seq[ValintatapajononTiedot]] = Future.successful(jonoOids.map(oid => ValintatapajononTiedot(oid, Some("valintatapajono_m"))).toSeq)
}
