package fi.vm.sade.hakurekisteri.oppija

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanatQuery}
import fi.vm.sade.hakurekisteri.ensikertalainen.{Ensikertalainen, EnsikertalainenQuery}
import fi.vm.sade.hakurekisteri.integration.hakemus.{HakemusQuery, IHakemusService}
import fi.vm.sade.hakurekisteri.integration.henkilo.{IOppijaNumeroRekisteri, PersonOidsWithAliases}
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaHenkilotQuery}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusHenkilotQuery}
import fi.vm.sade.hakurekisteri.organization.AuthorizedQuery
import fi.vm.sade.hakurekisteri.rest.support.{Query, Registers, User}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusHenkilotQuery}
import fi.vm.sade.hakurekisteri.tools.DurationHelper

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait OppijaFetcher {

  val logger: LoggingAdapter
  val rekisterit: Registers
  val hakemusService: IHakemusService
  val ensikertalaisuus: ActorRef
  val oppijaNumeroRekisteri: IOppijaNumeroRekisteri

  val singleSplitQuerySize = 5000

  protected implicit def executor: ExecutionContext
  implicit val defaultTimeout: Timeout

  def fetchOppijat(ensikertalaisuudet: Boolean, q: HakemusQuery)(implicit user: User): Future[Seq[Oppija]] = {
    for (
      personOids <- q.hakukohde match {
        case Some(hakukohdeOid) => hakemusService.personOidsForHakukohde(hakukohdeOid, q.organisaatio)
        case None => hakemusService.personOidsForHaku(q.haku.get, q.organisaatio)
      };
      oppijat <- fetchOppijat(personOids, ensikertalaisuudet, q)(user)
    ) yield oppijat
  }

  def fetchOppijat(persons: Set[String], ensikertalaisuudet: Boolean, q: HakemusQuery)(implicit user: User): Future[Seq[Oppija]] = {
    oppijaNumeroRekisteri.enrichWithAliases(persons).flatMap(personOidsWithAliases => {
      val rekisteriData = getRekisteriData(personOidsWithAliases)(user)
      if (ensikertalaisuudet) {
        rekisteriData.flatMap(fetchEnsikertalaisuudet(q))
      } else {
        rekisteriData
      }
    })
  }

  def fetchOppija(person: String, ensikertalaisuudet: Boolean, hakuOid: Option[String])(implicit user: User): Future[Oppija] = {
    fetchOppijat(Set(person), ensikertalaisuudet, HakemusQuery(haku = hakuOid))(user).map(_.head)
  }

  def getRekisteriData(personOidsWithAliases: PersonOidsWithAliases)(implicit user: User): Future[Seq[Oppija]] = {
    try {
      val logId = UUID.randomUUID()

      def timed[A](msg: String, f: Future[A]): Future[A] =
        DurationHelper.timed[A](logger, Duration(100, TimeUnit.MILLISECONDS))(s"$logId: $msg", f)

      val todistuksetF = timed("Suoritukset for rekisteritiedot", fetchSuoritukset(personOidsWithAliases))
        .flatMap(suoritukset => timed("Todistukset for rekisteritiedot", fetchTodistukset(suoritukset)))
        .map(_.groupBy(_.suoritus.henkiloOid))
      val opiskeluoikeudetF = timed("Opiskeluoikeudet for rekisteritiedot", fetchOpiskeluoikeudet(personOidsWithAliases))
        .map(_.groupBy(_.henkiloOid))
      val opiskelijatF = timed("Opiskelijat for rekisteritiedot", fetchOpiskelu(personOidsWithAliases))
        .map(_.groupBy(_.henkiloOid))

      for {
        todistukset <- todistuksetF
        opiskeluoikeudet <- opiskeluoikeudetF
        opiskelijat <- opiskelijatF
      } yield personOidsWithAliases.henkiloOids.map(henkiloOid =>
        Oppija(
          oppijanumero = henkiloOid,
          opiskelu = opiskelijat.getOrElse(henkiloOid, Seq()),
          opiskeluoikeudet = opiskeluoikeudet.getOrElse(henkiloOid, Seq()),
          suoritukset = suorituksetWithAliases(personOidsWithAliases.intersect(Set(henkiloOid)), todistukset, henkiloOid),
          ensikertalainen = None
        )
      ).toSeq
    } catch {
      case e: Exception => {
        logger.error("getRekisteriData failed", e)
        Future.failed(e)
      }
    }
  }

  private def suorituksetWithAliases(personOidsWithAliases: PersonOidsWithAliases, todistuksetByPersonOid: Map[String, Seq[Todistus]], oid: String): Seq[Todistus] = {
    val todistukset: Set[Todistus] = for {
      alias: String <- personOidsWithAliases.aliasesByPersonOids(oid)
      todistus <- todistuksetByPersonOid.getOrElse(alias, Seq())
    } yield todistus.copy(suoritus = Suoritus.copyWithHenkiloOid(todistus.suoritus.identify, oid))

    todistukset.toSeq
  }

  private def fetchTodistukset(suoritukset: Seq[Suoritus with Identified[UUID]])(implicit user: User): Future[Seq[Todistus]] =
    try {
      for (
        arvosanat <- (rekisterit.arvosanaRekisteri ? AuthorizedQuery(ArvosanatQuery(suoritukset.map(_.id).toSet), user))
          .mapTo[Seq[Arvosana]]
          .map(_.groupBy(_.suoritus))
      ) yield suoritukset.map(suoritus => Todistus(suoritus, arvosanat.getOrElse(suoritus.id, Seq())))
    } catch {
      case e: Exception => Future.failed(e)
    }

  private def fetchEnsikertalaisuudet(q: HakemusQuery)
                                     (rekisteriData: Seq[Oppija]): Future[Seq[Oppija]] = {
    for (
      ensikertalaisuudet <- (ensikertalaisuus ? EnsikertalainenQuery(
        henkiloOids = rekisteriData.map(_.oppijanumero).toSet,
        hakuOid = q.haku.get,
        hakukohdeOid = q.hakukohde,
        Some(rekisteriData.flatMap(_.suoritukset.map(_.suoritus))),
        Some(rekisteriData.flatMap(_.opiskeluoikeudet))
      )).mapTo[Seq[Ensikertalainen]].map(_.groupBy(_.henkiloOid).mapValues(_.head))
    ) yield for (
      oppija <- rekisteriData
    ) yield oppija.copy(ensikertalainen = ensikertalaisuudet.get(oppija.oppijanumero).map(_.ensikertalainen))
  }

  private def fetchOpiskeluoikeudet(personOidsWithAliases: PersonOidsWithAliases)(implicit user: User): Future[Seq[Opiskeluoikeus]] =
    try {
      splittedQuery[Opiskeluoikeus, Opiskeluoikeus](personOidsWithAliases, rekisterit.opiskeluoikeusRekisteri, (henkilot) => OpiskeluoikeusHenkilotQuery(henkilot))
    } catch {
      case e: Exception => Future.failed(e)
    }

  private def fetchOpiskelu(personOidsWithAliases: PersonOidsWithAliases)(implicit user: User): Future[Seq[Opiskelija]] =
    try {
      splittedQuery[Opiskelija, Opiskelija](personOidsWithAliases, rekisterit.opiskelijaRekisteri, (henkilot) => OpiskelijaHenkilotQuery(henkilot))
    } catch {
      case e: Exception => Future.failed(e)
    }

  private def fetchSuoritukset(personOidsWithAliases: PersonOidsWithAliases)(implicit user: User): Future[Seq[Suoritus with Identified[UUID]]] =
    try {
      splittedQuery[Suoritus with Identified[UUID], Suoritus](personOidsWithAliases, rekisterit.suoritusRekisteri, (henkilot) => SuoritusHenkilotQuery(henkilot))
    } catch {
      case e: Exception => Future.failed(e)
    }

  private def splittedQuery[A, B](personOidsWithAliases: PersonOidsWithAliases, actor: ActorRef, q: (PersonOidsWithAliases) => Query[B])(implicit user: User): Future[Seq[A]] =
    Future.sequence(personOidsWithAliases.henkiloOids.grouped(singleSplitQuerySize).map(henkiloSubset =>
      (actor ? AuthorizedQuery(q(personOidsWithAliases.intersect(henkiloSubset)), user)).mapTo[Seq[A]]
    )).map(_.flatten.toSeq)
}
