package fi.vm.sade.hakurekisteri.arvosana

import java.util.UUID
import java.util.concurrent.Executors

import akka.dispatch.ExecutionContexts
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.{JDBCJournal, JDBCRepository, JDBCService, Query, Kausi => _}
import fi.vm.sade.hakurekisteri.storage.ResourceActor
import fi.vm.sade.hakurekisteri.storage.repository.Delta
import slick.dbio.DBIOAction
import slick.dbio.Effect.All
import slick.lifted.Rep

import scala.concurrent.ExecutionContext

case class EmptyLisatiedot() extends Query[Arvosana]

class ArvosanaJDBCActor(val journal: JDBCJournal[Arvosana, UUID, ArvosanaTable], poolSize: Int)
  extends ResourceActor[Arvosana, UUID] with JDBCRepository[Arvosana, UUID, ArvosanaTable] with JDBCService[Arvosana, UUID, ArvosanaTable] {

  override def deduplicationQuery(i: Arvosana)(t: ArvosanaTable): Rep[Boolean] = t.suoritus === i.suoritus &&
    t.aine === i.aine &&
    t.lisatieto.getOrElse("") === i.lisatieto.getOrElse("") &&
    t.valinnainen === i.valinnainen &&
    t.myonnetty.getOrElse("") === i.myonnetty.map(_.toString("yyyy-MM-dd")).getOrElse("") &&
    t.jarjestys.getOrElse(0) === i.jarjestys.getOrElse(0)

  override val dbExecutor: ExecutionContext = ExecutionContexts.fromExecutor(Executors.newFixedThreadPool(poolSize))

  override val dbQuery: PartialFunction[Query[Arvosana], Either[Throwable, DBIOAction[Seq[Delta[Arvosana, UUID]], Streaming[Delta[Arvosana, UUID]], All]]] = {
    case EmptyLisatiedot() => Right(all.filter(t => t.lisatieto.isDefined && t.lisatieto === "").take(30000).result)
    case ArvosanaQuery(suoritus) => Right(all.filter(t => t.suoritus === suoritus).result)
    case ArvosanatQuery(suoritukset) => Right(all.filter(t => t.suoritus.inSet(suoritukset)).result)
  }
}
