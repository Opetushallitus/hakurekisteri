package fi.vm.sade.hakurekisteri.integration.pistesyotto

import fi.vm.sade.hakurekisteri.integration.{VirkailijaRestClient}
import scala.concurrent.{Future}

case class Pistetieto(aikaleima: String, tunniste: String, arvo: Any, osallistuminen: String)
case class PistetietoWrapper(hakemusOID: String, pisteet: Seq[Pistetieto])

class PistesyottoService(restClient: VirkailijaRestClient) {

  def fetchPistetiedot(hakemusOids: Set[String]): Future[Seq[PistetietoWrapper]] = {

    val result: Future[Seq[PistetietoWrapper]] = restClient
      .postObject[Seq[String], Seq[PistetietoWrapper]]("pistesyotto-service.hakemuksen.pisteet")(
        acceptedResponseCode = 200,
        hakemusOids.toSeq
      )

    result
  }
}
