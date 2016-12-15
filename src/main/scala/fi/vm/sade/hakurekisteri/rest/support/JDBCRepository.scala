package fi.vm.sade.hakurekisteri.rest.support

import java.sql.Statement

import akka.actor.ActorLogging
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.henkilo.{HenkiloViiteTable, PersonOidsWithAliases}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.storage.repository.{Deleted, _}
import fi.vm.sade.hakurekisteri.storage.{Identified, ResourceService}
import slick.ast.BaseTypedType
import slick.dbio.DBIOAction
import slick.dbio.Effect.{All, Transactional}
import slick.lifted

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}


trait JDBCRepository[R <: Resource[I, R], I, T <: JournalTable[R, I, _]] extends Repository[R,I]  {

  val journal: JDBCJournal[R,I,T]

  implicit val dbExecutor: ExecutionContext
  implicit val idType: BaseTypedType[I] = journal.idType

  override def delete(id: I, source: String): Unit = journal.addModification(Deleted(id, source))

  override def cursor(t: R): Any = ???

  val all = journal.latestResources.filter(_.deleted === false)

  def latest(id: I): lifted.Query[T, Delta[R, I], Seq] = all.filter((item) => item.resourceId === id)

  /**
    * Query journaled table with temporary table populated by person-alias mappings from henkilot and journaled table is joined on temp table by the joinOn column
    */
  def joinHenkilotWithTempTable(henkilot: PersonOidsWithAliases, joinOn: String): DBIOAction[Seq[Delta[R, I]], Streaming[Delta[R, I]], All with Transactional] = {
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

    val selectAllMatching = for {
      (record, _) <- all join henkiloviiteTempTable on (_.column[String](joinOn)  === _.linkedOid)
    } yield record

    val populateTempTable = DBIO.sequence(henkilot.aliasesByPersonOids.flatMap { case (henkilo, aliases) => aliases.map { a => henkiloviiteTempTable.forceInsert((henkilo, a)) } } )

    createHenkiloviiteTempTable.andThen(populateTempTable).andThen(selectAllMatching.distinct.result).transactionally
  }

  override def get(id: I): Option[R with Identified[I]] = Await.result(journal.db.run(latest(id).result.headOption), 10.seconds).collect {
    case Updated(res) => res
  }

  override def getAll(ids: Seq[I]): Seq[R with Identified[I]] = Await.result(journal.db.run(all.filter(_.resourceId.inSet(ids)).result), 60.seconds).collect {
    case Updated(res) => res
  }

  override def listAll(): Seq[R with Identified[I]] = Await.result(journal.db.run(all.result), 1.minute).collect {
    case Updated(res) => res
  }

  def deduplicationQuery(i: R)(t: T): lifted.Rep[Boolean]

  private def deduplicate(i: R): DBIO[Option[R with Identified[I]]] = all.filter(deduplicationQuery(i)).result.map(_.collect {
    case Updated(res) => res
  }.headOption)

  override def save(t: R): R with Identified[I] =
    journal.runAsSerialized(10, 5.milliseconds, s"Saving $t",
      deduplicate(t).flatMap {
        case Some(old) if old == t => DBIO.successful(old)
        case Some(old) => journal.addUpdate(t.identify(old.id))
        case None => journal.addUpdate(t.identify)
      }
    ) match {
      case Right(r) => r
      case Left(e) => throw e
    }

  override def insert(t: R): R with Identified[I] =
    journal.runAsSerialized(10, 5.milliseconds, s"Inserting $t",
      deduplicate(t).flatMap(_.fold(journal.addUpdate(t.identify))(DBIO.successful))
    ) match {
      case Right(r) => r
      case Left(e) => throw e
    }
}

trait JDBCService[R <: Resource[I, R], I, T <: JournalTable[R, I, _]] extends ResourceService[R,I] { this: JDBCRepository[R,I,T] with ActorLogging =>

  override def findBy(q: Query[R]): Future[Seq[R with Identified[I]]] = {
    dbQuery.lift(q).map{
      case Right(query) =>
        val start = Platform.currentTime
        val f = journal.db.run(query).map(_.collect { case Updated(res) => res })(dbExecutor)
        f.onComplete(_ => {
          val runtime = Platform.currentTime - start
          if (runtime > Config.slowQuery) {
            logSlowQuery(runtime, query)
          }
        })(dbExecutor)
        f
      case Left(t) => Future.failed(t)
    }.getOrElse(Future.successful(Seq()))
  }

  val dbQuery: PartialFunction[Query[R], Either[Throwable, DBIOAction[Seq[Delta[R, I]], Streaming[Delta[R,I]], All]]]

  private def logSlowQuery(runtime: Long, query: DBIOAction[_,_,_]): Unit = {
    var queryStr = query.getDumpInfo.mainInfo
    if(queryStr.length > 500) {
      queryStr = queryStr.take(500) + "...(truncated from " + queryStr.length + " chars)"
    }
    if(runtime > Config.reallySlowQuery) {
      log.info(s"Query $queryStr took $runtime ms")
    } else {
      log.warning(s"Query $queryStr took $runtime ms")
    }
  }
}
