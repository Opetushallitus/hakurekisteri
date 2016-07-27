package support

import java.util.UUID

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaTable}
import fi.vm.sade.hakurekisteri.batchimport.{ImportBatch, ImportBatchTable}
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaTable}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusTable}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.JDBCJournal
import fi.vm.sade.hakurekisteri.storage.repository.Journal
import fi.vm.sade.hakurekisteri.storage.HakurekisteriTables._
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusTable}
import org.slf4j.LoggerFactory

import scala.util.Try

trait Journals {
  val suoritusJournal: Journal[Suoritus, UUID]
  val opiskelijaJournal: Journal[Opiskelija, UUID]
  val opiskeluoikeusJournal: Journal[Opiskeluoikeus, UUID]
  val arvosanaJournal: Journal[Arvosana, UUID]
  val eraJournal: Journal[ImportBatch, UUID]
}

class DbJournals(config: Config)(implicit val system: ActorSystem) extends Journals {
  lazy val log = LoggerFactory.getLogger(getClass)

  private def useDevelopmentH2 = {
    log.info("Use develompent h2: " + config.h2DatabaseUrl)
    Database.forURL(config.h2DatabaseUrl, driver = "org.h2.Driver")
  }

  implicit val database = Try(Database.forName(config.jndiName)).recover {
    case _: javax.naming.NameNotFoundException => useDevelopmentH2
    case _: javax.naming.NoInitialContextException => useDevelopmentH2
  }.get

  system.registerOnTermination(database.close())

  override val suoritusJournal = new JDBCJournal[Suoritus, UUID, SuoritusTable](suoritusTable)
  override val opiskelijaJournal = new JDBCJournal[Opiskelija, UUID, OpiskelijaTable](opiskelijaTable)
  override val opiskeluoikeusJournal = new JDBCJournal[Opiskeluoikeus, UUID, OpiskeluoikeusTable](opiskeluoikeusTable)
  override val arvosanaJournal = new JDBCJournal[Arvosana, UUID, ArvosanaTable](arvosanaTable)
  override val eraJournal = new JDBCJournal[ImportBatch, UUID, ImportBatchTable](importBatchTable)

}
