package fi.vm.sade.hakurekisteri.integration.valintalaskentatulos

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.Future

trait IValintalaskentaTulosService {
  def getLaskennanTulosForHakemusOids(
    hakuOid: String,
    hakemusOids: Seq[String]
  ): Future[Seq[LaskennanTulosHakemukselle]]
}

case class LaskennanTulosHakemukselle(hakemusOid: String)

class ValintalaskentaTulosService(restClient: VirkailijaRestClient)(implicit
  val system: ActorSystem
) extends IValintalaskentaTulosService {

  override def getLaskennanTulosForHakemusOids(
    hakuOid: String,
    hakemusOids: Seq[String]
  ): Future[Seq[LaskennanTulosHakemukselle]] = {
    if (hakemusOids.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      restClient.postObject[Seq[String], Seq[LaskennanTulosHakemukselle]](
        "valintalaskentatulos.laskennantulos.hakemuksille",
        hakuOid
      )(200, hakemusOids)
    }
  }
}

class ValintalaskentaTulosServiceMock extends IValintalaskentaTulosService {
  override def getLaskennanTulosForHakemusOids(
    hakuOid: String,
    hakemusOids: Seq[String]
  ): Future[Seq[LaskennanTulosHakemukselle]] =
    Future.successful(Seq.empty)
}
