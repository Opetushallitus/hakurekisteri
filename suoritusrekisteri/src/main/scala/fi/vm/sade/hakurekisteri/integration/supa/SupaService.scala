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

class SupaService(restClient: VirkailijaRestClient)(implicit val system: ActorSystem)
    extends ISupaService {

  private val logger = Logging.getLogger(system, this)
  private implicit val ec: ExecutionContext = system.dispatcher

  override def getHarkinnanvaraisuudet(
    hs: Seq[HakijaHakemus]
  ): Future[Seq[HakemuksenHarkinnanvaraisuus]] = {
    val hakemusOids = hs.map(_.oid).toList
    if (hakemusOids.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      logger.info(
        s"${Thread.currentThread().getName} Haetaan suorituspalvelusta harkinnanvaraisuudet ${hakemusOids.size} hakemukselle"
      )
      restClient.postObject[SupaHarkinnanvaraisuusRequest, Seq[HakemuksenHarkinnanvaraisuus]](
        "suorituspalvelu.harkinnanvaraisuus"
      )(200, SupaHarkinnanvaraisuusRequest(hakemusOids))
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
      logger.info(
        s"${Thread.currentThread().getName} Haetaan suorituspalvelusta suoritukset ${hakemusOids.size} hakemukselle haussa $hakuOid"
      )
      restClient
        .postObject[SupaValintadataRequest, SupaValintadataResponse](
          "suorituspalvelu.valintadata"
        )(200, SupaValintadataRequest(hakuOid, hakemusOids))
        .map { response =>
          response.valintaHakemukset.map { hakemus =>
            val avainMap = hakemus.avaimet.collect { case SupaAvain(k, Some(v)) => k -> v }.toMap
            hakemus.hakijaOid -> avainMap
          }.toMap
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
