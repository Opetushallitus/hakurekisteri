package fi.vm.sade.hakurekisteri.ovara
import slick.jdbc.ActionBasedSQLInterpolation
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
//import slick.jdbc.JdbcBackend.Database
//import slick.jdbc.H2Profile.api._

import scala.concurrent.Await
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import support.Journals

import scala.collection.JavaConverters._
import scala.concurrent.duration.{Duration, _}
trait OvaraDbRepository {
  def getChangedSuoritusIds(after: Long, before: Long): Seq[String]
  def getChangedArvosanaIds(after: Long, before: Long): Seq[String]

  def getChangedSuoritukset(params: SiirtotiedostoPagingParams): Seq[SiirtotiedostoSuoritus]
  def getChangedArvosanat(params: SiirtotiedostoPagingParams): Seq[SiirtotiedostoArvosana]
  def getChangedOpiskelijat(params: SiirtotiedostoPagingParams): Seq[SiirtotiedostoOpiskelija]
  def getChangedOpiskeluoikeudet(
    params: SiirtotiedostoPagingParams
  ): Seq[SiirtotiedostoOpiskeluoikeus]

}

class OvaraDbRepositoryImpl(db: Database) extends OvaraDbRepository with OvaraExtractors {

  def getChangedSuoritusIds(after: Long, before: Long): Seq[String] = {
    val query =
      sql"""select resource_id from suoritus where inserted >= $after and inserted <= $before"""
        .as[String]
    Await.result(db.run(query), 10.minutes)
  }

  override def getChangedArvosanaIds(after: Long, before: Long): Seq[String] = ???

  override def getChangedSuoritukset(
    params: SiirtotiedostoPagingParams
  ): Seq[SiirtotiedostoSuoritus] = {
    val query =
      sql"""select resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen,
       suoritus_kieli, inserted, deleted, source, kuvaus, vuosi, tyyppi, index, vahvistettu, current, lahde_arvot
           from suoritus where current and inserted >= ${params.start} and inserted <= ${params.end}
                         order by inserted desc limit ${params.pageSize} offset ${params.offset}"""
        .as[SiirtotiedostoSuoritus]
    runBlocking(query)
  }

  override def getChangedArvosanat(
    params: SiirtotiedostoPagingParams
  ): Seq[SiirtotiedostoArvosana] = ???

  override def getChangedOpiskelijat(
    params: SiirtotiedostoPagingParams
  ): Seq[SiirtotiedostoOpiskelija] = ???

  override def getChangedOpiskeluoikeudet(
    params: SiirtotiedostoPagingParams
  ): Seq[SiirtotiedostoOpiskeluoikeus] = ???

  def runBlocking[R](operations: DBIO[R], timeout: Duration = 10.minutes): R = {
    Await.result(
      db.run(
        operations.withStatementParameters(statementInit =
          st => st.setQueryTimeout(timeout.toSeconds.toInt)
        )
      ),
      timeout
    )
  }
}

//Todo, varmista oikeasti optionaaliset kentät
case class SiirtotiedostoSuoritus(
  resource_id: String,
  komo: String,
  myontaja: String,
  tila: String,
  valmistuminen: String,
  henkilo_oid: String,
  yksilollistaminen: String,
  suoritus_kieli: String,
  source: String,
  kuvaus: Option[String],
  vuosi: Option[String],
  tyyppi: Option[String],
  index: Option[String],
  vahvistettu: Boolean,
  current: Boolean, //Käytännössä aina true, koska ei-currenteja ei ladota siirtotiedostoihin
  lahde_arvot: Map[String, String]
)

case class SiirtotiedostoArvosana()

case class SiirtotiedostoOpiskelija()

case class SiirtotiedostoOpiskeluoikeus()

case class SiirtotiedostoPagingParams(
  tyyppi: String,
  start: Long,
  end: Long,
  offset: Long,
  pageSize: Int
)
