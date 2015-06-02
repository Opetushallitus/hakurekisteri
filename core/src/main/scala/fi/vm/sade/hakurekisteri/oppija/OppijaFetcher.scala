package fi.vm.sade.hakurekisteri.oppija

import fi.vm.sade.hakurekisteri.rest.support.{User, Registers}
import akka.actor.ActorRef
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

  val megaQueryLimit = 1000

  protected implicit def executor: ExecutionContext
  implicit val defaultTimeout: Timeout

  def fetchOppijat(q: HakemusQuery)(implicit user: User): Future[Seq[Oppija]] =
    for (
      hakemukset <- (hakemusRekisteri ? q).mapTo[Seq[FullHakemus]];
      oppijat <- fetchOppijatFor(hakemukset)
    ) yield oppijat

  private def isMegaQuery(persons: Set[(String, Option[String])]): Boolean = persons.size > megaQueryLimit

  def fetchOppijatFor(hakemukset: Seq[FullHakemus])(implicit user: User): Future[Seq[Oppija]] = {
    val persons = extractPersons(hakemukset)

    if (isMegaQuery(persons)) {
      enrichWithEnsikertalaisuus(persons, getRekisteriData(persons.map(_._1)))
    } else {
      Future.sequence(persons.map {
        case (personOid, hetu) => fetchOppijaData(personOid, hetu)
      }.toSeq)
    }
  }

  private def extractPersons(hakemukset: Seq[FullHakemus]): Set[(String, Option[String])] =
    (for (
      hakemus <- hakemukset
      if hakemus.personOid.isDefined && hakemus.stateValid
    ) yield (hakemus.personOid.get, hakemus.hetu)).toSet

  private def enrichWithEnsikertalaisuus(persons: Set[(String, Option[String])],
                                 rekisteriData: Future[Seq[Oppija]])(implicit user: User): Future[Seq[Oppija]] = {
    val hetuMap = persons.groupBy(_._1).mapValues(_.headOption.flatMap(_._2))

    rekisteriData.flatMap(o => Future.sequence(o.map(oppija => for (
      ensikertalaisuus <- fetchEnsikertalaisuus(
        oppija.oppijanumero,
        hetuMap.getOrElse(oppija.oppijanumero, None),
        oppija.suoritukset.map(_.suoritus),
        oppija.opiskeluoikeudet
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
        (opiskelijat.keys ++ opiskeluoikeudet.keys ++ todistukset.keys).toSet.map((oid: String) => {
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

  private def fetchOppijaData(henkiloOid: String, hetu: Option[String])(implicit user: User): Future[Oppija] =
    for (
      suoritukset <- fetchSuoritukset(henkiloOid);
      todistukset <- fetchTodistukset(suoritukset);
      opiskelu <- fetchOpiskelu(henkiloOid);
      opiskeluoikeudet <- fetchOpiskeluoikeudet(henkiloOid);
      ensikertalainen <- fetchEnsikertalaisuus(henkiloOid, hetu, suoritukset, opiskeluoikeudet)
    ) yield Oppija(
      oppijanumero = henkiloOid,
      opiskelu = opiskelu,
      suoritukset = todistukset,
      opiskeluoikeudet = opiskeluoikeudet,
      ensikertalainen = ensikertalainen.map(_.ensikertalainen)
    )

  private def fetchEnsikertalaisuus(henkiloOid: String, hetu: Option[String], suoritukset: Seq[Suoritus], opiskeluoikeudet: Seq[Opiskeluoikeus]): Future[Option[Ensikertalainen]] = hetu match {
    case Some(_) => (ensikertalaisuus ? EnsikertalainenQuery(henkiloOid, Some(suoritukset), Some(opiskeluoikeudet))).mapTo[Ensikertalainen].map(Some(_))
    case None => Future.successful(None)
  }

  private def fetchOpiskeluoikeudet(henkiloOid: String)(implicit user: User): Future[Seq[Opiskeluoikeus]] =
    (rekisterit.opiskeluoikeusRekisteri ? AuthorizedQuery(OpiskeluoikeusQuery(henkilo = Some(henkiloOid)), user)).mapTo[Seq[Opiskeluoikeus]]

  private def fetchOpiskeluoikeudet(henkilot: Set[String])(implicit user: User): Future[Seq[Opiskeluoikeus]] =
    (rekisterit.opiskeluoikeusRekisteri ? AuthorizedQuery(OpiskeluoikeusHenkilotQuery(henkilot), user)).mapTo[Seq[Opiskeluoikeus]]

  private def fetchOpiskelu(henkiloOid: String)(implicit user: User): Future[Seq[Opiskelija]] =
    (rekisterit.opiskelijaRekisteri ? AuthorizedQuery(OpiskelijaQuery(henkilo = Some(henkiloOid)), user)).mapTo[Seq[Opiskelija]]

  private def fetchOpiskelu(henkilot: Set[String])(implicit user: User): Future[Seq[Opiskelija]] =
    (rekisterit.opiskelijaRekisteri ? AuthorizedQuery(OpiskelijaHenkilotQuery(henkilot), user)).mapTo[Seq[Opiskelija]]

  private def fetchSuoritukset(henkiloOid: String)(implicit user: User): Future[Seq[Suoritus with Identified[UUID]]] =
    (rekisterit.suoritusRekisteri ? AuthorizedQuery(SuoritusQuery(henkilo = Some(henkiloOid)), user)).mapTo[Seq[Suoritus with Identified[UUID]]]

  private def fetchSuoritukset(henkilot: Set[String])(implicit user: User): Future[Seq[Suoritus with Identified[UUID]]] =
    (rekisterit.suoritusRekisteri ? AuthorizedQuery(SuoritusHenkilotQuery(henkilot), user)).mapTo[Seq[Suoritus with Identified[UUID]]]
}
