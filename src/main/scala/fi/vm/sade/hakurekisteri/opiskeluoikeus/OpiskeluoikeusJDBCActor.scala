package fi.vm.sade.hakurekisteri.opiskeluoikeus

import java.util.UUID
import java.util.concurrent.Executors

import akka.dispatch.ExecutionContexts
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.{JDBCJournal, JDBCRepository, JDBCService, Query}
import fi.vm.sade.hakurekisteri.storage.ResourceActor
import fi.vm.sade.hakurekisteri.storage.repository.Delta
import slick.dbio.Effect.All
import slick.lifted

import scala.concurrent.ExecutionContext

class OpiskeluoikeusJDBCActor(val journal: JDBCJournal[Opiskeluoikeus, UUID, OpiskeluoikeusTable], poolSize: Int)
  extends ResourceActor[Opiskeluoikeus, UUID] with JDBCRepository[Opiskeluoikeus, UUID, OpiskeluoikeusTable] with JDBCService[Opiskeluoikeus, UUID, OpiskeluoikeusTable] {

  override def deduplicationQuery(o: Opiskeluoikeus)(t: OpiskeluoikeusTable): Rep[Boolean] =
    t.henkiloOid === o.henkiloOid && t.komo === o.komo && t.myontaja === o.myontaja

  override val dbExecutor: ExecutionContext = ExecutionContexts.fromExecutor(Executors.newFixedThreadPool(poolSize))

  override val dbQuery: PartialFunction[Query[Opiskeluoikeus], Either[Throwable, DBIOAction[Seq[Delta[Opiskeluoikeus, UUID]], Streaming[Delta[Opiskeluoikeus, UUID]], All]]] = {
    case OpiskeluoikeusQuery(henkilo, myontaja) => Right(all.filter(t =>
      henkilo.fold[Rep[Boolean]](true)(t.henkiloOid === _) && myontaja.fold[Rep[Boolean]](true)(t.myontaja === _)).result)
    case OpiskeluoikeusHenkilotQuery(henkilot, myontaja) => {
      Right(joinHenkilotWithTempTable(henkilot, "henkilo_oid"))
    }
  }
}
