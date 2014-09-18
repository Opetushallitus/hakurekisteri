package fi.vm.sade.hakurekisteri.integration.tarjonta

import java.net.URLEncoder

import akka.actor.Actor
import com.stackmob.newman.response.HttpResponseCode
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.{ExecutionContext, Future}
import akka.pattern.pipe
import spray.http.DateTime

case class SearchKomoQuery(koulutus: String)

case class GetKomoQuery(oid: String)

object GetHautQuery

case class RestHakuResult(result: List[RestHaku])

case class RestHaku(oid:Option[String], hakuaikas: List[RestHakuAika], nimi: Map[String, String], hakukausiUri: String,
hakukausiVuosi: Int)

case class RestHakuAika(alkuPvm:Long)

case class TarjontaSearchResponse(result: Seq[Komo])

case class TarjontaKomoResponse(result: Option[Komo])

case class KomoResponse(oid: String, komo: Option[Komo])

class TarjontaActor(restClient: VirkailijaRestClient)(implicit val ec: ExecutionContext) extends Actor {
  override def receive: Receive = {
    case q: SearchKomoQuery => searchKomo(q.koulutus) pipeTo sender
    case q: GetKomoQuery => getKomo(q.oid) pipeTo sender
    case GetHautQuery => getHaut pipeTo sender
  }
  
  def searchKomo(koulutus: String): Future[Seq[Komo]] = {
    restClient.readObject[TarjontaSearchResponse](s"/rest/v1/komo/search?koulutus=${URLEncoder.encode(koulutus, "UTF-8")}", HttpResponseCode.Ok).map(_.result)
  }

  def getKomo(oid: String): Future[KomoResponse] = {
    restClient.readObject[TarjontaKomoResponse](s"/rest/v1/komo/${URLEncoder.encode(oid, "UTF-8")}", HttpResponseCode.Ok).map(res => KomoResponse(oid, res.result))
  }



  def getHaut: Future[RestHakuResult] = restClient.readObject[RestHakuResult]("/rest/v1/haku/findAll", HttpResponseCode.Ok)


}


