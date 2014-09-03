package fi.vm.sade.hakurekisteri.integration.tarjonta

import java.net.URLEncoder

import akka.actor.Actor
import com.stackmob.newman.response.HttpResponseCode
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.{ExecutionContext, Future}
import akka.pattern.pipe

case class SearchKomoQuery(koulutus: String)

case class GetKomoQuery(oid: String)

case class TarjontaSearchResponse(result: Seq[Komo])

case class TarjontaKomoResponse(result: Option[Komo])

class TarjontaActor(restClient: VirkailijaRestClient)(implicit val ec: ExecutionContext) extends Actor {
  override def receive: Receive = {
    case q: SearchKomoQuery => searchKomo(q.koulutus) pipeTo sender
    case q: GetKomoQuery => getKomo(q.oid) pipeTo sender
  }
  
  def searchKomo(koulutus: String): Future[Seq[Komo]] = {
    restClient.readObject[TarjontaSearchResponse](s"/rest/v1/komo/search?koulutus=${URLEncoder.encode(koulutus, "UTF-8")}", HttpResponseCode.Ok).map(_.result)
  }

  def getKomo(oid: String): Future[Option[Komo]] = {
    restClient.readObject[TarjontaKomoResponse](s"/rest/v1/komo/${URLEncoder.encode(oid, "UTF-8")}", HttpResponseCode.Ok).map(_.result)
  }
}
