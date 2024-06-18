package fi.vm.sade.hakurekisteri.ovara

import scala.concurrent.Await
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._

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
       suoritus_kieli, inserted, deleted, source, kuvaus, vuosi, tyyppi, index, vahvistettu, lahde_arvot
           from suoritus where current and inserted >= ${params.start} and inserted <= ${params.end}
                         order by inserted desc limit ${params.pageSize} offset ${params.offset}"""
        .as[SiirtotiedostoSuoritus]
    runBlocking(query)
  }

  override def getChangedArvosanat(
    params: SiirtotiedostoPagingParams
  ): Seq[SiirtotiedostoArvosana] = {
    val query =
      sql"""select resource_id, suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, inserted, deleted, pisteet, myonnetty, source, jarjestys, lahde_arvot
           from arvosana where current and inserted >= ${params.start} and inserted <= ${params.end}
                         order by inserted desc limit ${params.pageSize} offset ${params.offset}"""
        .as[SiirtotiedostoArvosana]
    runBlocking(query)
  }

  override def getChangedOpiskelijat(
    params: SiirtotiedostoPagingParams
  ): Seq[SiirtotiedostoOpiskelija] = {
    val query =
      sql"""select resource_id, oppilaitos_oid, luokkataso, luokka, henkilo_oid, alku_paiva, loppu_paiva, inserted, deleted, source
           from opiskelija where current and inserted >= ${params.start} and inserted <= ${params.end}
                         order by inserted desc limit ${params.pageSize} offset ${params.offset}"""
        .as[SiirtotiedostoOpiskelija]
    runBlocking(query)
  }
  override def getChangedOpiskeluoikeudet(
    params: SiirtotiedostoPagingParams
  ): Seq[SiirtotiedostoOpiskeluoikeus] = {
    val query =
      sql"""select resource_id, alku_paiva, loppu_paiva, henkilo_oid, komo, myontaja, source, inserted, deleted
           from opiskeluoikeus where current and inserted >= ${params.start} and inserted <= ${params.end}
                         order by inserted desc limit ${params.pageSize} offset ${params.offset}"""
        .as[SiirtotiedostoOpiskeluoikeus]
    runBlocking(query)
  }

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

case class SiirtotiedostoSuoritus(
  resourceId: String,
  komo: Option[String],
  myontaja: String,
  tila: Option[String],
  valmistuminen: Option[String],
  henkiloOid: String,
  yksilollistaminen: Option[String],
  suoritusKieli: Option[String],
  inserted: Long,
  deleted: Option[Boolean],
  source: String,
  kuvaus: Option[String],
  vuosi: Option[String],
  tyyppi: Option[String],
  index: Option[String],
  vahvistettu: Boolean,
  lahdeArvot: Map[String, String]
)

case class SiirtotiedostoArvosana(
  resourceId: String,
  suoritus: String,
  arvosana: Option[String],
  asteikko: Option[String],
  aine: Option[String],
  lisatieto: Option[String],
  valinnainen: Boolean,
  inserted: Long,
  deleted: Boolean,
  pisteet: Option[String],
  myonnetty: Option[String],
  source: String,
  jarjestys: Option[String],
  lahdeArvot: Map[String, String]
)

case class SiirtotiedostoOpiskelija(
  resourceId: String,
  oppilaitosOid: String,
  luokkataso: String,
  luokka: String,
  henkiloOid: String,
  alkuPaiva: Long,
  loppuPaiva: Option[Long],
  inserted: Long,
  deleted: Boolean,
  source: String
)

case class SiirtotiedostoOpiskeluoikeus(
  resourceId: String,
  alkuPaiva: Long,
  loppuPaiva: Option[Long],
  henkiloOid: String,
  komo: String,
  myontaja: String,
  source: String,
  inserted: Long,
  deleted: Boolean
)

case class SiirtotiedostoPagingParams(
  executionId: String, //uuid
  fileCounter: Int,
  tyyppi: String,
  start: Long,
  end: Long,
  offset: Long,
  pageSize: Int
)
