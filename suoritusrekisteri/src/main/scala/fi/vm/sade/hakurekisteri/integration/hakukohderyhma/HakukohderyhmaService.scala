package fi.vm.sade.hakurekisteri.integration.hakukohderyhma

import akka.actor.ActorSystem
import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.Future

trait IHakukohderyhmaService {
  def getHakukohteetOfHakukohderyhma(hakukohderyhma: String): Future[Seq[String]]
}

class HakukohderyhmaService(restClient: VirkailijaRestClient)(implicit val system: ActorSystem)
    extends IHakukohderyhmaService {

  private val logger = Logging.getLogger(system, this)
  private val acceptedResponseCode: Int = 200
  private val maxRetries: Int = 2

  override def getHakukohteetOfHakukohderyhma(hakukohderyhma: String): Future[Seq[String]] = {
    logger.debug(s"Fetching hakukohteet for hakukohderyhma $hakukohderyhma")
    restClient.readObject[Seq[String]](
      "hakukohderyhmapalvelu.hakukohderyhma.hakukohteet",
      hakukohderyhma
    )(acceptedResponseCode, maxRetries)
  }
}

class HakukohderyhmaServiceMock extends IHakukohderyhmaService {
  override def getHakukohteetOfHakukohderyhma(hakukohderyhma: String): Future[Seq[String]] =
    Future.successful(Seq("1.2.246.562.28.001"))
}
