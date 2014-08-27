package fi.vm.sade.hakurekisteri.integration.virta

import akka.actor.Actor
import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatiopalvelu
import fi.vm.sade.hakurekisteri.integration.tarjonta.TarjontaClient
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, yksilollistaminen}
import org.joda.time.LocalDate

import scala.concurrent.{ExecutionContext, Future}
import akka.pattern.pipe

case class VirtaQuery(oppijanumero: Option[String], hetu: Option[String]) {
  if (oppijanumero.isEmpty && hetu.isEmpty) throw new IllegalArgumentException(s"oppijanumero and hetu are both empty")
}

case class KomoNotFoundException(message: String) extends Exception(message)

class VirtaActor(virtaClient: VirtaClient, organisaatiopalvelu: Organisaatiopalvelu, tarjontaClient: TarjontaClient) extends Actor {
  implicit val executionContext: ExecutionContext = context.dispatcher
  val log = Logging(context.system, this)

  def receive: Receive = {
    case q: VirtaQuery =>
      convertVirtaResult(virtaClient.getOpiskelijanTiedot(oppijanumero = q.oppijanumero, hetu = q.hetu))(q.oppijanumero) pipeTo sender
  }

  def opiskeluoikeus(oppijanumero: Option[String])(o: VirtaOpiskeluoikeus): Future[Opiskeluoikeus] = {
    resolveOppilaitosOid(o.myontaja).flatMap((oppilaitosOid) => {
      resolveKomoOid(o.koulutuskoodit.head).map((komoOid) => {
        Opiskeluoikeus(
          alkuPaiva = o.alkuPvm,
          loppuPaiva = o.loppuPvm,
          henkiloOid = oppijanumero.get,
          komo = komoOid,
          myontaja = oppilaitosOid
        )
      })
    })
  }

  def tutkinto(oppijanumero: Option[String])(t: VirtaTutkinto): Future[Suoritus] = {
    resolveOppilaitosOid(t.myontaja).flatMap((oppilaitosOid) => {
      resolveKomoOid(t.koulutuskoodi.get).map((komoOid) => {
        Suoritus(
          komo = komoOid,
          myontaja = oppilaitosOid,
          valmistuminen = t.suoritusPvm,
          tila = tila(t.suoritusPvm),
          henkiloOid = oppijanumero.get,
          yksilollistaminen = yksilollistaminen.Ei,
          suoritusKieli = t.kieli
        )
      })
    })
  }

  def tila(valmistuminen: LocalDate): String = valmistuminen match {
    case v: LocalDate if v.isBefore(new LocalDate()) => "VALMIS"
    case _ => "KESKEN"
  }

  def convertVirtaResult(f: Future[Option[VirtaResult]])(oppijanumero: Option[String]): Future[(Seq[Opiskeluoikeus], Seq[Suoritus])] = f.flatMap {
    case None => Future.successful((Seq(), Seq()))
    case Some(r) => {
      val opiskeluoikeudet: Future[Seq[Opiskeluoikeus]] = Future.sequence(r.opiskeluoikeudet.map(opiskeluoikeus(oppijanumero)))
      val suoritukset: Future[Seq[Suoritus]] = Future.sequence(r.tutkinnot.map(tutkinto(oppijanumero)))
      for {
        o <- opiskeluoikeudet
        s <- suoritukset
      } yield (o, s)
    }
  }

  def resolveOppilaitosOid(oppilaitosnumero: String): Future[String] = {
    organisaatiopalvelu.get(oppilaitosnumero).map(_ match {
      case Some(org) => org.oid
      case _ => log.error(s"oppilaitos not found with oppilaitosnumero $oppilaitosnumero"); throw OppilaitosNotFoundException(s"oppilaitos not found with oppilaitosnumero $oppilaitosnumero")
    })
  }

  def resolveKomoOid(koulutuskoodi: String): Future[String] = {
    tarjontaClient.searchKomo(koulutuskoodi).map(_.headOption match {
      case Some(komo) => komo.oid
      case _ => throw KomoNotFoundException(s"komo not found with koulutuskoodi $koulutuskoodi") // FIXME should fallback to saving koulutuskoodi instead
    })
  }
}
