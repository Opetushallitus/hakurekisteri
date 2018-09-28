package fi.vm.sade.hakurekisteri.rest.support

import java.sql.Statement

import akka.actor.ActorLogging
import fi.vm.sade.hakurekisteri.integration.henkilo.{HenkiloViiteTable, PersonOidsWithAliases}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.storage.repository.{Deleted, _}
import fi.vm.sade.hakurekisteri.storage.{Identified, ResourceService}
import org.springframework.util.ReflectionUtils
import slick.ast.BaseTypedType
import slick.dbio.DBIOAction
import slick.dbio.Effect.{All, Transactional}
import slick.jdbc.SimpleJdbcAction
import slick.lifted
import slick.util.{DumpInfo, Dumpable}
import scala.collection.mutable.ListBuffer
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
  private def joinHenkilotWithTempTable(
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

    val henkiloAliasPairs: Iterable[(String, String)] = for {
      henkiloOid <- henkilot.aliasesByPersonOids.keys
      aliasOid <- henkilot.aliasesByPersonOids(henkiloOid)
    } yield (henkiloOid, aliasOid)
    val populateTempTable = henkiloviiteTempTable ++= henkiloAliasPairs

    val selectAllMatching = for {
      (record, _) <- baseQuery join henkiloviiteTempTable on (_.column[String](joinOn)  === _.linkedOid)
    } yield record

    createHenkiloviiteTempTable.andThen(populateTempTable).andThen(selectAllMatching.distinct.result).transactionally
  }

  def findWithHenkilot(henkilot: PersonOidsWithAliases,
                       joinOn: String,
                       baseQuery: lifted.Query[T, Delta[R, I], Seq]): DBIOAction[Seq[Delta[R, I]], Streaming[Delta[R, I]], All with Transactional] = {
    henkilot.uniquePersonOid match {
      case Some(uniquePersonOid) => baseQuery.filter(x => x.column[String](joinOn) === uniquePersonOid).result
      case None => joinHenkilotWithTempTable(henkilot, joinOn, baseQuery)
    }
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

  override def save(t: R, personOidsWithAliases: PersonOidsWithAliases): Future[R with Identified[I]] = {
    Future.successful(doSave(t, (i, old) => journal.addUpdate(i.identify(old.id)), Some(personOidsWithAliases)))
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
          if (runtime > journal.dbLoggingConfig.slowQueryMillis) {
            logSlowQuery(runtime, query)
          }
        })(dbExecutor)
        f
      case Left(t) => Future.failed(t)
    }.getOrElse(Future.successful(Seq()))
  }

  val dbQuery: PartialFunction[Query[R], Either[Throwable, DBIOAction[Seq[Delta[R, I]], Streaming[Delta[R,I]], All]]]

  private def logSlowQuery(runtime: Long, query: DBIOAction[_,_,_]): Unit = {
    val sqlsAndCounts: Seq[(String, Int)] = sqlOf(query)
    val sqlStringSeq = sqlsAndCounts.map(x => x._1 + (if (x._2 > 1) s" (repeated ${x._2} times)" else "") )
    val sqlString = sqlStringSeq.mkString(" ")

    val (loggableQueryStr, prettyPrint) = if (sqlString.length > journal.dbLoggingConfig.maxLogLineLength) {
      (sqlString.take(journal.dbLoggingConfig.maxLogLineLength) + "...(truncated from " + sqlString.length + " chars)",
      ", complete queries:\n" + sqlStringSeq.mkString("\n"))
    } else {
      (sqlString, "")
    }
    if (runtime > journal.dbLoggingConfig.reallySlowQueryMillis) {
      log.warning(s"Query $loggableQueryStr took $runtime ms$prettyPrint")
    } else {
      log.info(s"Query $loggableQueryStr took $runtime ms$prettyPrint")
    }
  }

  /**
    * Extract SQL queries from a Dumpable object.
    *
    * Takes a Dumpable object, traverses it recursively and collects SQL queries
    * and the counts of repeated queries.
    *
    * The method is recursive and it works as follows:
    * 1.  The auxiliary method dump is called
    * 2.  It extracts DumpInfo object from the current Dumpable
    * 3a. If the DumpInfo object's mainInfo-field is not empty (mainInfo and
    *     attrInfo) contain the things we want to extract), then a string
    *     representation of the SQL query is constructed. This string is then
    *     compared with the last extracted sql query, which is stored as the last
    *     element in the ListBuffer object 'lb'. If the strings match, then the
    *     count is incremented, else the query is simply appended to the
    *     ListBuffer, with a count of 1.
    * 3b. Else dump is called with each of the children of the DumpInfo object
    *     (which are Dumpable objects)
    *
    * @param dumpable A Dumpable object
    * @return Sequence of pairs containing query and count
    */
  private def sqlOf(dumpable: Dumpable): Seq[(String, Int)] = {
    val lb = new ListBuffer[(String, Int)]()
    def dump(dumpable: Dumpable): Unit = {
      val di = dumpable.getDumpInfo
      if (!di.mainInfo.isEmpty) {
        kludgeLoggingMultiInsertParameters(dumpable)
        val newQuery = di.mainInfo + (if (di.attrInfo.isEmpty) "" else " " + di.attrInfo)
        val previousQuery = if (lb.nonEmpty) lb.last._1 else ""
        if (previousQuery == newQuery) {
          val newCount = lb.last._2 + 1
          lb.remove(lb.indexOf(lb.last))
          lb.append((newQuery, newCount))
        } else {
          lb.append((newQuery, 1))
        }
      } else {
        di.children.foreach  { case (_, value) =>
          dump(value)
        }
      }
    }
    dump(dumpable)
    lb
  }

  /**
    * Slick has a class MultiInsertAction not visible to outside which stores in a private
    * field its query parameters...
    *
    * @see slick.driver.JdbcActionComponent.InsertActionComposerImpl.MultiInsertAction
    */
  private def kludgeLoggingMultiInsertParameters(dumpable: Dumpable) = {
    if (log.isInfoEnabled && dumpable.getDumpInfo.name == "MultiInsertAction") {
      dumpable.getClass.getFields.find(_.getName.endsWith("values")).foreach { field =>
        field.setAccessible(true)
        val valuesObj = ReflectionUtils.getField(field, dumpable)
        valuesObj match {
          case sqlParameterValues: Iterable[_] =>
            log.info(s"MultiInsertAction being run for ${sqlParameterValues.size} values")
            log.debug(s"MultiInsertAction parameter values: $sqlParameterValues")
          case _ =>
        }
      }
    }
  }
}
