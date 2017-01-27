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
import slick.jdbc.SimpleJdbcAction
import slick.lifted
import slick.util.{DumpInfo, Dumpable}

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
  def joinHenkilotWithTempTable(
      henkilot: PersonOidsWithAliases,
      joinOn: String,
      baseQuery: lifted.Query[T, Delta[R, I], Seq]): DBIOAction[Seq[Delta[R, I]], Streaming[Delta[R, I]], All with Transactional] = {

    val henkiloviiteTempTable = TableQuery[HenkiloViiteTable]

    val createTempTableStatements = henkiloviiteTempTable.schema.createStatements
      .map(_.replaceAll("(?i)create table", "create temporary table"))
      .reduce(_ ++ _)
      .concat(" on commit drop")


    val createHenkiloviiteTempTable: SimpleDBIO[Array[Int]] with Dumpable = new SimpleJdbcAction({ session =>
      val statement: Statement = session.connection.createStatement()
      try {
        statement.addBatch(createTempTableStatements)
        statement.executeBatch()
      } finally {
        statement.close()
      }
    }) {
      override def getDumpInfo: DumpInfo = DumpInfo(DumpInfo.simpleNameFor(getClass), mainInfo = "[" + createTempTableStatements + "]")
    }

    val populateTempTable = DBIO.sequence(henkilot.aliasesByPersonOids.flatMap { case (henkilo, aliases) => aliases.map { a => henkiloviiteTempTable.forceInsert((henkilo, a)) } } )


    val selectAllMatching = for {
      (record, _) <- baseQuery join henkiloviiteTempTable on (_.column[String](joinOn)  === _.linkedOid)
    } yield record

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
  def deduplicationQuery(i: R, p: Option[PersonOidsWithAliases])(t: T): lifted.Rep[Boolean] = deduplicationQuery(i)(t)

  private def deduplicate(i: R, p: Option[PersonOidsWithAliases]): DBIO[Option[R with Identified[I]]] = all.filter(deduplicationQuery(i, p)).result.map(_.collect {
    case Updated(res) => res
  }.headOption)

  protected def doSave(i: R,
                       handleExistingResourceUpdate: (R, R with Identified[I]) => DBIO[R with Identified[I]],
                       p: Option[PersonOidsWithAliases] = None): R with Identified[I] = {
    journal.runAsSerialized(10, 5.milliseconds, s"Saving $i",
      deduplicate(i, p).flatMap {
        case Some(old) if old == i => DBIO.successful(old)
        case Some(old) => handleExistingResourceUpdate(i, old)
        case None => journal.addUpdate(i.identify)
      }
    ) match {
      case Right(r) => r
      case Left(e) => throw e
    }
  }

  override def save(t: R): Future[R with Identified[I]] = {
    Future.successful(doSave(t, (i, old) => journal.addUpdate(i.identify(old.id))))
  }

  override def insert(t: R, personOidsWithAliases: PersonOidsWithAliases): R with Identified[I] =
    journal.runAsSerialized(10, 5.milliseconds, s"Inserting $t",
      deduplicate(t, Some(personOidsWithAliases)).flatMap(_.fold(journal.addUpdate(t.identify))(DBIO.successful))
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
    var queryStr: String = sqlOf(query)
    var prettyPrint: String = ""

    if(queryStr.length > 200) {
      queryStr = queryStr.take(200) + "...(truncated from " + queryStr.length + " chars)"
      prettyPrint = ", complete queries:\n" + sqlOf(query, multiline = true)
    }
    if(runtime > Config.reallySlowQuery) {
      log.warning(s"Query $queryStr took $runtime ms$prettyPrint")
    } else {
      log.info(s"Query $queryStr took $runtime ms$prettyPrint")
    }
  }

  private def sqlOf(dumpable: Dumpable, multiline: Boolean = false): String = {
    def dump(dumpable: Dumpable, sb: StringBuilder): StringBuilder = {
      val di = dumpable.getDumpInfo
      if (!di.mainInfo.isEmpty) {
        sb.append(di.mainInfo + (if (di.attrInfo.isEmpty) "" else " " + di.attrInfo))
        if (multiline) sb.append("\n")
      }
      di.children.toSeq.foreach { case (_, value) =>
        dump(value, sb)
      }
      sb
    }
    dump(dumpable, new StringBuilder).toString
  }
}
