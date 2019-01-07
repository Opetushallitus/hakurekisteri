package fi.vm.sade.hakurekisteri.integration.virta

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorRef}
import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.integration.organisaatio.{Oppilaitos, OppilaitosResponse, OrganisaatioActorRef}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus, yksilollistaminen}
import org.joda.time.LocalDate
import support.TypedActorRef

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}


class VirtaActor(virtaClient: VirtaClient, organisaatioActor: OrganisaatioActorRef, suoritusActor: ActorRef, opiskeluoikeusActor: ActorRef) extends Actor with ActorLogging {
  implicit val executionContext: ExecutionContext = context.dispatcher

  import akka.pattern.pipe

  def receive: Receive = {
    case q: VirtaQuery =>
      val from = sender()
      val tiedot = getOpiskelijanTiedot(q.oppijanumero, q.hetu)
      tiedot.onComplete(t => from ! QueryProsessed(q))
      tiedot.pipeTo(self)(ActorRef.noSender)
      
    case Some(r: VirtaResult) => 
      save(r)
      
    case Failure(t: VirtaValidationError) => 
      log.warning(s"virta validation error: $t")
      
    case Failure(t: Throwable) => 
      log.error(t, "error occurred in virta query")
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
          source = Oids.cscOrganisaatioOid)

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
          lahde = Oids.cscOrganisaatioOid)

  def tila(valmistuminen: LocalDate): String = valmistuminen match {
    case v: LocalDate if v.isBefore(new LocalDate()) => "VALMIS"
    case _ => "KESKEN"
  }

  def save(r: VirtaResult): Unit = {
    val opiskeluoikeudet: Future[Seq[Opiskeluoikeus]] = Future.sequence(r.opiskeluoikeudet.map(opiskeluoikeus(r.oppijanumero)))
    val suoritukset: Future[Seq[Suoritus]] = Future.sequence(r.tutkinnot.map(tutkinto(r.oppijanumero)))
    for {
      o <- opiskeluoikeudet
      s <- suoritukset
    } yield {
      o.foreach(opiskeluoikeusActor ! _)
      s.foreach(suoritusActor ! _)
    }
  }

  import akka.pattern.ask

  def resolveOppilaitosOid(oppilaitosnumero: String): Future[String] = oppilaitosnumero match {
    case o if Seq("XX", "UK", "UM").contains(o) => Future.successful(Oids.tuntematonOrganisaatioOid)
    case o => (organisaatioActor.actor ? Oppilaitos(o))(1.hour).mapTo[OppilaitosResponse].map(_.oppilaitos.oid)
  }
}

case class VirtaActorRef(actor: ActorRef) extends TypedActorRef
