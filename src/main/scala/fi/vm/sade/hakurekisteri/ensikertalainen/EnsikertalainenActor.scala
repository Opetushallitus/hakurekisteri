package fi.vm.sade.hakurekisteri.ensikertalainen


import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.dates.Ajanjakso
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.integration.hakemus.{AtaruHakemus, FullHakemus, HakemusAnswers, HakijaHakemus, IHakemusService}
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, Haku}
import fi.vm.sade.hakurekisteri.integration.henkilo.{IOppijaNumeroRekisteri, PersonOidsWithAliases}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{GetKomoQuery, KomoResponse, TarjontaActorRef}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{EnsimmainenVastaanotto, ValintarekisteriActorRef, ValintarekisteriQuery}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusHenkilotQuery}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusHenkilotQuery, VirallinenSuoritus}
import org.joda.time.{DateTime, DateTimeZone, ReadableInstant}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

case class EnsikertalainenQuery(henkiloOids: Set[String],
                                hakuOid: String,
                                hakukohdeOid: Option[String] = None,
                                suoritukset: Option[Seq[Suoritus]] = None,
                                opiskeluoikeudet: Option[Seq[Opiskeluoikeus]] = None)

case class HaunEnsikertalaisetQuery(hakuOid: String)

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

case class Ensikertalainen(henkiloOid: String, menettamisenPeruste: Option[MenettamisenPeruste]){
  val ensikertalainen = menettamisenPeruste.isEmpty
}

