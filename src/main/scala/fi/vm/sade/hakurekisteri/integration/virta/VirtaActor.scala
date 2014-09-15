package fi.vm.sade.hakurekisteri.integration.virta

import akka.actor.{ActorRef, Actor}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.integration.tarjonta.{Komo, SearchKomoQuery}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, yksilollistaminen}
import org.joda.time.LocalDate

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import akka.pattern.pipe

case class VirtaQuery(oppijanumero: Option[String], hetu: Option[String]) {
  if (oppijanumero.isEmpty && hetu.isEmpty) throw new IllegalArgumentException(s"oppijanumero and hetu are both empty")
}

case class KomoNotFoundException(message: String) extends Exception(message)

class VirtaActor(virtaClient: VirtaClient, organisaatioActor: ActorRef, tarjontaActor: ActorRef) extends Actor {
  implicit val executionContext: ExecutionContext = context.dispatcher
  val log = Logging(context.system, this)

  def receive: Receive = {
    case VirtaQuery(o, h) =>
      convertVirtaResult(virtaClient.getOpiskelijanTiedot(oppijanumero = o, hetu = h))(o) pipeTo sender
  }

  def opiskeluoikeus(oppijanumero: Option[String])(o: VirtaOpiskeluoikeus): Future[Opiskeluoikeus] = {
    resolveOppilaitosOid(o.myontaja).flatMap((oppilaitosOid) => {
      resolveKomoOid(o.koulutuskoodit.head).map((komoOid) => {
        Opiskeluoikeus(
          alkuPaiva = o.alkuPvm,
          loppuPaiva = o.loppuPvm,
          henkiloOid = oppijanumero.get,
          komo = komoOid,
          myontaja = oppilaitosOid,
          source = CSC
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
          suoritusKieli = t.kieli,
          source = CSC
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

  import akka.pattern.ask
  val CSC = "1.2.246.562.10.2013112012294919827487"
  val tuntematon = "1.2.246.562.10.57118763579"

  def resolveOppilaitosOid(oppilaitosnumero: String): Future[String] = oppilaitosnumero match {
    case o if Seq("XX", "UK", "UM").contains(o) => Future.successful(tuntematon)
    case o =>
      (organisaatioActor ? o)(20.seconds).mapTo[Option[Organisaatio]].map(_ match {
        case Some(org) => org.oid
        case _ => log.error(s"oppilaitos not found with oppilaitosnumero $o"); throw OppilaitosNotFoundException(s"oppilaitos not found with oppilaitosnumero $o")
      })
  }

  def resolveKomoOid(koulutuskoodi: String): Future[String] = {
    /*(tarjontaActor ? SearchKomoQuery(koulutuskoodi))(10.seconds).mapTo[Seq[Komo]].map(_.headOption match {
      case Some(komo) => komo.oid
      case _ => throw KomoNotFoundException(s"komo not found with koulutuskoodi $koulutuskoodi") // FIXME should fallback to saving koulutuskoodi instead
    })*/
    Future.successful(s"koulutus_$koulutuskoodi")
  }
}
