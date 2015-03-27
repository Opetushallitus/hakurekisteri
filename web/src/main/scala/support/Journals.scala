package support

import java.util.UUID

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaTable}
import fi.vm.sade.hakurekisteri.batchimport.{ImportBatch, ImportBatchTable}
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaTable}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusTable}
import fi.vm.sade.hakurekisteri.rest.support.JDBCJournal
import fi.vm.sade.hakurekisteri.storage.repository.Journal
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusTable}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.simple._

import scala.slick.lifted.TableQuery
import scala.util.Try

trait Journals {
  val suoritusJournal: Journal[Suoritus, UUID]
  val opiskelijaJournal: Journal[Opiskelija, UUID]
  val opiskeluoikeusJournal: Journal[Opiskeluoikeus, UUID]
  val arvosanaJournal: Journal[Arvosana, UUID]
  val eraJournal: Journal[ImportBatch, UUID]
}

class DbJournals(jndiName: String)(implicit val system: ActorSystem) extends Journals {
  implicit val database = Try(Database.forName(jndiName)).recover {
    case _: javax.naming.NameNotFoundException => Database.forURL("jdbc:h2:file:data/sample", driver = "org.h2.Driver")
    case _: javax.naming.NoInitialContextException => Database.forURL("jdbc:h2:file:data/sample", driver = "org.h2.Driver")
  }.get

  override val suoritusJournal = new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable])
  override val opiskelijaJournal = new JDBCJournal[Opiskelija, UUID, OpiskelijaTable](TableQuery[OpiskelijaTable])
  override val opiskeluoikeusJournal = new JDBCJournal[Opiskeluoikeus, UUID, OpiskeluoikeusTable](TableQuery[OpiskeluoikeusTable])
  override val arvosanaJournal = new JDBCJournal[Arvosana, UUID, ArvosanaTable](TableQuery[ArvosanaTable])
  override val eraJournal = new JDBCJournal[ImportBatch, UUID, ImportBatchTable](TableQuery[ImportBatchTable])

}
