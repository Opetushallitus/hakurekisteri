package fi.vm.sade.hakurekisteri.integration.virta

import akka.actor.{ActorLogging, Actor, ActorRef}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus, yksilollistaminen}
import org.joda.time.LocalDate

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class VirtaQuery(oppijanumero: String, hetu: Option[String])

case class KomoNotFoundException(message: String) extends Exception(message)

case class VirtaData(opiskeluOikeudet: Seq[Opiskeluoikeus], suoritukset: Seq[Suoritus])

class VirtaActor(virtaClient: VirtaClient, organisaatioActor: ActorRef) extends Actor with ActorLogging {
  implicit val executionContext: ExecutionContext = context.dispatcher

  def receive: Receive = {
    case VirtaQuery(o, h) => convertVirtaResult(getOpiskelijanTiedot(o, h))(o) pipeTo sender
  }

  def getOpiskelijanTiedot(oppijanumero: String, hetu: Option[String]): Future[Option[VirtaResult]] =
    virtaClient.getOpiskelijanTiedot(oppijanumero = oppijanumero, hetu = hetu)

  def getKoulutusUri(koulutuskoodi: Option[String]): String =
    s"koulutus_${resolveKoulutusKoodiOrUnknown(koulutuskoodi)}"

  def resolveKoulutusKoodiOrUnknown(koulutuskoodi: Option[String]): String = {
    val tuntematon = "999999"
    koulutuskoodi.getOrElse(tuntematon)
  }

  def opiskeluoikeus(oppijanumero: String)(o: VirtaOpiskeluoikeus): Future[Opiskeluoikeus] =
    for (
      oppilaitosOid <- resolveOppilaitosOid(o.myontaja)
    ) yield Opiskeluoikeus(
          alkuPaiva = o.alkuPvm,
          loppuPaiva = o.loppuPvm,
          henkiloOid = oppijanumero,
          komo = getKoulutusUri(o.koulutuskoodit.headOption),
          myontaja = oppilaitosOid,
          source = Virta.CSC)

  def tutkinto(oppijanumero: String)(t: VirtaTutkinto): Future[Suoritus] =
    for (
      oppilaitosOid <- resolveOppilaitosOid(t.myontaja)
    ) yield VirallinenSuoritus(
          komo = getKoulutusUri(t.koulutuskoodi),
          myontaja = oppilaitosOid,
          valmistuminen = t.suoritusPvm,
          tila = tila(t.suoritusPvm),
          henkilo = oppijanumero,
          yksilollistaminen = yksilollistaminen.Ei,
          suoritusKieli = t.kieli,
          vahv = true,
          lahde = Virta.CSC)

  def tila(valmistuminen: LocalDate): String = valmistuminen match {
    case v: LocalDate if v.isBefore(new LocalDate()) => "VALMIS"
    case _ => "KESKEN"
  }

  def convertVirtaResult(f: Future[Option[VirtaResult]])(oppijanumero: String): Future[VirtaData] = f.flatMap {
    case None => Future.successful(VirtaData(Seq(), Seq()))
    case Some(r) =>
      val opiskeluoikeudet: Future[Seq[Opiskeluoikeus]] = Future.sequence(r.opiskeluoikeudet.map(opiskeluoikeus(oppijanumero)))
      val suoritukset: Future[Seq[Suoritus]] = Future.sequence(r.tutkinnot.map(tutkinto(oppijanumero)))
      for {
        o <- opiskeluoikeudet
        s <- suoritukset
      } yield VirtaData(o, s)
  }

  import akka.pattern.ask
  val tuntematon = "1.2.246.562.10.57118763579"

  def resolveOppilaitosOid(oppilaitosnumero: String): Future[String] = oppilaitosnumero match {
    case o if Seq("XX", "UK", "UM").contains(o) => Future.successful(tuntematon)
    case o =>
      (organisaatioActor ? o)(30.seconds).mapTo[Option[Organisaatio]] map {
          case Some(org) => org.oid
          case _ => log.error(s"oppilaitos not found with oppilaitosnumero $o"); throw OppilaitosNotFoundException(s"oppilaitos not found with oppilaitosnumero $o")
      }
  }
}

object Virta {
  val CSC = "1.2.246.562.10.2013112012294919827487"
}