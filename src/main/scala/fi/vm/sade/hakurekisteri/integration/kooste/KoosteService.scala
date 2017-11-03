package fi.vm.sade.hakurekisteri.integration.kooste


import akka.actor.ActorSystem
import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.hakemus.{AtaruHakemus, FullHakemus, HakijaHakemus}
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.Future

trait IKoosteService {
  def getSuoritukset(hakuOid: String, hakemus: HakijaHakemus): Future[Map[String,String]]
  def getSuoritukset(hakuOid: String, hakemukset: Seq[HakijaHakemus]): Future[Map[String,Map[String,String]]]
}

class KoosteService(restClient: VirkailijaRestClient, pageSize: Int = 200)
                    (implicit val system: ActorSystem) extends IKoosteService {

  case class SearchParams(aoOids: Seq[String] = null, asId: String = null, organizationFilter: String = null,
                          updatedAfter: String = null, start: Int = 0, rows: Int = pageSize)

  private val logger = Logging.getLogger(system, this)

  def getSuoritukset(hakuOid: String, h: HakijaHakemus): Future[Map[String,String]] = h match {
    case hakemus: FullHakemus =>
      val opiskelijaOid = hakemus.personOid.get
      logger.info(s"Get suoritukset for single hakemus for haku $hakuOid")
      restClient.postObject[FullHakemus, Map[String,String]]("valintalaskentakoostepalvelu.suorituksetByOpiskelijaOid", hakuOid, opiskelijaOid)(200, hakemus)
    case _: AtaruHakemus => throw new UnsupportedOperationException
  }

  def getSuoritukset(hakuOid: String, hs: Seq[HakijaHakemus]): Future[Map[String,Map[String,String]]] = {
    val hakemukset: Seq[FullHakemus] = hs.map {
      case h: FullHakemus => h
      case _: AtaruHakemus => throw new UnsupportedOperationException
    }
    if (hakemukset.nonEmpty) {
      val hakemusHakijat: Seq[HakemusHakija] = hakemukset.map(h => HakemusHakija(h.personOid.get, h))
      logger.info(s"Get suoritukset for ${hakemukset.size} hakemukset for haku $hakuOid")
      restClient.postObject[Seq[HakemusHakija], Map[String,Map[String,String]]]("valintalaskentakoostepalvelu.suorituksetByOpiskelijaOid", hakuOid)(200, hakemusHakijat)
    } else {
      Future.successful(Map.empty)
    }
  }

  case class HakemusHakija(opiskelijaOid: String, hakemus: FullHakemus)
}

class KoosteServiceMock extends IKoosteService {
  override def getSuoritukset(hakuOid: String, hakemus: HakijaHakemus): Future[Map[String,String]] = Future.successful(Map[String,String]())
  override def getSuoritukset(hakuOid: String, hakemukset: Seq[HakijaHakemus]): Future[Map[String,Map[String,String]]] = Future.successful(Map[String,Map[String,String]]())
}
