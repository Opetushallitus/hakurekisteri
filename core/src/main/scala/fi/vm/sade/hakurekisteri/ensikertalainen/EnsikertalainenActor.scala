package fi.vm.sade.hakurekisteri.ensikertalainen


import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.tarjonta.{GetKomoQuery, KomoResponse}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{EnsimmainenVastaanotto, ValintarekisteriQuery}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
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

case class Ensikertalainen(henkiloOid: String, ensikertalainen: Boolean, menettamisenPeruste: Option[MenettamisenPeruste])

class EnsikertalainenActor(suoritusActor: ActorRef, valintarekisterActor: ActorRef, tarjontaActor: ActorRef, config: Config)
                          (implicit val ec: ExecutionContext) extends Actor with ActorLogging {

  val syksy2014 = "2014S"
  val Oid = "(1\\.2\\.246\\.562\\.[0-9.]+)".r
  val KkKoulutusUri = "koulutus_[67][1-9][0-9]{4}".r

  implicit val defaultTimeout: Timeout = 2.minutes

  log.info(s"started ensikertalaisuus actor: $self")

  override def receive: Receive = {
    case q: EnsikertalainenQuery =>
      val ensikertalaisuudet: Future[Seq[Ensikertalainen]] = for {
        valmistumishetket: Map[String, DateTime] <- valmistumiset(q)
        vastaanottohetket: Map[String, DateTime] <- vastaanotot(q)(valmistumishetket.keySet)
      } yield for (
        henkilo <- q.henkiloOids.toSeq
      ) yield ensikertalaisuus(
        henkilo,
        q.paivamaara.getOrElse(DateTime.now()),
        valmistumishetket.get(henkilo),
        vastaanottohetket.get(henkilo)
      )
      ensikertalaisuudet pipeTo sender
    case QueryCount =>
      sender ! QueriesRunning(Map[String, Int]())
  }

  private def vastaanotot(q: EnsikertalainenQuery)(valmistuneet: Set[String]): Future[Map[String, DateTime]] =
    (q.henkiloOids.diff(valmistuneet) match {
      case kysyttavat if kysyttavat.isEmpty => Future.successful(Seq[EnsimmainenVastaanotto]())
      case kysyttavat => getKkVastaanotonHetket(kysyttavat)
    }).map(_.collect {
      case EnsimmainenVastaanotto(oid, Some(date)) => (oid, date)
    }.toMap)

  private def valmistumiset(q: EnsikertalainenQuery): Future[Map[String, DateTime]] = q.suoritukset
    .map(Future.successful)
    .getOrElse(suoritukset(q.henkiloOids))
    .flatMap(kkSuoritukset)
    .map(_.groupBy(_.henkiloOid))
    .map(_.mapValues(aikaisinValmistuminen))
    .map(_.collect {
      case (key, Some(date)) => (key, date)
    })

  private def suoritukset(henkiloOids: Set[String]): Future[Seq[Suoritus]] =
    (suoritusActor ? SuoritusHenkilotQuery(henkilot = henkiloOids)).mapTo[Seq[Suoritus]]

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

  private def kkSuoritukset(suoritukset: Seq[Suoritus]): Future[Seq[Suoritus]] =
    Future.sequence(suoritukset.map(isKkTutkinto)).map(_.flatten)

  private def aikaisinValmistuminen(suoritukset: Seq[Suoritus]): Option[DateTime] = {
    val valmistumishetket = suoritukset.collect {
      case VirallinenSuoritus(_, _, "VALMIS", valmistuminen, _, _, _, _, _, _) => valmistuminen.toDateTimeAtStartOfDay
    }
    if (valmistumishetket.isEmpty) None else Some(valmistumishetket.minBy(_.getMillis))
  }

  private def getKkVastaanotonHetket(henkiloOids: Set[String]): Future[Seq[EnsimmainenVastaanotto]] = {
    (valintarekisterActor ? ValintarekisteriQuery(henkiloOids, syksy2014)).mapTo[Seq[EnsimmainenVastaanotto]]
  }

  def ensikertalaisuus(henkilo: String,
                       leikkuripaiva: DateTime,
                       valmistuminen: Option[DateTime],
                       vastaanotto: Option[DateTime]): Ensikertalainen = (valmistuminen, vastaanotto) match {
    case (Some(tutkintopaiva), _) if tutkintopaiva.isBefore(leikkuripaiva) =>
      Ensikertalainen(henkilo, ensikertalainen = false, Some(SuoritettuKkTutkinto(tutkintopaiva)))
    case (_, Some(vastaanottopaiva)) if vastaanottopaiva.isBefore(leikkuripaiva) =>
      Ensikertalainen(henkilo, ensikertalainen = false, Some(KkVastaanotto(vastaanottopaiva)))
    case _ =>
      Ensikertalainen(henkilo, ensikertalainen = true, None)
  }

}


