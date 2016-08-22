package fi.vm.sade.hakurekisteri.arvosana

import java.util.UUID
import java.util.concurrent.Executors

import akka.dispatch.ExecutionContexts
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.{JDBCJournal, JDBCRepository, JDBCService, Query}
import fi.vm.sade.hakurekisteri.storage._
import fi.vm.sade.hakurekisteri.storage.repository._
import slick.lifted

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

case class EmptyLisatiedot() extends Query[Arvosana]

class ArvosanaJDBCActor(val journal: JDBCJournal[Arvosana, UUID, ArvosanaTable], poolSize: Int)
  extends ResourceActor[Arvosana, UUID] with JDBCRepository[Arvosana, UUID, ArvosanaTable] with JDBCService[Arvosana, UUID, ArvosanaTable] {

  val q = journal.db.run(sql"select count(distinct resource_id) from arvosana".as[Int])
  override def count: Int = Await.result(q, 60.minutes).head

  override def deduplicationQuery(i: Arvosana)(t: ArvosanaTable): Rep[Boolean] = t.suoritus === i.suoritus &&
    t.aine === i.aine &&
    t.lisatieto.getOrElse("") === i.lisatieto.getOrElse("") &&
    t.valinnainen === i.valinnainen &&
    t.myonnetty.getOrElse("") === i.myonnetty.map(_.toString("yyyy-MM-dd")).getOrElse("") &&
    t.jarjestys.getOrElse(0) === i.jarjestys.getOrElse(0)

  override val dbExecutor: ExecutionContext = ExecutionContexts.fromExecutor(Executors.newFixedThreadPool(poolSize))

  override val dbQuery: PartialFunction[Query[Arvosana], Either[Throwable, lifted.Query[ArvosanaTable, Delta[Arvosana, UUID], Seq]]] = {
    case EmptyLisatiedot() => Right(all.filter(t => t.lisatieto.isDefined && t.lisatieto === "").take(30000))
    case ArvosanaQuery(Some(suoritus)) => Right(all.filter(t => t.suoritus === suoritus))
    case ArvosanaQuery(None) => Left(new IllegalArgumentException("empty query not supported for arvosana"))
  }
}
