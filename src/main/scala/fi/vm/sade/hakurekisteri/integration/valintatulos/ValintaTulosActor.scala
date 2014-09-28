package fi.vm.sade.hakurekisteri.integration.valintatulos

import java.net.URLEncoder

import akka.actor.Actor
import akka.event.Logging
import akka.pattern.pipe
import com.stackmob.newman.response.HttpResponseCode
import fi.vm.sade.hakurekisteri.integration.{PreconditionFailedException, VirkailijaRestClient}

import scala.concurrent.{Future, ExecutionContext}

case class ValintaTulosQuery(hakuOid: String,
                             hakemusOid: String)

case class ValintaTulosHakutoive(hakukohdeOid: String,
                                 tarjoajaOid: String,
                                 valintatila: String,
                                 vastaanottotila: String,
                                 ilmoittautumistila: String,
                                 vastaanotettavuustila: String,
                                 julkaistavissa: Boolean)

case class ValintaTulos(hakemusOid: String,
                        hakutoiveet: Seq[ValintaTulosHakutoive])

class ValintaTulosActor(restClient: VirkailijaRestClient)
                       (implicit val ec: ExecutionContext) extends Actor {

  val log = Logging(context.system, this)

  override def receive: Receive = {
    case q: ValintaTulosQuery => getTulos(q) pipeTo sender
  }

  def getTulos(q: ValintaTulosQuery): Future[ValintaTulos] = {
    try {
      restClient.readObject[ValintaTulos](s"/haku/${URLEncoder.encode(q.hakuOid, "UTF-8")}/hakemus/${URLEncoder.encode(q.hakemusOid, "UTF-8")}", HttpResponseCode.Ok)
    } catch {
      case t: PreconditionFailedException =>
        log.warning(s"valinta tulos not found with haku ${q.hakuOid} and hakemus ${q.hakemusOid}: $t")
        Future.successful(ValintaTulos(q.hakemusOid, Seq()))
    }
  }

}
