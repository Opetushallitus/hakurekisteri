package fi.vm.sade.hakurekisteri.virta

import akka.actor.Actor
import akka.dispatch.Futures
import akka.event.Logging
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen, Suoritus}
import org.joda.time.LocalDate

import scala.concurrent.{Future, ExecutionContext}

class VirtaActor(virtaClient: VirtaClient) extends Actor {
  implicit val executionContext: ExecutionContext = context.dispatcher
  val log = Logging(context.system, this)

  def receive: Receive = {
    case (oppijanumero: Option[String], hetu: Option[String]) =>
      virtaResult2suoritukset(virtaClient.getOpiskelijanTiedot(oppijanumero = oppijanumero, hetu = hetu))(oppijanumero) pipeTo sender
  }

  def opiskeluoikeus2suoritus(oppijanumero: Option[String])(o: VirtaOpiskeluoikeus): Future[Suoritus] = {
    val valm: LocalDate = valmistuminen(o)
    resolveOppilaitosOid(o.myontaja).flatMap((oppilaitosOid) => {
      resolveKomoOid(o.koulutuskoodit.head, o.opintoala1995, o.koulutusala2002).map((komoOid) => {
        Suoritus(
          komo = komoOid,
          myontaja = oppilaitosOid,
          valmistuminen = valm,
          tila = tila(valm),
          henkiloOid = oppijanumero.get,
          yksilollistaminen = yksilollistaminen.Ei,
          suoritusKieli = o.kieli
        )
      })
    })
  }

  def valmistuminen(o: VirtaOpiskeluoikeus): LocalDate = o.loppuPvm match {
    case None => new LocalDate(1900 + o.alkuPvm.getYear + 7, 12, 31)
    case Some(l) => l
  }

  def tutkinto2suoritus(oppijanumero: Option[String])(t: VirtaTutkinto): Future[Suoritus] = {
    resolveOppilaitosOid(t.myontaja).flatMap((oppilaitosOid) => {
      resolveKomoOid(t.koulutuskoodi.get, t.opintoala1995, t.koulutusala2002).map((komoOid) => {
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

  def virtaResult2suoritukset(f: Future[Option[VirtaResult]])(oppijanumero: Option[String]): Future[Seq[Suoritus]] = f.flatMap {
    case None => Future.successful(Seq())
    case Some(r) =>
      Future.sequence(r.opiskeluoikeudet.map(opiskeluoikeus2suoritus(oppijanumero)) ++ r.tutkinnot.map(tutkinto2suoritus(oppijanumero)))
  }

  def resolveOppilaitosOid(oppilaitosnumero: String): Future[String] = {
    Future.successful(oppilaitosnumero) // FIXME search from organisaatio-service
  }

  def resolveKomoOid(koulutuskoodi: String, opintoala1995: Option[String], koulutusala2002: Option[String]): Future[String] = {
    Future.successful("") // FIXME search from tarjonta-service
  }
}
