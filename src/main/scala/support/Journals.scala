package support

import java.util.{Properties, UUID}

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaTable}
import fi.vm.sade.hakurekisteri.batchimport.{ImportBatch, ImportBatchTable}
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaTable}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusTable}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.JDBCJournal
import fi.vm.sade.hakurekisteri.storage.HakurekisteriTables._
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusTable}
import org.slf4j.LoggerFactory

trait Journals {
  val suoritusJournal: JDBCJournal[Suoritus, UUID, SuoritusTable]
  val opiskelijaJournal: JDBCJournal[Opiskelija, UUID, OpiskelijaTable]
  val opiskeluoikeusJournal: JDBCJournal[Opiskeluoikeus, UUID, OpiskeluoikeusTable]
  val arvosanaJournal: JDBCJournal[Arvosana, UUID, ArvosanaTable]
  val eraJournal: JDBCJournal[ImportBatch, UUID, ImportBatchTable]
}

class DbJournals(config: Config)(implicit val system: ActorSystem) extends Journals {
  lazy val log = LoggerFactory.getLogger(getClass)

  log.info(s"Opening database connections to ${config.databaseUrl} with user ${config.postgresUser}")
  val configForDb = {
    val javaProperties = new Properties()
    config.properties.foreach(kv => javaProperties.put(kv._1, kv._2))
    javaProperties.put("suoritusrekisteri.db.url", config.databaseUrl)
    if(config.postgresUser != null) javaProperties.put("suoritusrekisteri.db.user", config.postgresUser)
    if(config.postgresPassword != null) javaProperties.put("suoritusrekisteri.db.password", config.postgresPassword)
    ConfigFactory.parseProperties(javaProperties)
  }
  implicit val database = Database.forConfig("suoritusrekisteri.db", configForDb)
  system.registerOnTermination(database.close())

  val dbLoggingConfig = SureDbLoggingConfig(config)

  override val suoritusJournal = new JDBCJournal[Suoritus, UUID, SuoritusTable](suoritusTable, dbLoggingConfig, config)
  override val opiskelijaJournal = new JDBCJournal[Opiskelija, UUID, OpiskelijaTable](opiskelijaTable, dbLoggingConfig, config)
  override val opiskeluoikeusJournal = new JDBCJournal[Opiskeluoikeus, UUID, OpiskeluoikeusTable](opiskeluoikeusTable, dbLoggingConfig, config)
  override val arvosanaJournal = new JDBCJournal[Arvosana, UUID, ArvosanaTable](arvosanaTable, dbLoggingConfig, config)
  override val eraJournal = new JDBCJournal[ImportBatch, UUID, ImportBatchTable](importBatchTable, dbLoggingConfig, config)
}

case class SureDbLoggingConfig(slowQueryMillis: Long = 200,
                               reallySlowQueryMillis: Long = 10000,
                               maxLogLineLength: Int = 600)
object SureDbLoggingConfig {
  def apply(config: Config): SureDbLoggingConfig = new SureDbLoggingConfig(
    slowQueryMillis = config.slowQuery,
    reallySlowQueryMillis = config.reallySlowQuery,
    maxLogLineLength = config.maxDbLogLineLength)
}
