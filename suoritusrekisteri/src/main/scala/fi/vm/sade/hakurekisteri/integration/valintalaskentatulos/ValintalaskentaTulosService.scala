package fi.vm.sade.hakurekisteri.integration.valintalaskentatulos

import akka.actor.ActorSystem
import akka.event.Logging
import com.github.blemale.scaffeine.Scaffeine
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait IValintalaskentaTulosService {
  def getHakukohteidenValinnanvaiheet(
    hakukohdeOids: Set[String]
  ): Future[Map[String, Seq[LaskennanTulosValinnanvaihe]]]

}

case class FunktioTulos(
  tunniste: String,
  arvo: String,
  nimiFi: Option[String],
  nimiSv: Option[String],
  nimiEn: Option[String]
)
case class LaskennanTulosJonosija(
  jonosija: Int,
  hakemusOid: String,
  hakijaOid: String,
  funktioTulokset: List[FunktioTulos]
)
case class LaskennanTulosValintatapajono(
  valintatapajonooid: String,
  jonosijat: List[LaskennanTulosJonosija]
)
case class LaskennanTulosValinnanvaihe(
  valinnanvaiheoid: String,
  valintatapajonot: List[LaskennanTulosValintatapajono]
) {
  val keskiarvoKeys = Set("keskiarvo_pk", "keskiarvo_lk", "painotettu_keskiarvo")

  def keskiarvoHakemukselle(hakemusOid: String) = {
    val keskiarvot = for {
      jono <- valintatapajonot
      jonosija <- jono.jonosijat.filter(sija => sija.hakemusOid.equals(hakemusOid));
      funktiotulos <- jonosija.funktioTulokset.filter(tulos =>
        keskiarvoKeys.contains(tulos.tunniste)
      )
    } yield {
      funktiotulos.arvo
    }
    keskiarvot.find(arvo => arvo.nonEmpty)
  }
}
case class LaskennanTulosHakukohde(oid: String, valinnanvaihe: List[LaskennanTulosValinnanvaihe])
case class LaskennanTulosHakemukselle(
  hakemusoid: String,
  hakukohteet: List[LaskennanTulosHakukohde]
)

class ValintalaskentaTulosService(restClient: VirkailijaRestClient)(implicit
  val system: ActorSystem
) extends IValintalaskentaTulosService {
  val logger = Logging.getLogger(system, this)

  private val laskennanTulosCache = Scaffeine()
    .expireAfterWrite(30.minutes)
    .buildAsyncFuture[String, (String, Seq[LaskennanTulosValinnanvaihe])](
      getHakukohteenValinnanvaiheet
    )

  private def getHakukohteenValinnanvaiheetCached(hakukohdeOid: String) = {
    laskennanTulosCache.get(hakukohdeOid)
  }

  private def getHakukohteenValinnanvaiheet(
    hakukohdeOid: String
  ): Future[(String, Seq[LaskennanTulosValinnanvaihe])] = {
    logger.info(s"Haetaan valintalaskennasta tulokset hakukohteen $hakukohdeOid valinnanvaiheille.")
    for {
      result <- restClient.readObject[Seq[LaskennanTulosValinnanvaihe]](
        "valintalaskentatulos.valinnanvaiheet.hakukohteelle",
        hakukohdeOid
      )(200)
    } yield {
      (hakukohdeOid, result)
    }
  }

  override def getHakukohteidenValinnanvaiheet(
    hakukohdeOids: Set[String]
  ): Future[Map[String, Seq[LaskennanTulosValinnanvaihe]]] = {
    logger.info(
      s"Haetaan valintalaskennasta tulokset hakukohteiden ${hakukohdeOids} valinnanvaiheille."
    )
    Future
      .sequence(hakukohdeOids.map(hk => getHakukohteenValinnanvaiheetCached(hk)))
      .map(r => r.map(res => res._1 -> res._2).toMap)
  }
}

class ValintalaskentaTulosServiceMock extends IValintalaskentaTulosService {
  override def getHakukohteidenValinnanvaiheet(
    hakukohdeOids: Set[String]
  ): Future[Map[String, Seq[LaskennanTulosValinnanvaihe]]] = ???
}
