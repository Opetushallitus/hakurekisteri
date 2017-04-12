package fi.vm.sade.hakurekisteri.integration.kooste


import akka.actor.ActorSystem
import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.hakemus.FullHakemus
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.Future
import scala.concurrent.duration._

trait IKoosteService {
  def getSuoritukset(hakuOid: String, hakemus: FullHakemus): Future[Map[String,String]]
  def getSuoritukset(hakuOid: String, hakemukset: Seq[FullHakemus]): Future[Map[String,Map[String,String]]]
}

class KoosteService(restClient: VirkailijaRestClient, pageSize: Int = 200)
                    (implicit val system: ActorSystem) extends IKoosteService {

  case class SearchParams(aoOids: Seq[String] = null, asId: String = null, organizationFilter: String = null,
                          updatedAfter: String = null, start: Int = 0, rows: Int = pageSize)

  private val logger = Logging.getLogger(system, this)

  def getSuoritukset(hakuOid: String, hakemus: FullHakemus): Future[Map[String,String]] = {
      val opiskelijaOid = hakemus.personOid.get
      restClient.postObject[FullHakemus, Map[String,String]]("valintalaskentakoostepalvelu.suorituksetByOpiskelijaOid", hakuOid, opiskelijaOid)(200, hakemus)
  }

  def getSuoritukset(hakuOid: String, hakemukset: Seq[FullHakemus]): Future[Map[String,Map[String,String]]] = {
    val hakemusHakijat: Seq[HakemusHakija] = hakemukset.map(h => HakemusHakija(h.personOid.get, h))
    restClient.postObject[Seq[HakemusHakija], Map[String,Map[String,String]]]("valintalaskentakoostepalvelu.suorituksetByOpiskelijaOid", hakuOid)(200, hakemusHakijat)
  }

  case class HakemusHakija(opiskelijaOid: String, hakemus: FullHakemus)
}

class KoosteServiceMock extends IKoosteService {
  override def getSuoritukset(hakuOid: String, hakemus: FullHakemus): Future[Map[String,String]] = Future.successful(Map[String,String]())
  override def getSuoritukset(hakuOid: String, hakemukset: Seq[FullHakemus]): Future[Map[String,Map[String,String]]] = Future.successful(Map[String,Map[String,String]]())
}
