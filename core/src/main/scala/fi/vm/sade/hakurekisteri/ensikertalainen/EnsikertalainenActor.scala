package fi.vm.sade.hakurekisteri.ensikertalainen


import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.tarjonta.{GetKomoQuery, KomoResponse}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.ValintarekisteriQuery
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery, VirallinenSuoritus}
import org.joda.time.{DateTime, DateTimeZone, LocalDate}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

case class EnsikertalainenQuery(henkiloOid: String,
                                suoritukset: Option[Seq[Suoritus]] = None,
                                opiskeluoikeudet: Option[Seq[Opiskeluoikeus]] = None,
                                paivamaara: Option[DateTime] = None)

object QueryCount

case class QueriesRunning(count: Map[String, Int], timestamp: Long = Platform.currentTime)

sealed trait MenettamisenPeruste {
  val peruste: String
  val paivamaara: DateTime
}

case class KkVastaanotto(paivamaara: DateTime) extends MenettamisenPeruste {
  override val peruste: String = "KkVastaanotto"
}

case class SuoritettuKkTutkinto(paivamaara: DateTime) extends MenettamisenPeruste {
  override val peruste: String = "SuoritettuKkTutkinto"
}

case class Ensikertalainen(ensikertalainen: Boolean, menettamisenPeruste: Option[MenettamisenPeruste])

case class HetuNotFoundException(message: String) extends Exception(message)

class EnsikertalainenActor(suoritusActor: ActorRef, valintarekisterActor: ActorRef, tarjontaActor: ActorRef, config: Config)(implicit val ec: ExecutionContext) extends Actor with ActorLogging {

  val syksy2014: DateTime = new DateTime(2014, 8, 1, 0, 0, 0, 0, DateTimeZone.forID("Europe/Helsinki"))
  val Oid = "(1\\.2\\.246\\.562\\.[0-9.]+)".r
  val KkKoulutusUri = "koulutus_[67][1-9][0-9]{4}".r

  implicit val defaultTimeout: Timeout = 2.minutes

  log.info(s"started ensikertalaisuus actor: $self")

  override def receive: Receive = {
    case q: EnsikertalainenQuery =>
      (for (
        valmistumishetki <- suoritukset(q.henkiloOid).flatMap(kkSuoritukset).map(aikaisinValmistuminen);
        vastaanottohetki <- if (valmistumishetki.isEmpty) getKkVastaanotonHetki(q.henkiloOid) else Future.successful(None)
      ) yield ensikertalaisuus(q.paivamaara.getOrElse(new LocalDate().toDateTimeAtStartOfDay), (valmistumishetki, vastaanottohetki))).pipeTo(sender)
    case QueryCount =>
      sender ! QueriesRunning(Map[String, Int]())
  }

  private def suoritukset(henkiloOid: String): Future[Seq[Suoritus]] = {
    (suoritusActor ? SuoritusQuery(henkilo = Some(henkiloOid))).mapTo[Seq[Suoritus]]
  }

  private def isKkTutkinto(suoritus: Suoritus): Future[Option[Suoritus]] = suoritus match {
    case s: VirallinenSuoritus => s.komo match {
      case KkKoulutusUri() => Future.successful(Some(suoritus))
      case Oid(komo) =>
        (tarjontaActor ? GetKomoQuery(komo)).mapTo[KomoResponse].flatMap(_.komo match {
          case Some(k) if k.isKorkeakoulututkinto => Future.successful(Some(suoritus))
          case Some(_) => Future.successful(None)
          case None => Future.failed(new Exception(s"komo $komo not found"))
        })
      case _ => Future.successful(None)
    }
    case _ => Future.successful(None)
  }

  private def kkSuoritukset(suoritukset: Seq[Suoritus]): Future[Seq[Suoritus]] = {
    Future.sequence(suoritukset.map(isKkTutkinto)).map(_.collect {
      case Some(s) => s
    })
  }

  private def aikaisinValmistuminen(suoritukset: Seq[Suoritus]): Option[DateTime] = {
    val valmistumishetket = suoritukset.collect {
      case VirallinenSuoritus(_, _, "VALMIS", valmistuminen, _, _, _, _, _, _) => valmistuminen.toDateTimeAtStartOfDay
    }
    if (valmistumishetket.isEmpty) None else Some(valmistumishetket.minBy(_.getMillis))
  }

  private def getKkVastaanotonHetki(henkiloOid: String): Future[Option[DateTime]] = {
    (valintarekisterActor ? ValintarekisteriQuery(henkiloOid, syksy2014)).mapTo[Option[DateTime]]
  }

  def ensikertalaisuus(leikkuripaiva: DateTime, t: (Option[DateTime], Option[DateTime])): Ensikertalainen = t match {
    case (Some(tutkintopaiva), _) if tutkintopaiva.isBefore(leikkuripaiva) => Ensikertalainen(ensikertalainen = false, Some(SuoritettuKkTutkinto(tutkintopaiva)))
    case (_, Some(vastaanottopaiva)) if vastaanottopaiva.isBefore(leikkuripaiva) => Ensikertalainen(ensikertalainen = false, Some(KkVastaanotto(vastaanottopaiva)))
    case _ => Ensikertalainen(ensikertalainen = true, None)
  }

  class FetchResource[T, R](query: Query[T], wrapper: (Seq[T]) => R, receiver: ActorRef, resourceActor: ActorRef) extends Actor {
    override def preStart(): Unit = {
      resourceActor ! query
    }

    override def receive: Actor.Receive = {
      case s: Seq[T] =>
        receiver ! wrapper(s)
        context.stop(self)
    }
  }

}


