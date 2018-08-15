package fi.vm.sade.hakurekisteri.integration.virta

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.pattern.ask
import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.integration.organisaatio.{Oppilaitos, OppilaitosResponse, OrganisaatioActorRef}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusHenkilotQuery, OpiskeluoikeusQuery}
import fi.vm.sade.hakurekisteri.storage.{DeleteResource, Identified}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus, yksilollistaminen}
import org.joda.time.LocalDate
import support.TypedActorRef

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}


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

    implicit val timeout: Timeout = Timeout(1.minute)
    val oidsWithAliases = PersonOidsWithAliases(Set(r.oppijanumero), Map.empty)
    opiskeluoikeusActor ? OpiskeluoikeusHenkilotQuery(oidsWithAliases)
    val existingOpiskeluoikeudet: Future[Seq[Opiskeluoikeus with Identified[UUID]]] = (opiskeluoikeusActor ? OpiskeluoikeusQuery(Some(r.oppijanumero)))
      .mapTo[Seq[Opiskeluoikeus with Identified[UUID]]]

    val newOpiskeluOikeudet: Future[Seq[Opiskeluoikeus]] = Future.sequence(r.opiskeluoikeudet.map(opiskeluoikeus(r.oppijanumero)))
    val newSuoritukset: Future[Seq[Suoritus]] = Future.sequence(r.tutkinnot.map(tutkinto(r.oppijanumero)))

    val res = Await.result(existingOpiskeluoikeudet, Duration(1, TimeUnit.MINUTES))
    val virtaOpiskeluOikeus = res.filter(p => Oids.cscOrganisaatioOid.matches(p.source))

    val pendingDeletes: Seq[Future[Any]] = for {
      vrtOO <- virtaOpiskeluOikeus
    } yield {
      opiskeluoikeusActor ? DeleteResource(vrtOO.id, "virta-actor")
    }

    Future.sequence(pendingDeletes).recoverWith {
      case e: Exception =>
        log.error(e,"Error deleting old virta data")
        Future.failed(e)
    } onComplete { _ =>
      for {
        o <- newOpiskeluOikeudet
        s <- newSuoritukset
      } yield {
        o.foreach(opiskeluoikeusActor ! _)
        s.foreach(suoritusActor ! _)
      }
    }
  }

  import akka.pattern.ask

  def resolveOppilaitosOid(oppilaitosnumero: String): Future[String] = oppilaitosnumero match {
    case o if Seq("XX", "UK", "UM").contains(o) => Future.successful(Oids.tuntematonOrganisaatioOid)
    case o => (organisaatioActor.actor ? Oppilaitos(o))(1.hour).mapTo[OppilaitosResponse].map(_.oppilaitos.oid)
  }
}

case class VirtaActorRef(actor: ActorRef) extends TypedActorRef
