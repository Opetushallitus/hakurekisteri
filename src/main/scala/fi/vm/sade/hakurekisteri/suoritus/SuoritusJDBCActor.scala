package fi.vm.sade.hakurekisteri.suoritus

import java.util.UUID
import java.util.concurrent.Executors

import akka.dispatch.ExecutionContexts
import com.github.nscala_time.time.Imports._
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.{startOfAutumnDate, yearOf}
import fi.vm.sade.hakurekisteri.rest.support.Kausi._
import fi.vm.sade.hakurekisteri.rest.support.{Query, Kausi => _, _}
import fi.vm.sade.hakurekisteri.storage.{Identified, ResourceActor}
import fi.vm.sade.hakurekisteri.storage.repository.Delta
import slick.dbio.DBIOAction
import slick.dbio.Effect.All
import slick.lifted.Rep
import support.PersonAliasesProvider

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions
import scala.util.Try

class SuoritusJDBCActor(
  val journal: JDBCJournal[Suoritus, UUID, SuoritusTable],
  poolSize: Int,
  personAliasProvider: PersonAliasesProvider,
  config: Config
) extends ResourceActor[Suoritus, UUID](config)
    with JDBCRepository[Suoritus, UUID, SuoritusTable]
    with JDBCService[Suoritus, UUID, SuoritusTable] {

  override def deduplicationQuery(o: Suoritus)(t: SuoritusTable): Rep[Boolean] = o match {
    case VapaamuotoinenSuoritus(henkilo, _, _, _, tyyppi, index, _) =>
      t.henkiloOid === henkilo && (t.tyyppi === tyyppi).asColumnOf[Boolean] && (t.index === index)
        .asColumnOf[Boolean]
    case VirallinenSuoritus(komo, myontaja, _, _, henkilo, _, _, _, vahv, _, suoritustyyppi, _) =>
      (t.komo === komo).asColumnOf[Boolean] &&
        t.myontaja === myontaja &&
        t.henkiloOid === henkilo &&
        (t.vahvistettu === vahv).asColumnOf[Boolean] &&
        ((t.tyyppi.isEmpty && suoritustyyppi.isEmpty) || t.tyyppi === suoritustyyppi)
          .asColumnOf[Boolean]
  }

  override val dbExecutor: ExecutionContext =
    ExecutionContexts.fromExecutor(Executors.newFixedThreadPool(poolSize))

  override def findByWithPersonAliases(
    o: QueryWithPersonOid[Suoritus]
  ): Future[Seq[Suoritus with Identified[UUID]]] = {
    personAliasProvider.enrichWithAliases(o.henkilo.toSet).flatMap { personOidsWithAliases =>
      findBy(o.createQueryWithAliases(personOidsWithAliases))
        .map(suorituses =>
          suorituses.map(s => replaceResultHenkiloOidsWithQueriedOids(s, personOidsWithAliases))
        )
    }
  }

  override def deduplicationQuery(i: Suoritus, p: Option[PersonOidsWithAliases])(
    t: SuoritusTable
  ): Rep[Boolean] = {
    val personOidsWithAliases = p.getOrElse {
      throw new IllegalStateException("PersonOidsWithAliases required")
    }
    i match {
      case VapaamuotoinenSuoritus(henkilo, _, _, _, tyyppi, index, _) =>
        (t.henkiloOid inSet personOidsWithAliases.henkiloOidsWithLinkedOids) && (t.tyyppi === tyyppi)
          .asColumnOf[Boolean] && (t.index === index).asColumnOf[Boolean]
      case VirallinenSuoritus(komo, myontaja, _, _, henkilo, _, _, _, vahv, _, suoritustyyppi, _) =>
        (t.komo === komo).asColumnOf[Boolean] &&
          t.myontaja === myontaja &&
          (t.henkiloOid inSet personOidsWithAliases.henkiloOidsWithLinkedOids) &&
          (t.vahvistettu === vahv).asColumnOf[Boolean] &&
          ((t.tyyppi.isEmpty && suoritustyyppi.isEmpty) || t.tyyppi === suoritustyyppi)
            .asColumnOf[Boolean]
    }
  }

  private def fixPersonOid(
    newSuoritus: Suoritus,
    oldSuoritus: Suoritus with Identified[UUID]
  ): DBIO[Suoritus with Identified[UUID]] = {
    val correctedSuoritus = Suoritus.copyWithHenkiloOid(newSuoritus, oldSuoritus.henkiloOid)
    if (correctedSuoritus == oldSuoritus) {
      DBIO.successful(oldSuoritus)
    } else {
      journal.addUpdate(correctedSuoritus.identify(oldSuoritus.id))
    }
  }

  override def save(t: Suoritus): Future[Suoritus with Identified[UUID]] = {
    personAliasProvider.enrichWithAliases(Set(t.henkiloOid)).map { p =>
      doSave(t, fixPersonOid, Some(p))
    }
  }

  override def save(
    t: Suoritus,
    personOidsWithAliases: PersonOidsWithAliases
  ): Future[Suoritus with Identified[UUID]] = {
    Future.fromTry(Try(doSave(t, fixPersonOid, Some(personOidsWithAliases))))
  }

  private def replaceResultHenkiloOidsWithQueriedOids(
    suoritus: Suoritus with Identified[UUID],
    personOidsWithAliases: PersonOidsWithAliases
  ): Suoritus with Identified[UUID] = {
    if (
      personOidsWithAliases.henkiloOids.isEmpty || personOidsWithAliases.henkiloOids.contains(
        suoritus.henkiloOid
      )
    ) {
      suoritus
    } else {
      personOidsWithAliases.aliasesByPersonOids
        .filter(_._2.contains(suoritus.henkiloOid))
        .map { oidWithAliases => Suoritus.copyWithHenkiloOid(suoritus, oidWithAliases._1) }
        .head
    }
  }

  override val dbQuery: PartialFunction[Query[Suoritus], Either[Throwable, DBIOAction[Seq[
    Delta[Suoritus, UUID]
  ], Streaming[Delta[Suoritus, UUID]], All]]] = {
    case SuoritusQuery(henkilo, kausi, vuosi, myontaja, komo, muokattuJalkeen, muokattuEnnen) =>
      Right(filter(henkilo, kausi, vuosi, myontaja, komo, muokattuJalkeen, muokattuEnnen).result)
    case SuoritusQueryWithPersonAliases(q, henkilot) =>
      val baseQuery =
        filter(None, q.kausi, q.vuosi, q.myontaja, q.komo, q.muokattuJalkeen, q.muokattuEnnen)
      if (henkilot.henkiloOids.isEmpty) {
        Right(baseQuery.result)
      } else {
        Right(findWithHenkilot(henkilot, "henkilo_oid", baseQuery))
      }
    case SuoritusHenkilotQuery(henkilot) => {
      Right(findWithHenkilot(henkilot, "henkilo_oid", all))
    }
    case SuoritysTyyppiQuery(henkilo, komo) =>
      Right(all.filter(t => matchHenkilo(Some(henkilo))(t) && matchKomo(Some(komo))(t)).result)
    case AllForMatchinHenkiloSuoritusQuery(vuosi, myontaja) =>
      Right(all.filter(t => matchVuosi(vuosi)(t) && matchMyontaja(myontaja)(t)).result)
  }

  private def filter(
    henkilo: Option[String],
    kausi: Option[Kausi],
    vuosi: Option[String],
    myontaja: Option[String],
    komo: Option[String],
    muokattuJalkeen: Option[DateTime],
    muokattuEnnen: Option[DateTime]
  ) = {
    all.filter(t =>
      matchHenkilo(henkilo)(t) && matchKausi(kausi)(t) && matchVuosi(vuosi)(t) &&
        matchMyontaja(myontaja)(t) && matchKomo(komo)(t) && matchMuokattuJalkeen(muokattuJalkeen)(t)
        && matchMuokattuEnnen(muokattuEnnen)(t)
    )
  }

  private def matchHenkilo(henkilo: Option[String])(s: SuoritusTable): Rep[Boolean] =
    henkilo.fold[Rep[Boolean]](true)(h => s.henkiloOid === h)

  private def matchKausi(kausi: Option[Kausi])(s: SuoritusTable): Rep[Boolean] =
    kausi.fold[Rep[Boolean]](true) {
      case Kevät =>
        s.valmistuminen.isDefined && (s.valmistuminen < startOfAutumnDate(yearOf(s.valmistuminen)))
          .asColumnOf[Boolean]
      case Syksy =>
        s.valmistuminen.isDefined && (startOfAutumnDate(yearOf(s.valmistuminen)) <= s.valmistuminen)
          .asColumnOf[Boolean]
    }

  private def matchMuokattuJalkeen(
    muokattuJalkeen: Option[DateTime]
  )(s: SuoritusTable): Rep[Boolean] = {
    muokattuJalkeen.fold[Rep[Boolean]](true)(d => s.inserted.asColumnOf[DateTime] >= d)
  }

  private def matchMuokattuEnnen(
    muokattuEnnen: Option[DateTime]
  )(s: SuoritusTable): Rep[Boolean] = {
    muokattuEnnen.fold[Rep[Boolean]](true)(d => s.inserted.asColumnOf[DateTime] < d)
  }

  private def matchKomo(komo: Option[String])(s: SuoritusTable): Rep[Boolean] =
    komo.fold[Rep[Boolean]](true)(k => s.komo.isDefined && (s.komo === k).asColumnOf[Boolean])

  private def matchVuosi(vuosi: Option[String])(s: SuoritusTable): Rep[Boolean] =
    vuosi.fold[Rep[Boolean]](true)(v =>
      (s.vuosi.isDefined && (s.vuosi === v.toInt).asColumnOf[Boolean]) ||
        (s.valmistuminen.isDefined && (yearOf(s.valmistuminen) === vuosi).asColumnOf[Boolean])
    )

  private def matchMyontaja(myontaja: Option[String])(s: SuoritusTable): Rep[Boolean] =
    myontaja.fold[Rep[Boolean]](true)(m => s.myontaja === m)
}
