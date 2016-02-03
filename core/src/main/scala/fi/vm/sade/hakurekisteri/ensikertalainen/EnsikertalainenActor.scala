package fi.vm.sade.hakurekisteri.ensikertalainen


import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.tarjonta.{GetKomoQuery, KomoResponse}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{EnsimmainenVastaanotto, ValintarekisteriQuery}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusHenkilotQuery, Suoritus, VirallinenSuoritus}
import org.joda.time.{DateTime, LocalDate}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

case class EnsikertalainenQuery(henkiloOids: Set[String],
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

class EnsikertalainenActor(suoritusActor: ActorRef, valintarekisterActor: ActorRef, tarjontaActor: ActorRef, config: Config)
                          (implicit val ec: ExecutionContext) extends Actor with ActorLogging {

  val syksy2014 = "2014S"
  val Oid = "(1\\.2\\.246\\.562\\.[0-9.]+)".r
  val KkKoulutusUri = "koulutus_[67][1-9][0-9]{4}".r

  implicit val defaultTimeout: Timeout = 2.minutes

  log.info(s"started ensikertalaisuus actor: $self")

  override def receive: Receive = {
    case q: EnsikertalainenQuery =>
      val kaikkienKkSuoritukset = q.suoritukset.map(Future.successful).getOrElse(suoritukset(q.henkiloOids)).flatMap(kkSuoritukset).map(_.groupBy(_.henkiloOid))

      val ensimmaisetValmistumiset: Future[Map[String, Option[DateTime]]] = kaikkienKkSuoritukset.map(_.mapValues(aikaisinValmistuminen))

      val ensimmaisetVastaanotot: Future[Seq[EnsimmainenVastaanotto]] = ensimmaisetValmistumiset.map(e => {
        q.henkiloOids.diff(e.collect{
          case (oid, Some(_)) => oid
        }.toSet)
      }).flatMap {
        case s: Set[String] if s.isEmpty => Future.successful(Seq())
        case kysyttavat => getKkVastaanotonHetket(kysyttavat)
      }

      val result: Future[Set[Ensikertalainen]] = for (
        valmistumishetket: Map[String, Option[DateTime]] <- ensimmaisetValmistumiset;
        vastaanottohetket: Map[String, Option[DateTime]] <- ensimmaisetVastaanotot.map(_.map(v => (v.oid, v.paattyi)).toMap)
      ) yield for (
        henkilo <- q.henkiloOids
      ) yield ensikertalaisuus(q.paivamaara.getOrElse(LocalDate.now().toDateTimeAtStartOfDay), (valmistumishetket.getOrElse(henkilo, None), vastaanottohetket.getOrElse(henkilo, None)))

      result.map(_.toSeq) pipeTo sender
    case QueryCount =>
      sender ! QueriesRunning(Map[String, Int]())
  }

  private def suoritukset(henkiloOids: Set[String]): Future[Seq[Suoritus]] = {
    (suoritusActor ? SuoritusHenkilotQuery(henkilot = henkiloOids)).mapTo[Seq[Suoritus]]
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

  private def getKkVastaanotonHetket(henkiloOids: Set[String]): Future[Seq[EnsimmainenVastaanotto]] = {
    (valintarekisterActor ? ValintarekisteriQuery(henkiloOids, syksy2014)).mapTo[Seq[EnsimmainenVastaanotto]]
  }

  def ensikertalaisuus(leikkuripaiva: DateTime, t: (Option[DateTime], Option[DateTime])): Ensikertalainen = t match {
    case (Some(tutkintopaiva), _) if tutkintopaiva.isBefore(leikkuripaiva) =>
      Ensikertalainen(ensikertalainen = false, Some(SuoritettuKkTutkinto(tutkintopaiva)))
    case (_, Some(vastaanottopaiva)) if vastaanottopaiva.isBefore(leikkuripaiva) =>
      Ensikertalainen(ensikertalainen = false, Some(KkVastaanotto(vastaanottopaiva)))
    case _ =>
      Ensikertalainen(ensikertalainen = true, None)
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


