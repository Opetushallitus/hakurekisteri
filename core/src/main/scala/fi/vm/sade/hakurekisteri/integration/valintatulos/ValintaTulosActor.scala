package fi.vm.sade.hakurekisteri.integration.valintatulos

import java.util.concurrent.ExecutionException

import akka.actor.{Actor, ActorLogging}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.valintatulos.Ilmoittautumistila._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila._
import fi.vm.sade.hakurekisteri.integration.{PreconditionFailedException, VirkailijaRestClient}

import scala.concurrent.{ExecutionContext, Future}

case class ValintaTulosQuery(hakuOid: String,
                             hakukohdeOid: Option[String],
                             hakemusOid: Option[String],
                             cachedOk: Boolean = true)

class ValintaTulosActor(client: VirkailijaRestClient, config: Config) extends Actor with ActorLogging {

  implicit val ec: ExecutionContext = context.dispatcher
  private val maxRetries: Int = config.integrations.valintaTulosConfig.httpClientMaxRetries

  override def receive: Receive = {
    case q: ValintaTulosQuery =>
      haeSijoittelu(q) pipeTo sender
  }

  private def haeSijoittelu(q: ValintaTulosQuery): Future[SijoitteluTulos] = {
    if (q.hakemusOid.isEmpty && q.hakukohdeOid.isEmpty) {
      kutsuHakuOIDilla(q.hakuOid)
    } else if (q.hakemusOid.isDefined) {
      kutsuHakemusOIDilla(q.hakuOid, q.hakemusOid.get)
    } else {
      kutsuHakukohdeOIDilla(q.hakuOid, q.hakukohdeOid.get)
    }
  }

  private def kutsuHakuOIDilla(hakuOid: String): Future[SijoitteluTulos] = client.
      readObject[Seq[ValintaTulos]]("valinta-tulos-service.haku", hakuOid)(200).
      recoverWith {
        case t: ExecutionException if t.getCause != null && onko404(t.getCause) =>
          log.warning(s"valinta tulos not found with haku $hakuOid: $t")
          Future.successful(Seq[ValintaTulos]())
      }.
      map(valintaTulokset2SijoitteluTulos)

  private def kutsuHakukohdeOIDilla(hakuOid: String, hakukohdeOid: String): Future[SijoitteluTulos] = client.
      readObject[Seq[ValintaTulos]]("valinta-tulos-service.hakukohde", hakuOid, hakukohdeOid)(200).
      recoverWith {
        case t: ExecutionException if t.getCause != null && onko404(t.getCause) =>
          log.warning(s"valinta tulos not found with haku $hakuOid and hakukohde $hakukohdeOid: $t")
          Future.successful(Seq[ValintaTulos]())
      }.
      map(valintaTulokset2SijoitteluTulos)

  private def kutsuHakemusOIDilla(hakuOid: String, hakemusOid: String): Future[SijoitteluTulos] = {
    client.
      readObject[ValintaTulos]("valinta-tulos-service.hakemus", hakuOid, hakemusOid)(200, maxRetries).
      recoverWith {
        case t: ExecutionException if t.getCause != null && onko404(t.getCause) =>
          log.warning(s"valinta tulos not found with haku $hakuOid and hakemus $hakemusOid: $t")
          Future.successful(ValintaTulos(hakemusOid, Seq()))
      }.
      map(t => {
        valintaTulokset2SijoitteluTulos(t)
      })
  }

  private def onko404(t: Throwable): Boolean = t match {
    case PreconditionFailedException(_, 404) => true
    case _ => false
  }

  private def valintaTulokset2SijoitteluTulos(tulokset: ValintaTulos*): SijoitteluTulos = new SijoitteluTulos {
    val hakemukset = tulokset.groupBy(t => t.hakemusOid).mapValues(_.head)

    private def hakukohde(hakemusOid: String, hakukohdeOid: String): Option[ValintaTulosHakutoive] = hakemukset.get(hakemusOid).flatMap(_.hakutoiveet.find(_.hakukohdeOid == hakukohdeOid))

    override def pisteet(hakemusOid: String, hakukohdeOid: String): Option[BigDecimal] = hakukohde(hakemusOid, hakukohdeOid).flatMap(_.pisteet)
    override def valintatila(hakemusOid: String, hakukohdeOid: String): Option[Valintatila] = hakukohde(hakemusOid, hakukohdeOid).map(_.valintatila)
    override def vastaanottotila(hakemusOid: String, hakukohdeOid: String): Option[Vastaanottotila] = hakukohde(hakemusOid, hakukohdeOid).map(_.vastaanottotila)
    override def ilmoittautumistila(hakemusOid: String, hakukohdeOid: String): Option[Ilmoittautumistila] = hakukohde(hakemusOid, hakukohdeOid).map(_.ilmoittautumistila.ilmoittautumistila)
  }

}

case class UpdateValintatulos(haku: String)

case class BatchUpdateValintatulos(haut: Set[UpdateValintatulos])