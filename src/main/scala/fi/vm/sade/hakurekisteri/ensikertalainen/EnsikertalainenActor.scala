package fi.vm.sade.hakurekisteri.ensikertalainen


import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.dates.Ajanjakso
import fi.vm.sade.hakurekisteri.integration.hakemus.{HakemusActor, HakemusService, FullHakemus, HakemusQuery}
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, Haku}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{GetKomoQuery, KomoResponse}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{EnsimmainenVastaanotto, ValintarekisteriQuery}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusHenkilotQuery}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusHenkilotQuery, VirallinenSuoritus}
import org.joda.time.{DateTime, DateTimeZone, ReadableInstant}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

case class EnsikertalainenQuery(henkiloOids: Set[String],
                                hakuOid: String,
                                suoritukset: Option[Seq[Suoritus]] = None,
                                opiskeluoikeudet: Option[Seq[Opiskeluoikeus]] = None)

object QueryCount

case class QueriesRunning(count: Map[String, Int], timestamp: Long = Platform.currentTime)

sealed trait MenettamisenPeruste {
  val peruste: String
}

case class KkVastaanotto(paivamaara: DateTime) extends MenettamisenPeruste {
  override val peruste: String = "KkVastaanotto"
}

case class SuoritettuKkTutkinto(paivamaara: DateTime) extends MenettamisenPeruste {
  override val peruste: String = "SuoritettuKkTutkinto"
}

case class OpiskeluoikeusAlkanut(paivamaara: DateTime) extends MenettamisenPeruste {
  override val peruste: String = "OpiskeluoikeusAlkanut"
}

case class SuoritettuKkTutkintoHakemukselta(vuosi: Int) extends MenettamisenPeruste {
  override val peruste: String = "SuoritettuKkTutkintoHakemukselta"
}

case class Ensikertalainen(henkiloOid: String, ensikertalainen: Boolean, menettamisenPeruste: Option[MenettamisenPeruste])

