package fi.vm.sade.hakurekisteri.integration.supa

import akka.actor.ActorSystem
import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.hakemus.{HakemuksenHarkinnanvaraisuus, HakijaHakemus}

import scala.concurrent.{ExecutionContext, Future}

case class SupaHarkinnanvaraisuusRequest(hakemusOids: List[String])

case class SupaValintadataRequest(hakuOid: String, hakemusOids: List[String])

case class SupaAvain(avain: String, arvo: Option[String])

case class SupaValintaHakemus(
  hakemusoid: String,
  hakijaOid: String,
  avaimet: List[SupaAvain]
)

case class SupaValintadataResponse(valintaHakemukset: List[SupaValintaHakemus])

trait ISupaService {
  def getHarkinnanvaraisuudet(hs: Seq[HakijaHakemus]): Future[Seq[HakemuksenHarkinnanvaraisuus]]
  def getSuorituksetForAtaruhakemukset(
    hakuOid: String,
    hs: Seq[HakijaHakemus]
  ): Future[Map[String, Map[String, String]]]
}

class SupaService(restClient: VirkailijaRestClient, batchSize: Int = 5000)(implicit
  val system: ActorSystem
) extends ISupaService {

  private val logger = Logging.getLogger(system, this)
  private implicit val ec: ExecutionContext = system.dispatcher

  override def getHarkinnanvaraisuudet(
    hs: Seq[HakijaHakemus]
  ): Future[Seq[HakemuksenHarkinnanvaraisuus]] = {
    val hakemusOids = hs.map(_.oid).toList
    if (hakemusOids.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val batches = hakemusOids.grouped(batchSize).toList
      logger.info(
        s"${Thread.currentThread().getName} Haetaan suorituspalvelusta harkinnanvaraisuudet ${hakemusOids.size} hakemukselle ${batches.size} erässä (eräkoko $batchSize)"
      )
      batches.zipWithIndex.foldLeft(Future.successful(Seq.empty[HakemuksenHarkinnanvaraisuus])) {
        case (acc, (batch, idx)) =>
          acc.flatMap { previousResults =>
            logger.info(
              s"${Thread.currentThread().getName} Haetaan harkinnanvaraisuudet erä ${idx + 1}/${batches.size} (${batch.size} hakemusta)"
            )
            restClient
              .postObject[SupaHarkinnanvaraisuusRequest, Seq[HakemuksenHarkinnanvaraisuus]](
                "suorituspalvelu.harkinnanvaraisuus"
              )(200, SupaHarkinnanvaraisuusRequest(batch))
              .map(previousResults ++ _)
          }
      }
    }
  }

  override def getSuorituksetForAtaruhakemukset(
    hakuOid: String,
    hs: Seq[HakijaHakemus]
  ): Future[Map[String, Map[String, String]]] = {
    val hakemusOids = hs.map(_.oid).toList
    if (hakemusOids.isEmpty) {
      Future.successful(Map.empty)
    } else {
      val batches = hakemusOids.grouped(batchSize).toList
      logger.info(
        s"${Thread.currentThread().getName} Haetaan suorituspalvelusta suoritukset ${hakemusOids.size} hakemukselle haussa $hakuOid ${batches.size} erässä (eräkoko $batchSize)"
      )
      batches.zipWithIndex.foldLeft(Future.successful(Map.empty[String, Map[String, String]])) {
        case (acc, (batch, idx)) =>
          acc.flatMap { previousResults =>
            logger.info(
              s"${Thread.currentThread().getName} Haetaan suoritukset erä ${idx + 1}/${batches.size} (${batch.size} hakemusta) haussa $hakuOid"
            )
            restClient
              .postObject[SupaValintadataRequest, SupaValintadataResponse](
                "suorituspalvelu.valintadata"
              )(200, SupaValintadataRequest(hakuOid, batch))
              .map { response =>
                val batchMap = response.valintaHakemukset.map { hakemus =>
                  val avainMap =
                    hakemus.avaimet.collect { case SupaAvain(k, Some(v)) => k -> v }.toMap
                  hakemus.hakijaOid -> avainMap
                }.toMap
                previousResults ++ batchMap
              }
          }
      }
    }
  }
}

class SupaServiceMock extends ISupaService {
  override def getHarkinnanvaraisuudet(
    hs: Seq[HakijaHakemus]
  ): Future[Seq[HakemuksenHarkinnanvaraisuus]] =
    Future.successful(Seq.empty)

  override def getSuorituksetForAtaruhakemukset(
    hakuOid: String,
    hs: Seq[HakijaHakemus]
  ): Future[Map[String, Map[String, String]]] =
    Future.successful(Map.empty)
}
