package fi.vm.sade.hakurekisteri.oppija

import fi.vm.sade.hakurekisteri.rest.support.{Query, User, Registers}
import akka.actor.ActorRef
import scala.concurrent.{Future, ExecutionContext}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.hakemus.{HakemusService, FullHakemus, HakemusQuery}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusHenkilotQuery, Suoritus}
import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID
import fi.vm.sade.hakurekisteri.organization.AuthorizedQuery
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaQuery}
import fi.vm.sade.hakurekisteri.ensikertalainen.{EnsikertalainenQuery, Ensikertalainen}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{OpiskeluoikeusHenkilotQuery, Opiskeluoikeus}
import fi.vm.sade.hakurekisteri.opiskelija.{OpiskelijaHenkilotQuery, Opiskelija}
import akka.pattern.ask

trait OppijaFetcher {

  val rekisterit: Registers
  val hakemusService: HakemusService
  val ensikertalaisuus: ActorRef

  val singleSplitQuerySize = 5000

  protected implicit def executor: ExecutionContext
  implicit val defaultTimeout: Timeout

  def fetchOppijat(q: HakemusQuery, hakuOid: String)(implicit user: User): Future[Seq[Oppija]] = {
    val personOids = hakemusService.personOidsForHaku(hakuOid)
    for (
      oppijat <- fetchOppijat(personOids, Some(hakuOid))(user)
    ) yield oppijat
  }

  def fetchOppijat(persons: Set[String], hakuOid: Option[String])(implicit user: User): Future[Seq[Oppija]] = {
    enrichWithEnsikertalaisuus(getRekisteriData(persons)(user), hakuOid)
  }

  def fetchOppija(person: String, hakuOid: Option[String])(implicit user: User): Future[Oppija] = {
    fetchOppijat(Set(person), hakuOid)(user).map(_.head)
  }

  private def enrichWithEnsikertalaisuus(rekisteriData: Future[Seq[Oppija]],
                                         hakuOid: Option[String]): Future[Seq[Oppija]] = hakuOid match {
    case Some(haku) => rekisteriData.flatMap(fetchEnsikertalaisuudet(haku))
    case None => rekisteriData
  }

  def getRekisteriData(personOids: Set[String])(implicit user: User): Future[Seq[Oppija]] = {
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

  private def fetchEnsikertalaisuudet(hakuOid: String)
                                     (rekisteriData: Seq[Oppija]): Future[Seq[Oppija]] = {
    for (
      ensikertalaisuudet <- (ensikertalaisuus ? EnsikertalainenQuery(
        henkiloOids = rekisteriData.map(_.oppijanumero).toSet,
        hakuOid = hakuOid,
        Some(rekisteriData.flatMap(_.suoritukset.map(_.suoritus))),
        Some(rekisteriData.flatMap(_.opiskeluoikeudet))
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
    Future.sequence(henkilot.grouped(singleSplitQuerySize).map(henkiloSubset =>
      (actor ? AuthorizedQuery(q(henkiloSubset), user)).mapTo[Seq[A]]
    )).map(_.flatten.toSeq)
}
