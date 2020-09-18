package fi.vm.sade.hakurekisteri.storage

import fi.vm.sade.hakurekisteri.arvosana.ArvosanaTable
import fi.vm.sade.hakurekisteri.batchimport.ImportBatchTable
import fi.vm.sade.hakurekisteri.opiskelija.OpiskelijaTable
import fi.vm.sade.hakurekisteri.opiskeluoikeus.OpiskeluoikeusTable
import fi.vm.sade.hakurekisteri.suoritus.SuoritusTable

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._

object HakurekisteriTables {
  val suoritusTable = TableQuery[SuoritusTable]
  val opiskelijaTable = TableQuery[OpiskelijaTable]
  val opiskeluoikeusTable = TableQuery[OpiskeluoikeusTable]
  val arvosanaTable = TableQuery[ArvosanaTable]
  val importBatchTable = TableQuery[ImportBatchTable]

  val allTables =
    Seq(suoritusTable, opiskelijaTable, opiskeluoikeusTable, arvosanaTable, importBatchTable)
}
