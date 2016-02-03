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

  def fetchOppijat(q: HakemusQuery, ensikertalaisuudenRajapvm: Option[DateTime] = None)(implicit user: User): Future[Seq[Oppija]] =
    for (
      hakemukset <- (hakemusRekisteri ? q).mapTo[Seq[FullHakemus]];
      oppijat <- fetchOppijatFor(hakemukset, ensikertalaisuudenRajapvm)
    ) yield oppijat

  def fetchOppijatFor(hakemukset: Seq[FullHakemus], ensikertalaisuudenRajapvm: Option[DateTime] = None)(implicit user: User): Future[Seq[Oppija]] = {
    val persons = extractPersons(hakemukset)
    enrichWithEnsikertalaisuus(persons, getRekisteriData(persons.keySet), ensikertalaisuudenRajapvm)
  }

  def fetchOppijat(persons: List[String], hetuExists: Boolean, rajapvm: Option[DateTime])(implicit user: User): Future[Seq[Oppija]] = {
    Future.sequence(persons.map(personOid => fetchOppijaData(personOid, hetuExists, rajapvm)).toSeq)
  }

  def fetchOppija(person: String, rajaPvm: Option[DateTime])(implicit user: User): Future[Oppija] ={
    fetchOppijaData(person, true, rajaPvm)
  }

  private def extractPersons(hakemukset: Seq[FullHakemus]): Map[String, Boolean] =
    (for (
      hakemus <- hakemukset
      if hakemus.personOid.isDefined && hakemus.stateValid
    ) yield (hakemus.personOid.get, hakemus.hetu.isDefined)).groupBy(_._1).map {
      case (personOid, (_, hasHetu) :: rest) if rest.forall(hasHetu == _._2) => (personOid, hasHetu)
      case (personOid, _) => throw new Exception(s"$personOid has applications with conflicting hetu info")
    }

  private def enrichWithEnsikertalaisuus(persons: Map[String, Boolean],
                                         rekisteriData: Future[Seq[Oppija]],
                                         ensikertalaisuudenRajapvm: Option[DateTime])(implicit user: User): Future[Seq[Oppija]] = {
    rekisteriData.flatMap(o => Future.sequence(o.map(oppija => for (
      ensikertalaisuus <- fetchEnsikertalaisuus(
        oppija.oppijanumero,
        persons.getOrElse(oppija.oppijanumero, false),
        oppija.suoritukset.map(_.suoritus),
        oppija.opiskeluoikeudet,
        ensikertalaisuudenRajapvm
      )
    ) yield oppija.copy(
        ensikertalainen = ensikertalaisuus.map(_.ensikertalainen)
      )
    )))
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

  private def fetchOppijaData(henkiloOid: String, hetuExists: Boolean, ensikertalaisuudenRajapvm: Option[DateTime])(implicit user: User): Future[Oppija] =
    for (
      suoritukset <- fetchSuoritukset(henkiloOid);
      todistukset <- fetchTodistukset(suoritukset);
      opiskelu <- fetchOpiskelu(henkiloOid);
      opiskeluoikeudet <- fetchOpiskeluoikeudet(henkiloOid);
      ensikertalainen <- fetchEnsikertalaisuus(henkiloOid, hetuExists, suoritukset, opiskeluoikeudet, ensikertalaisuudenRajapvm)
    ) yield Oppija(
      oppijanumero = henkiloOid,
      opiskelu = opiskelu,
      suoritukset = todistukset,
      opiskeluoikeudet = opiskeluoikeudet,
      ensikertalainen = ensikertalainen.map(_.ensikertalainen)
    )

  private def fetchEnsikertalaisuus(henkiloOid: String,
                                    hetuExists: Boolean,
                                    suoritukset: Seq[Suoritus],
                                    opiskeluoikeudet: Seq[Opiskeluoikeus],
                                    ensikertalaisuudenRajapvm: Option[DateTime]): Future[Option[Ensikertalainen]] = {
    val ensikertalainen: Future[Ensikertalainen] =
      (ensikertalaisuus ? EnsikertalainenQuery(henkiloOid, Some(suoritukset), Some(opiskeluoikeudet), ensikertalaisuudenRajapvm)).mapTo[Ensikertalainen]
    if (hetuExists) {
      ensikertalainen.map(Some(_))
    } else {
      ensikertalainen.map(e => if (e.ensikertalainen) None else Some(e))
    }
  }

  private def fetchOpiskeluoikeudet(henkiloOid: String)(implicit user: User): Future[Seq[Opiskeluoikeus]] =
    (rekisterit.opiskeluoikeusRekisteri ? AuthorizedQuery(OpiskeluoikeusQuery(henkilo = Some(henkiloOid)), user)).mapTo[Seq[Opiskeluoikeus]]

  private def fetchOpiskeluoikeudet(henkilot: Set[String])(implicit user: User): Future[Seq[Opiskeluoikeus]] =
    splittedQuery[Opiskeluoikeus, Opiskeluoikeus](henkilot, rekisterit.opiskeluoikeusRekisteri, (henkilot) => OpiskeluoikeusHenkilotQuery(henkilot))

  private def fetchOpiskelu(henkiloOid: String)(implicit user: User): Future[Seq[Opiskelija]] =
    (rekisterit.opiskelijaRekisteri ? AuthorizedQuery(OpiskelijaQuery(henkilo = Some(henkiloOid)), user)).mapTo[Seq[Opiskelija]]

  private def fetchOpiskelu(henkilot: Set[String])(implicit user: User): Future[Seq[Opiskelija]] =
    splittedQuery[Opiskelija, Opiskelija](henkilot, rekisterit.opiskelijaRekisteri, (henkilot) => OpiskelijaHenkilotQuery(henkilot))

  private def fetchSuoritukset(henkiloOid: String)(implicit user: User): Future[Seq[Suoritus with Identified[UUID]]] =
    (rekisterit.suoritusRekisteri ? AuthorizedQuery(SuoritusQuery(henkilo = Some(henkiloOid)), user)).mapTo[Seq[Suoritus with Identified[UUID]]]

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