class EnsikertalainenActor(suoritusActor: ActorRef,
                           opiskeluoikeusActor: ActorRef,
                           valintarekisterActor: ValintarekisteriActorRef,
                           tarjontaActor: TarjontaActorRef,
                           hakuActor: ActorRef,
                           hakemusService: IHakemusService,
                           oppijaNumeroRekisteri: IOppijaNumeroRekisteri,
                           config: Config) extends Actor with ActorLogging {

  val syksy2014 = "2014S"
  val Oid = "(1\\.2\\.246\\.562\\.[0-9.]+)".r
  val KkKoulutusUri = "koulutus_[67][1-9][0-9]{4}".r
  val koulutuksenAlkaminenSyksy2014 = new DateTime(2014, 8, 1, 0, 0, 0, 0, DateTimeZone.forID("Europe/Helsinki"))
  val sizeLimitForFetchingByPersons = 100
  val resourceQuerySize = 5000

  implicit val defaultTimeout: Timeout = 15.minutes
  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(8, getClass.getSimpleName)

  log.info(s"started ensikertalaisuus actor: $self")

  override def receive: Receive = {
    case q: EnsikertalainenQuery =>
      laskeEnsikertalaisuudet(q) pipeTo sender
    case HaunEnsikertalaisetQuery(hakuOid) =>
      haunEnsikertalaiset(hakuOid) pipeTo sender
    case QueryCount =>
      sender ! QueriesRunning(Map[String, Int]())
  }

  private def haunEnsikertalaiset(hakuOid: String): Future[Seq[Ensikertalainen]] = for {
    haku <- (hakuActor ? GetHaku(hakuOid)).mapTo[Haku]
    tutkintovuodetHakemuksilta <- tutkinnotHakemuksilta(hakuOid)
    personOidsWithAliases <- oppijaNumeroRekisteri.enrichWithAliases(tutkintovuodetHakemuksilta.keySet)
    valmistumishetket <- valmistumiset(personOidsWithAliases, Seq())
    opiskeluoikeuksienAlkamiset <- opiskeluoikeudetAlkaneet(personOidsWithAliases, Seq())
    vastaanottohetket <- vastaanotot(personOidsWithAliases)
  } yield {
    tutkintovuodetHakemuksilta.map {
      case (henkiloOid, tutkintovuosi) =>
        ensikertalaisuus(
          henkiloOid,
          haku.viimeinenHakuaikaPaattyy.getOrElse(throw new IllegalArgumentException(s"haku $hakuOid is missing hakuajan päätös")),
          valmistumishetket.get(henkiloOid),
          opiskeluoikeuksienAlkamiset.get(henkiloOid),
          vastaanottohetket.get(henkiloOid),
          tutkintovuosi
        )
    }.toSeq
  }

  def mergeEnsikertalaisuus(henkiloOid:String, allEnsikertalainen: Set[Ensikertalainen]): Ensikertalainen = {
    val mikaVainMenettamisenPeruste = allEnsikertalainen.flatMap(_.menettamisenPeruste).headOption
    Ensikertalainen(henkiloOid, mikaVainMenettamisenPeruste)
  }

  private def laskeEnsikertalaisuudet(q: EnsikertalainenQuery): Future[Seq[Ensikertalainen]] = {
    for {
      haku <- (hakuActor ? GetHaku(q.hakuOid)).mapTo[Haku]
      personOidsWithAliases <- oppijaNumeroRekisteri.enrichWithAliases(q.henkiloOids)
      tutkintovuodetHakemuksilta <- tutkinnotHakemuksilta(personOidsWithAliases.henkiloOidsWithLinkedOids, q.hakuOid, q.hakukohdeOid)
      valmistumishetket <- valmistumiset(
        personOidsWithAliases,
        q.suoritukset.getOrElse(Seq())
      )
      opiskeluoikeuksienAlkamiset <- opiskeluoikeudetAlkaneet(
        personOidsWithAliases,
        q.opiskeluoikeudet.getOrElse(Seq())
      )
      vastaanottohetket <- vastaanotot(
        personOidsWithAliases
      )
    } yield {
      q.henkiloOids.toSeq.flatMap(henkilo => {
        personOidsWithAliases.aliasesByPersonOids.get(henkilo).map(links => {
          mergeEnsikertalaisuus(henkilo, links.map(linkedOid => {
            ensikertalaisuus(
              henkilo,
              haku.viimeinenHakuaikaPaattyy.getOrElse(throw new IllegalArgumentException(s"haku ${q.hakuOid} is missing hakuajan päätös")),
              valmistumishetket.get(linkedOid),
              opiskeluoikeuksienAlkamiset.get(linkedOid),
              vastaanottohetket.get(linkedOid),
              tutkintovuodetHakemuksilta.get(linkedOid)
            )
          }))
        })
      })
    }
  }

  private def tutkinnotHakemuksilta(hakuOid: String): Future[Map[String, Option[Int]]] = {
    hakemusService.suoritusoikeudenTaiAiemmanTutkinnonVuosi(hakuOid, None).map(findCasesWithSuoritusoikeusTaiAiempiTutkinto(_).toMap)
  }

  private def tutkinnotHakemuksilta(henkiloOids: Set[String],
                                    hakuOid: String,
                                    hakukohdeOid: Option[String]): Future[Map[String, Int]] = {
    val hakemukset = {
      if (henkiloOids.size <= sizeLimitForFetchingByPersons || hakukohdeOid.isEmpty) {
        hakemusService.hakemuksetForPersonsInHaku(henkiloOids, hakuOid)
      } else {
        hakemusService.suoritusoikeudenTaiAiemmanTutkinnonVuosi(hakuOid, hakukohdeOid)
      }
    }

    hakemukset.map(findCasesWithSuoritusoikeusTaiAiempiTutkinto(_).collect {
      case (personOid, Some(year)) => (personOid, year)
    }.toMap)
  }

  private def findCasesWithSuoritusoikeusTaiAiempiTutkinto: Seq[HakijaHakemus] => Seq[(String, Option[Int])] = { hakemukset =>
    hakemukset.collect {
      case FullHakemus(_, Some(personOid), _, Some(HakemusAnswers(_, Some(koulutustausta), _, _, _)), _, _, _)
        if koulutustausta.suoritusoikeus_tai_aiempi_tutkinto.contains("true") &&
          koulutustausta.suoritusoikeus_tai_aiempi_tutkinto_vuosi.isDefined =>
        (personOid, Some(koulutustausta.suoritusoikeus_tai_aiempi_tutkinto_vuosi.get.toInt))
      case FullHakemus(_, Some(personOid), _, _, _, _, _) =>
        (personOid, None)
      case h: AtaruHakemus =>
        (h.henkilo.oidHenkilo, h.korkeakoulututkintoVuosi)
    }
  }

  private def vastaanotot(personOidsWithAliases: PersonOidsWithAliases): Future[Map[String, DateTime]] =
    if (personOidsWithAliases.isEmpty) {
      Future.successful(Map())
    } else {
      (valintarekisterActor.actor ? ValintarekisteriQuery(personOidsWithAliases, syksy2014)).mapTo[Seq[EnsimmainenVastaanotto]]
        .map(_.collect {
          case EnsimmainenVastaanotto(oid, Some(date)) => (oid, date)
        }.toMap)
    }

  private def valmistumiset(personOidsWithAliases: PersonOidsWithAliases,
                            prefetchedSuoritukset: Seq[Suoritus]): Future[Map[String, DateTime]] =
    suoritukset(personOidsWithAliases).map(_ ++ prefetchedSuoritukset)
      .flatMap(kkSuoritukset)
      .map(_.groupBy(_.henkiloOid)
        .mapValues(aikaisinValmistuminen)
        .collect {
          case (henkiloOid, Some(date)) => (henkiloOid, date)
        }
      )

  private def opiskeluoikeudetAlkaneet(personOidsWithAliases: PersonOidsWithAliases,
                                       prefetchedOpiskeluoikeudet: Seq[Opiskeluoikeus]): Future[Map[String, DateTime]] =
    opiskeluoikeudet(personOidsWithAliases).map(fetchedOpiskeluoikeudet => {
      (fetchedOpiskeluoikeudet ++ prefetchedOpiskeluoikeudet)
        .groupBy(_.henkiloOid)
        .mapValues(aikaisinMerkitsevaOpiskeluoikeus)
        .collect {
          case (henkiloOid, Some(date)) => (henkiloOid, date)
        }
    })

  private def opiskeluoikeudet(personOidsWithAliases: PersonOidsWithAliases): Future[Seq[Opiskeluoikeus]] = Future.sequence(
    personOidsWithAliases
      .grouped(resourceQuerySize)
      .map(oidsWithAliases => (opiskeluoikeusActor ? OpiskeluoikeusHenkilotQuery(oidsWithAliases)).mapTo[Seq[Opiskeluoikeus]])
  ).map(_.flatten.toSeq)

  private def suoritukset(personOidsWithAliases: PersonOidsWithAliases): Future[Seq[Suoritus]] = Future.sequence(
    personOidsWithAliases
      .grouped(resourceQuerySize)
      .map(oidsWithAliases => (suoritusActor ? SuoritusHenkilotQuery(oidsWithAliases)).mapTo[Seq[Suoritus]])
  ).map(_.flatten.toSeq)

  private def isKkTutkinto(suoritus: Suoritus): Future[Option[Suoritus]] = suoritus match {
    case s: VirallinenSuoritus => s.komo match {
      case KkKoulutusUri() => Future.successful(Some(suoritus))
      case Oid(komo) =>
        (tarjontaActor.actor ? GetKomoQuery(komo)).mapTo[KomoResponse].flatMap(_.komo match {
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
      case VirallinenSuoritus(_, _, "VALMIS", valmistuminen, _, _, _, _, _, _, _, _) => valmistuminen.toDateTimeAtStartOfDay
    }
    if (valmistumishetket.isEmpty) None else Some(valmistumishetket.minBy(_.getMillis))
  }

  private def aikaisinMerkitsevaOpiskeluoikeus(suoritukset: Seq[Opiskeluoikeus]): Option[DateTime] = {
    def greaterOrEqualToSyksy2014(instant: ReadableInstant): Boolean = {
      !instant.isBefore(koulutuksenAlkaminenSyksy2014)
    }
    val alkamiset = suoritukset.collect {
      case Opiskeluoikeus(Ajanjakso(alku, _), _, _, _, _) if greaterOrEqualToSyksy2014(alku) =>
        alku.asInstanceOf[DateTime]
    }
    if (alkamiset.isEmpty) None else Some(alkamiset.minBy(_.getMillis))
  }

  def ensikertalaisuus(henkilo: String,
                       leikkuripaiva: DateTime,
                       valmistuminen: Option[DateTime],
                       opiskeluoikeusAlkanut: Option[DateTime],
                       vastaanotto: Option[DateTime],
                       suorittanutTutkinnonHakemukselta: Option[Int]): Ensikertalainen = {
    (valmistuminen, opiskeluoikeusAlkanut, vastaanotto, suorittanutTutkinnonHakemukselta) match {
      case (Some(tutkintopaiva), _, _, _) if tutkintopaiva.isBefore(leikkuripaiva) =>
        Ensikertalainen(henkilo, Some(SuoritettuKkTutkinto(tutkintopaiva)))
      case (_, Some(opiskeluoikeusAlkupvm), _, _) if opiskeluoikeusAlkupvm.isBefore(leikkuripaiva) =>
        Ensikertalainen(henkilo, Some(OpiskeluoikeusAlkanut(opiskeluoikeusAlkupvm)))
      case (_, _, Some(vastaanottopaiva), _) if vastaanottopaiva.isBefore(leikkuripaiva) =>
        Ensikertalainen(henkilo, Some(KkVastaanotto(vastaanottopaiva)))
      case (_, _, _, Some(vuosi)) =>
        Ensikertalainen(henkilo, Some(SuoritettuKkTutkintoHakemukselta(vuosi)))
      case _ =>
        Ensikertalainen(henkilo, None)
    }
  }
}


