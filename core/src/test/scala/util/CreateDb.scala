package util

import fi.vm.sade.hakurekisteri.rest.support.{JDBCJournal, HakurekisteriDriver}
import HakurekisteriDriver.simple._
import scala.slick.jdbc.meta.MTable
import fi.vm.sade.hakurekisteri.batchimport.{ImportStatus, BatchState, ImportBatch, ImportBatchTable}
import java.util.UUID
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen, VirallinenSuoritus, Suoritus, SuoritusTable}
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.Config
import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloSearchResponse
import scala.concurrent.ExecutionContext
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import fi.vm.sade.hakurekisteri.tools.Peruskoulu
import com.github.nscala_time.time.TypeImports._
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloSearchResponse
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import org.joda.time.{DateTime, LocalDate}
import fi.vm.sade.hakurekisteri.opiskelija.{OpiskelijaTable, Opiskelija}

object CreateDb extends App {
  println("Creating a test db")

  val kevatJuhla = new MonthDay(6,4).toLocalDate(DateTime.now.getYear)


  implicit val db = Database.forURL("jdbc:h2:file:data/sample", driver = "org.h2.Driver")

  implicit val system = ActorSystem("db-import")

  implicit val ec: ExecutionContext = system.dispatcher


  println(Config.henkiloConfig)

  val henkiloClient = new VirkailijaRestClient(Config.henkiloConfig, None)

  val suoritusJournal = new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable])
  val opiskelijaJournal = new JDBCJournal[Opiskelija, UUID, OpiskelijaTable](TableQuery[OpiskelijaTable])


  for (
    henkilos <- henkiloClient.readObject[HenkiloSearchResponse]("/resources/henkilo/?ht=OPPIJA&index=0&count=50&no=true&s=true&p=false", 200)
  ) {
    for (
      henkilo <- henkilos.results
    ) createOppilas(henkilo.oidHenkilo)
    println()
    system.shutdown()




  }

  system.awaitTermination()

  def createOppilas(oid:String) {
    suoritusJournal.addModification(Updated(VirallinenSuoritus(Config.perusopetusKomoOid, "1.2.246.562.10.39644336305", "KESKEN", kevatJuhla, oid, yksilollistaminen.Ei, "fi",  lahde = "Test").identify(UUID.randomUUID())))
    opiskelijaJournal.addModification(Updated(Opiskelija("1.2.246.562.10.39644336305", "9", "9A", oid, new MonthDay(8,18).toLocalDate(DateTime.now.getYear - 1).toDateTimeAtStartOfDay(), Some(kevatJuhla.toDateTimeAtStartOfDay()), "Test").identify(UUID.randomUUID())))
    print(".")
  }


}