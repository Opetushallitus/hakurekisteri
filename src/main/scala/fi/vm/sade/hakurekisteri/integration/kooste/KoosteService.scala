package fi.vm.sade.hakurekisteri.integration.kooste


import akka.actor.ActorSystem
import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.hakemus.{AtaruHakemus, FullHakemus, HakijaHakemus}
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.haku.Haku

import scala.concurrent.Future

trait IKoosteService {
  def getSuoritukset(haku: Haku, hakemukset: Seq[HakijaHakemus]): Future[Map[String,Map[String,String]]]
}

class KoosteService(restClient: VirkailijaRestClient, pageSize: Int = 200)
                    (implicit val system: ActorSystem) extends IKoosteService {

  case class SearchParams(aoOids: Seq[String] = null, asId: String = null, organizationFilter: String = null,
                          updatedAfter: String = null, start: Int = 0, rows: Int = pageSize)

  private val logger = Logging.getLogger(system, this)

  def getSuoritukset(haku: Haku, hs: Seq[HakijaHakemus]): Future[Map[String, Map[String, String]]] = {
    haku.ataruLomakeAvain match {
      case Some(_) =>
        val hakemusOids: Seq[String] = hs.collect { case h: AtaruHakemus => h }
          .map(_.oid)
        if (hakemusOids.nonEmpty) {
          logger.info(s"Get suoritukset for ${hakemusOids.size} ataru applications for haku ${haku.oid}")
          restClient.postObject[Seq[String], Map[String, Map[String, String]]]("valintalaskentakoostepalvelu.ataruSuorituksetByOpiskelijaOid", haku.oid)(200, hakemusOids)
        } else {
          Future.successful(Map.empty)
        }
      case None =>
        val hakemusHakijat: Seq[HakemusHakija] = hs.collect { case h: FullHakemus => h }
          .map(h => HakemusHakija(h.personOid.get, h))
        if (hakemusHakijat.nonEmpty) {
          logger.info(s"Get suoritukset for ${hakemusHakijat.size} hakuapp applications for haku ${haku.oid}")
          restClient.postObject[Seq[HakemusHakija], Map[String, Map[String, String]]]("valintalaskentakoostepalvelu.suorituksetByOpiskelijaOid", haku.oid)(200, hakemusHakijat)
        } else {
          Future.successful(Map.empty)
        }
    }
  }

  case class HakemusHakija(opiskelijaOid: String, hakemus: FullHakemus)
}

class KoosteServiceMock extends IKoosteService {
  override def getSuoritukset(haku: Haku, hakemukset: Seq[HakijaHakemus]): Future[Map[String,Map[String,String]]] = Future.successful(Map[String,Map[String,String]]())
}
