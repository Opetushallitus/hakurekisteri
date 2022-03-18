package fi.vm.sade.hakurekisteri.integration.kooste

import akka.actor.ActorSystem
import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.hakemus.{AtaruHakemus, FullHakemus, HakijaHakemus}
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.Future

trait IKoosteService {
  def getSuoritukset(
    hakuOid: String,
    hakemukset: Seq[HakijaHakemus]
  ): Future[Map[String, Map[String, String]]]
}

class KoosteService(restClient: VirkailijaRestClient, pageSize: Int = 200)(implicit
  val system: ActorSystem
) extends IKoosteService {

  case class SearchParams(
    aoOids: Seq[String] = null,
    asId: String = null,
    organizationFilter: String = null,
    updatedAfter: String = null,
    start: Int = 0,
    rows: Int = pageSize
  )

  private val logger = Logging.getLogger(system, this)

  def getSuoritukset(
    hakuOid: String,
    hs: Seq[HakijaHakemus]
  ): Future[Map[String, Map[String, String]]] = {
    val hakuAppHakemukset: Seq[FullHakemus] = hs.collect { case h: FullHakemus => h }
    if (hakuAppHakemukset.nonEmpty) {
      val hakemusHakijat: Seq[HakemusHakija] =
        hakuAppHakemukset.map(h => HakemusHakija(h.personOid.get, h))
      logger.info(s"Get suoritukset for ${hakuAppHakemukset.size} hakemukset for haku $hakuOid")
      restClient.postObject[Seq[HakemusHakija], Map[String, Map[String, String]]](
        "valintalaskentakoostepalvelu.suorituksetByOpiskelijaOid",
        hakuOid
      )(200, hakemusHakijat)
    } else {
      val hakemusOids = hs.map(hh => hh.oid).toList
      logger.info(
        s"Getting atarusuoritukset from koostepalvelu for ataruhakemukset: ${hakemusOids}"
      )
      restClient.postObject[List[String], Map[String, Map[String, String]]](
        "valintalaskentakoostepalvelu.atarusuorituksetByOpiskelijaOid",
        hakuOid
      )(200, hakemusOids)
    }
  }

  case class HakemusHakija(opiskelijaOid: String, hakemus: FullHakemus)
}

class KoosteServiceMock extends IKoosteService {
  override def getSuoritukset(
    hakuOid: String,
    hakemukset: Seq[HakijaHakemus]
  ): Future[Map[String, Map[String, String]]] =
    Future.successful(Map[String, Map[String, String]]())
}
