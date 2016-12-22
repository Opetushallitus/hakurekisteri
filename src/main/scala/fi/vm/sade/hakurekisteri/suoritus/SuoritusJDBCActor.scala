package fi.vm.sade.hakurekisteri.suoritus

import java.sql.Statement
import java.util.UUID
import java.util.concurrent.Executors

import akka.dispatch.ExecutionContexts
import com.github.nscala_time.time.Imports._
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloViiteTable
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.{startOfAutumnDate, yearOf}
import fi.vm.sade.hakurekisteri.rest.support.Kausi._
import fi.vm.sade.hakurekisteri.rest.support.{Query, Kausi => _, _}
import fi.vm.sade.hakurekisteri.storage.{Identified, ResourceActor}
import fi.vm.sade.hakurekisteri.storage.repository.Delta
import slick.dbio.DBIOAction
import slick.dbio.Effect.All
import slick.lifted.Rep

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

class SuoritusJDBCActor(val journal: JDBCJournal[Suoritus, UUID, SuoritusTable], poolSize: Int)
  extends ResourceActor[Suoritus, UUID] with JDBCRepository[Suoritus, UUID, SuoritusTable] with JDBCService[Suoritus, UUID, SuoritusTable] {

  override def deduplicationQuery(o: Suoritus)(t: SuoritusTable): Rep[Boolean] = o match {
    case VapaamuotoinenSuoritus(henkilo, _, _, _, tyyppi, index, _) =>
      t.henkiloOid === henkilo && (t.tyyppi === tyyppi).asColumnOf[Boolean] && (t.index === index).asColumnOf[Boolean]
    case VirallinenSuoritus(komo, myontaja, _, _, henkilo, _, _, _, vahv, _) =>
      (t.komo === komo).asColumnOf[Boolean] && t.myontaja === myontaja && t.henkiloOid === henkilo && (t.vahvistettu === vahv).asColumnOf[Boolean]
  }

  override val dbExecutor: ExecutionContext = ExecutionContexts.fromExecutor(Executors.newFixedThreadPool(poolSize))

  override def findByWithPersonAliases(o: QueryWithPersonAliasesResolver[Suoritus]): Future[Seq[Suoritus with Identified[UUID]]] = {
    o.fetchPersonAliases.flatMap { personOidsWithAliases =>
      findBy(o.createQueryWithAliases(personOidsWithAliases)) // TODO there's probably a more elegant way to do this
    }
  }

  override val dbQuery: PartialFunction[Query[Suoritus], Either[Throwable, DBIOAction[Seq[Delta[Suoritus, UUID]], Streaming[Delta[Suoritus, UUID]], All]]] = {
    case SuoritusQuery(henkilo, kausi, vuosi, myontaja, komo, muokattuJalkeen, personOidAliasFetcher) =>
      Right(filter(henkilo, kausi, vuosi, myontaja, komo, muokattuJalkeen).result)
    case SuoritusQueryWithPersonAliases(q, henkilot) =>
      val baseQUery = filter(q.henkilo, q.kausi, q.vuosi, q.myontaja, q.komo, q.muokattuJalkeen)
      if (henkilot.henkiloOids.isEmpty) {
        Right(baseQUery.result)
      } else {
        Right(
          { // TODO : Consolidate this copy-paste with JDBCRepository.joinHenkilotWithTempTable

            val henkiloviiteTempTable = TableQuery[HenkiloViiteTable]

            val createTempTableStatements = henkiloviiteTempTable.schema.createStatements
              .map(_.replaceAll("(?i)create table", "create temporary table"))
              .reduce(_ ++ _)
              .concat(" on commit drop")

            val createHenkiloviiteTempTable = SimpleDBIO { session =>
              val statement: Statement = session.connection.createStatement()
              try {
                statement.addBatch(createTempTableStatements)
                statement.executeBatch()
              } finally {
                statement.close()
              }
            }

            val populateTempTable = DBIO.sequence(henkilot.aliasesByPersonOids.flatMap { case (henkilo, aliases) => aliases.map { a => henkiloviiteTempTable.forceInsert((henkilo, a)) } })

            val selectAllMatching = for {
              (record, _) <- baseQUery join henkiloviiteTempTable on (_.column[String]("henkilo_oid") === _.linkedOid)
            } yield record

            createHenkiloviiteTempTable.andThen(populateTempTable).andThen(selectAllMatching.distinct.result).transactionally

          })
      }
    case SuoritusHenkilotQuery(henkilot) => {
      Right(joinHenkilotWithTempTable(henkilot, "henkilo_oid"))
     }
    case SuoritysTyyppiQuery(henkilo, komo) => Right(all.filter(t => matchHenkilo(Some(henkilo))(t) && matchKomo(Some(komo))(t)).result)
    case AllForMatchinHenkiloSuoritusQuery(vuosi, myontaja) => Right(all.filter(t => matchVuosi(vuosi)(t) && matchMyontaja(myontaja)(t)).result)
  }

  private def filter(henkilo: Option[String], kausi: Option[Kausi], vuosi: Option[String], myontaja: Option[String], komo: Option[String], muokattuJalkeen: Option[DateTime]) = {
    all.filter(t => matchHenkilo(henkilo)(t) && matchKausi(kausi)(t) && matchVuosi(vuosi)(t) &&
      matchMyontaja(myontaja)(t) && matchKomo(komo)(t) && matchMuokattuJalkeen(muokattuJalkeen)(t))
  }

  private def matchHenkilo(henkilo: Option[String])(s: SuoritusTable): Rep[Boolean] =
    henkilo.fold[Rep[Boolean]](true)(h => s.henkiloOid === h)

  private def matchKausi(kausi: Option[Kausi])(s: SuoritusTable): Rep[Boolean] =
    kausi.fold[Rep[Boolean]](true) {
      case Kevät =>
        s.valmistuminen.isDefined && (s.valmistuminen < startOfAutumnDate(yearOf(s.valmistuminen))).asColumnOf[Boolean]
      case Syksy =>
        s.valmistuminen.isDefined && (startOfAutumnDate(yearOf(s.valmistuminen)) <= s.valmistuminen).asColumnOf[Boolean]
    }

  private def matchMuokattuJalkeen(muokattuJalkeen: Option[DateTime])(s: SuoritusTable): Rep[Boolean] = {
    muokattuJalkeen.fold[Rep[Boolean]](true)(d => s.inserted.asColumnOf[DateTime] >= d)
  }

  private def matchKomo(komo: Option[String])(s: SuoritusTable): Rep[Boolean] =
    komo.fold[Rep[Boolean]](true)(k => s.komo.isDefined && (s.komo === k).asColumnOf[Boolean])

  private def matchVuosi(vuosi: Option[String])(s: SuoritusTable): Rep[Boolean] =
    vuosi.fold[Rep[Boolean]](true)(v => (s.vuosi.isDefined && (s.vuosi === v.toInt).asColumnOf[Boolean]) ||
      (s.valmistuminen.isDefined && (yearOf(s.valmistuminen) === vuosi).asColumnOf[Boolean]))

  private def matchMyontaja(myontaja: Option[String])(s: SuoritusTable): Rep[Boolean] =
    myontaja.fold[Rep[Boolean]](true)(m => s.myontaja === m)
}