class EnsikertalainenActor(suoritusActor: ActorRef,
                           opiskeluoikeusActor: ActorRef,
                           valintarekisterActor: ActorRef,
                           tarjontaActor: ActorRef,
                           hakuActor: ActorRef,
                           hakemusService: HakemusService,
                           config: Config)(implicit val ec: ExecutionContext) extends Actor with ActorLogging {

  val syksy2014 = "2014S"
  val Oid = "(1\\.2\\.246\\.562\\.[0-9.]+)".r
  val KkKoulutusUri = "koulutus_[67][1-9][0-9]{4}".r
  val koulutuksenAlkaminenSyksy2014 = new DateTime(2014, 8, 1, 0, 0, 0, 0, DateTimeZone.forID("Europe/Helsinki"))
  val resourceQuerySize = 5000

  implicit val defaultTimeout: Timeout = 15.minutes

  log.info(s"started ensikertalaisuus actor: $self")

  override def receive: Receive = {
    case q: EnsikertalainenQuery =>
      laskeEnsikertalaisuudet(q) pipeTo sender
    case QueryCount =>
      sender ! QueriesRunning(Map[String, Int]())
  }

  def loytyyTutkintovuosiHakemukselta(tutkintovuodetHakemuksilta: Map[String, Option[Int]]): Set[String] =
    tutkintovuodetHakemuksilta.filter(_._2.isDefined).keySet

  private def laskeEnsikertalaisuudet(q: EnsikertalainenQuery): Future[Seq[Ensikertalainen]] = for {
    haku <- (hakuActor ? GetHaku(q.hakuOid)).mapTo[Haku]
    tutkintovuodetHakemuksilta <- tutkinnotHakemuksilta(q)
    valmistumishetket <- valmistumiset(q)(loytyyTutkintovuosiHakemukselta(tutkintovuodetHakemuksilta))
    opiskeluoikeuksienAlkamiset <- opiskeluoikeudetAlkaneet(q)(
      loytyyTutkintovuosiHakemukselta(tutkintovuodetHakemuksilta) ++ valmistumishetket.keySet
    )
    vastaanottohetket <- vastaanotot(q)(
      loytyyTutkintovuosiHakemukselta(tutkintovuodetHakemuksilta) ++ valmistumishetket.keySet ++ opiskeluoikeuksienAlkamiset.keySet
    )
  } yield {
    q.henkiloOids.toSeq.map(henkilo => {
      val vuosi = tutkintovuodetHakemuksilta.getOrElse(henkilo, None)
      ensikertalaisuus(
        henkilo,
        haku.viimeinenHakuaikaPaattyy.getOrElse(throw new IllegalArgumentException(s"haku ${q.hakuOid} is missing hakuajan päätös")),
        valmistumishetket.get(henkilo),
        opiskeluoikeuksienAlkamiset.get(henkilo),
        vastaanottohetket.get(henkilo),
        vuosi
      )
    })
  }

  private def tutkinnotHakemuksilta(q: EnsikertalainenQuery): Future[Map[String, Option[Int]]] = {
    hakemusService.hakemuksetForHaku(q.hakuOid).map(_
      .filter(_.personOid.isDefined)
      .groupBy(_.personOid.get)
      .mapValues(_
        .filter(_.answers.exists(_.koulutustausta.exists(_.suoritusoikeus_tai_aiempi_tutkinto.contains("true"))))
        .flatMap(_.answers.flatMap(_.koulutustausta.flatMap(_.suoritusoikeus_tai_aiempi_tutkinto_vuosi.map(_.toInt))))
        .sorted
        .headOption
      )
    )
  }

  private def vastaanotot(q: EnsikertalainenQuery)(joSelvitetyt: Set[String]): Future[Map[String, DateTime]] =
    (q.henkiloOids.diff(joSelvitetyt) match {
      case kysyttavat if kysyttavat.isEmpty => Future.successful(Seq[EnsimmainenVastaanotto]())
      case kysyttavat => getKkVastaanotonHetket(kysyttavat)
    }).map(_.collect {
      case EnsimmainenVastaanotto(oid, Some(date)) => (oid, date)
    }.toMap)

  private def valmistumiset(q: EnsikertalainenQuery)(joSelvitetyt: Set[String]): Future[Map[String, DateTime]] =
    q.henkiloOids.diff(joSelvitetyt) match {
      case kysyttavat if kysyttavat.isEmpty => Future.successful(Map[String, DateTime]())
      case kysyttavat => q.suoritukset
        .map(Future.successful)
        .getOrElse(suoritukset(kysyttavat))
        .flatMap(kkSuoritukset)
        .map(_.groupBy(_.henkiloOid))
        .map(_.mapValues(aikaisinValmistuminen))
        .map(_.collect {
          case (key, Some(date)) => (key, date)
        })
    }

  private def opiskeluoikeudetAlkaneet(q: EnsikertalainenQuery)(joSelvitetyt: Set[String]): Future[Map[String, DateTime]] = {
    q.henkiloOids.diff(joSelvitetyt) match {
      case kysyttavat if kysyttavat.isEmpty => Future.successful(Map[String, DateTime]())
      case kysyttavat => q.opiskeluoikeudet
          .map(Future.successful)
          .getOrElse(opiskeluoikeudet(kysyttavat))
          .map(_.groupBy(_.henkiloOid))
          .map(_.mapValues(aikaisinMerkitsevaOpiskeluoikeus))
          .map(_.collect {
            case (key, Some(date)) => (key, date)
          })
    }
  }

  private def opiskeluoikeudet(henkiloOids: Set[String]): Future[Seq[Opiskeluoikeus]] = Future.sequence(
    henkiloOids
      .grouped(resourceQuerySize)
      .map(oids => (opiskeluoikeusActor ? OpiskeluoikeusHenkilotQuery(henkilot = oids)).mapTo[Seq[Opiskeluoikeus]])
  ).map(_.flatten.toSeq)

  private def suoritukset(henkiloOids: Set[String]): Future[Seq[Suoritus]] = Future.sequence(
    henkiloOids
      .grouped(resourceQuerySize)
      .map(oids => (suoritusActor ? SuoritusHenkilotQuery(henkilot = oids)).mapTo[Seq[Suoritus]])
  ).map(_.flatten.toSeq)

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

  private def aikaisinMerkitsevaOpiskeluoikeus(suoritukset: Seq[Opiskeluoikeus]): Option[DateTime] = {
    def greaterOrEqualToSyksy2014(instant: ReadableInstant): Boolean =
      instant.asInstanceOf[DateTime] == koulutuksenAlkaminenSyksy2014 || instant.asInstanceOf[DateTime].isAfter(koulutuksenAlkaminenSyksy2014)
    val alkamiset = suoritukset.collect {
      case Opiskeluoikeus(Ajanjakso(alku, _), _, _, _, _) if alku.isInstanceOf[DateTime] && greaterOrEqualToSyksy2014(alku) =>
        alku.asInstanceOf[DateTime]
    }
    if (alkamiset.isEmpty) None else Some(alkamiset.minBy(_.getMillis))
  }

  private def getKkVastaanotonHetket(henkiloOids: Set[String]): Future[Seq[EnsimmainenVastaanotto]] = {
    (valintarekisterActor ? ValintarekisteriQuery(henkiloOids, syksy2014)).mapTo[Seq[EnsimmainenVastaanotto]]
  }

  def ensikertalaisuus(henkilo: String,
                       leikkuripaiva: DateTime,
                       valmistuminen: Option[DateTime],
                       opiskeluoikeusAlkanut: Option[DateTime],
                       vastaanotto: Option[DateTime],
                       suorittanutTutkinnonHakemukselta: Option[Int]): Ensikertalainen = (valmistuminen, opiskeluoikeusAlkanut, vastaanotto, suorittanutTutkinnonHakemukselta) match {
    case (Some(tutkintopaiva), _, _, _) if tutkintopaiva.isBefore(leikkuripaiva) =>
      Ensikertalainen(henkilo, ensikertalainen = false, Some(SuoritettuKkTutkinto(tutkintopaiva)))
    case (_, Some(opiskeluoikeusAlkupvm), _, _) if opiskeluoikeusAlkupvm.isBefore(leikkuripaiva) =>
      Ensikertalainen(henkilo, ensikertalainen = false, Some(OpiskeluoikeusAlkanut(opiskeluoikeusAlkupvm)))
    case (_, _, Some(vastaanottopaiva), _) if vastaanottopaiva.isBefore(leikkuripaiva) =>
      Ensikertalainen(henkilo, ensikertalainen = false, Some(KkVastaanotto(vastaanottopaiva)))
    case (_, _, _, Some(vuosi)) =>
      Ensikertalainen(henkilo, ensikertalainen = false, Some(SuoritettuKkTutkintoHakemukselta(vuosi)))
    case _ =>
      Ensikertalainen(henkilo, ensikertalainen = true, None)
  }

}


