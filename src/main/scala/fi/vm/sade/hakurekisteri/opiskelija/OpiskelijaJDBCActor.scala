package fi.vm.sade.hakurekisteri.opiskelija

import java.util.UUID
import java.util.concurrent.Executors

import akka.dispatch.ExecutionContexts
import com.github.nscala_time.time.Imports._
import fi.vm.sade.hakurekisteri.rest.support
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.{startOfAutumn, startOfYear, yearOf}
import fi.vm.sade.hakurekisteri.rest.support.Kausi._
import fi.vm.sade.hakurekisteri.rest.support.{JDBCJournal, JDBCRepository, JDBCService}
import fi.vm.sade.hakurekisteri.storage._
import fi.vm.sade.hakurekisteri.storage.repository._
import slick.dbio.Effect.All
import slick.lifted

import scala.concurrent.ExecutionContext

class OpiskelijaJDBCActor(val journal: JDBCJournal[Opiskelija, UUID, OpiskelijaTable], poolSize: Int)
  extends ResourceActor[Opiskelija, UUID] with JDBCRepository[Opiskelija, UUID, OpiskelijaTable] with JDBCService[Opiskelija, UUID, OpiskelijaTable] {

  override def deduplicationQuery(i: Opiskelija)(t: OpiskelijaTable): Rep[Boolean] =
    t.oppilaitosOid === i.oppilaitosOid && t.luokkataso === i.luokkataso && t.henkiloOid === i.henkiloOid

  override val dbExecutor: ExecutionContext = ExecutionContexts.fromExecutor(Executors.newFixedThreadPool(poolSize))

  override val dbQuery: PartialFunction[support.Query[Opiskelija], Either[Throwable, DBIOAction[Seq[Delta[Opiskelija, UUID]], Streaming[Delta[Opiskelija, UUID]], All]]] = {
    case OpiskelijaQuery(henkilo, kausi, vuosi, paiva, oppilaitosOid, luokka) =>
      Right(all.filter(t => matchHenkilo(henkilo)(t) &&
        matchOppilaitosOid(oppilaitosOid)(t) &&
        matchPaiva(paiva)(t) &&
        matchVuosiAndKausi(vuosi, kausi)(t) &&
        matchLuokka(luokka)(t)).result)
  }

  private def matchHenkilo(henkilo: Option[String])(t: OpiskelijaTable): Rep[Boolean] = henkilo match {
    case Some(h) => t.henkiloOid === h
    case None => true
  }

  private def matchOppilaitosOid(oppilaitosOid: Option[String])(t: OpiskelijaTable): Rep[Boolean] = oppilaitosOid match {
    case Some(o) => t.oppilaitosOid === o
    case None => true
  }

  private def matchLuokka(luokka: Option[String])(t: OpiskelijaTable): Rep[Boolean] = luokka match {
    case Some(l) => t.luokka === l
    case None => true
  }

  private def matchPaiva(paiva: Option[DateTime])(t: OpiskelijaTable): Rep[Boolean] = paiva match {
    case Some(date) => t.alkuPaiva < date && (t.loppuPaiva.isEmpty || (t.loppuPaiva > date).asColumnOf[Boolean])
    case None => true
  }

  private def matchVuosiAndKausi(vuosi: Option[String], kausi: Option[Kausi])(t: OpiskelijaTable): Rep[Boolean] = {
    val start = t.alkuPaiva
    val end = t.loppuPaiva
    (vuosi, kausi) match {
      case (Some(v), Some(Kevät)) => (end.isEmpty || (startOfYear(v) <= end).asColumnOf[Boolean]) && start < startOfAutumn(v)
      case (Some(v), Some(Syksy)) => (end.isEmpty || (startOfAutumn(v) <= end).asColumnOf[Boolean]) && start < startOfYear((v.toInt + 1).toString)
      case (Some(v), None) => (end.isEmpty || (startOfYear(v) <= end).asColumnOf[Boolean]) && start < startOfYear((v.toInt + 1).toString)
      case (None, Some(Kevät)) => end.isEmpty || (start < startOfAutumn(yearOf(end))).asColumnOf[Boolean]
      case (None, Some(Syksy)) => end.isEmpty || (startOfAutumn(yearOf(start)) <= end).asColumnOf[Boolean]
      case (None, None) => true
      case (_, Some(k)) => throw new IllegalArgumentException(s"Not a kausi $k")
    }
  }
}
