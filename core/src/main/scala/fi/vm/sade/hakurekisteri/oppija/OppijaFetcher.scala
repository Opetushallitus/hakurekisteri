package fi.vm.sade.hakurekisteri.oppija

import fi.vm.sade.hakurekisteri.rest.support.{Query, User, Registers}
import akka.actor.ActorRef
import org.joda.time.DateTime
import scala.concurrent.{Future, ExecutionContext}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusQuery}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusHenkilotQuery, SuoritusQuery, Suoritus}
import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID
import fi.vm.sade.hakurekisteri.organization.AuthorizedQuery
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaQuery}
import fi.vm.sade.hakurekisteri.ensikertalainen.{EnsikertalainenQuery, Ensikertalainen}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{OpiskeluoikeusHenkilotQuery, OpiskeluoikeusQuery, Opiskeluoikeus}
import fi.vm.sade.hakurekisteri.opiskelija.{OpiskelijaHenkilotQuery, OpiskelijaQuery, Opiskelija}
import akka.pattern.ask

trait OppijaFetcher {

  val rekisterit: Registers
  val hakemusRekisteri: ActorRef
  val ensikertalaisuus: ActorRef

  val singleSplitQuerySize = 5000

  protected implicit def executor: ExecutionContext
  implicit val defaultTimeout: Timeout

  def fetchOppijat(q: HakemusQuery, ensikertalaisuudenRajapvm: Option[DateTime])(implicit user: User): Future[Seq[Oppija]] =
    for (
      hakemukset <- (hakemusRekisteri ? q).mapTo[Seq[FullHakemus]];
      oppijat <- fetchOppijatFor(hakemukset, ensikertalaisuudenRajapvm)
    ) yield oppijat

  def fetchOppijatFor(hakemukset: Seq[FullHakemus], ensikertalaisuudenRajapvm: Option[DateTime])(implicit user: User): Future[Seq[Oppija]] = {
    val persons = extractPersons(hakemukset)
    fetchOppijat(persons, ensikertalaisuudenRajapvm)
  }

  def fetchOppijat(persons: Set[String], ensikertalaisuudenRajapvm: Option[DateTime])(implicit user: User): Future[Seq[Oppija]] = {
    enrichWithEnsikertalaisuus(getRekisteriData(persons), ensikertalaisuudenRajapvm)
  }

  def fetchOppija(person: String, ensikertalaisuudenRajapvm: Option[DateTime])(implicit user: User): Future[Oppija] = {
    fetchOppijat(Set(person), ensikertalaisuudenRajapvm).map(_.head)
  }

  private def extractPersons(hakemukset: Seq[FullHakemus]): Set[String] =
    (for (
      hakemus <- hakemukset
      if hakemus.personOid.isDefined && hakemus.stateValid
    ) yield hakemus.personOid.get).toSet

  private def enrichWithEnsikertalaisuus(rekisteriData: Future[Seq[Oppija]],
                                         ensikertalaisuudenRajapvm: Option[DateTime])(implicit user: User): Future[Seq[Oppija]] = {
    rekisteriData.flatMap(fetchEnsikertalaisuudet(ensikertalaisuudenRajapvm))
  }

  private def getRekisteriData(personOids: Set[String])(implicit user: User): Future[Seq[Oppija]] = {
    val oppijaData = for (
      suoritukset <- fetchSuoritukset(personOids);
      todistukset <- fetchTodistukset(suoritukset);
      opiskeluoikeudet <- fetchOpiskeluoikeudet(personOids);
      opiskelijat <- fetchOpiskelu(personOids)
    ) yield (opiskelijat.groupBy(_.henkiloOid), opiskeluoikeudet.groupBy(_.henkiloOid), todistukset.groupBy(_.suoritus.henkiloOid))

    oppijaData.map {
      case (opiskelijat, opiskeluoikeudet, todistukset) =>
        personOids.map((oid: String) => {
          Oppija(
            oppijanumero = oid,
            opiskelu = opiskelijat.getOrElse(oid, Seq()),
            opiskeluoikeudet = opiskeluoikeudet.getOrElse(oid, Seq()),
            suoritukset = todistukset.getOrElse(oid, Seq()),
            ensikertalainen = None
          )
        }).toSeq
    }
  }

  private def fetchTodistukset(suoritukset: Seq[Suoritus with Identified[UUID]])(implicit user: User): Future[Seq[Todistus]] = Future.sequence(
    for (
      suoritus <- suoritukset
    ) yield for (
      arvosanat <- (rekisterit.arvosanaRekisteri ? AuthorizedQuery(ArvosanaQuery(suoritus = Some(suoritus.id)), user)).mapTo[Seq[Arvosana]]
    ) yield Todistus(suoritus, arvosanat)
  )

  private def fetchEnsikertalaisuudet(ensikertalaisuudenRajapvm: Option[DateTime])
                                     (rekisteriData: Seq[Oppija]): Future[Seq[Oppija]] = {
    for (
      ensikertalaisuudet <- (ensikertalaisuus ? EnsikertalainenQuery(
        rekisteriData.map(_.oppijanumero).toSet,
        Some(rekisteriData.flatMap(_.suoritukset.map(_.suoritus))),
        Some(rekisteriData.flatMap(_.opiskeluoikeudet)),
        ensikertalaisuudenRajapvm
      )).mapTo[Seq[Ensikertalainen]].map(_.groupBy(_.henkiloOid).mapValues(_.head))
    ) yield for (
      oppija <- rekisteriData
    ) yield oppija.copy(ensikertalainen = ensikertalaisuudet.get(oppija.oppijanumero).map(_.ensikertalainen))
  }

  private def fetchOpiskeluoikeudet(henkilot: Set[String])(implicit user: User): Future[Seq[Opiskeluoikeus]] =
    splittedQuery[Opiskeluoikeus, Opiskeluoikeus](henkilot, rekisterit.opiskeluoikeusRekisteri, (henkilot) => OpiskeluoikeusHenkilotQuery(henkilot))

  private def fetchOpiskelu(henkilot: Set[String])(implicit user: User): Future[Seq[Opiskelija]] =
    splittedQuery[Opiskelija, Opiskelija](henkilot, rekisterit.opiskelijaRekisteri, (henkilot) => OpiskelijaHenkilotQuery(henkilot))

  private def fetchSuoritukset(henkilot: Set[String])(implicit user: User): Future[Seq[Suoritus with Identified[UUID]]] =
    splittedQuery[Suoritus with Identified[UUID], Suoritus](henkilot, rekisterit.suoritusRekisteri, (henkilot) => SuoritusHenkilotQuery(henkilot))

  private def splittedQuery[A, B](henkilot: Set[String], actor: ActorRef, q: (Set[String]) => Query[B])(implicit user: User): Future[Seq[A]] =
    Future.sequence(grouped(henkilot, singleSplitQuerySize).map(henkiloSubset =>
      (actor ? AuthorizedQuery(q(henkiloSubset), user)).mapTo[Seq[A]]
    )).map(_.foldLeft[Seq[A]](Seq())(_ ++ _))

  private def grouped[A](xs: Set[A], size: Int) = {
    def grouped[B](xs: Set[B], size: Int, result: Set[Set[B]]): Set[Set[B]] = {
      if(xs.isEmpty) {
        result
      } else {
        val (slice, rest) = xs.splitAt(size)
        grouped(rest, size, result + slice)
      }
    }
    grouped(xs, size, Set[Set[A]]())
  }
}
