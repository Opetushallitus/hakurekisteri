package fi.vm.sade.hakurekisteri.suoritus

import akka.event.Logging
import fi.vm.sade.hakurekisteri.rest.support.{Query, Kausi}
import Kausi._
import fi.vm.sade.hakurekisteri.storage._
import com.github.nscala_time.time.Imports._
import fi.vm.sade.hakurekisteri.storage.repository._
import scala.concurrent.Future
import java.util.UUID
import scala.util.Try
import org.joda.time.ReadableInstant


trait SuoritusRepository extends JournaledRepository[Suoritus, UUID] {
  var tiedonSiirtoIndex: Map[String, Map[String, Seq[Suoritus with Identified[UUID]]]] = Option(tiedonSiirtoIndex).getOrElse(Map())

  def year(suoritus:Suoritus): String = suoritus match {
    case s: VirallinenSuoritus =>  s.valmistuminen.getYear.toString
    case s: VapaamuotoinenSuoritus => s.vuosi.toString
  }

  def addNew(suoritus: Suoritus with Identified[UUID]) = {
    tiedonSiirtoIndex = Option(tiedonSiirtoIndex).getOrElse(Map())

    val newIndexSeq =  suoritus +: tiedonSiirtoIndex.get(suoritus.henkiloOid).flatMap((i) => i.get(year(suoritus))).getOrElse(Seq())
    val newHenk = tiedonSiirtoIndex.getOrElse(suoritus.henkiloOid, Map()) + (year(suoritus) -> newIndexSeq)
    tiedonSiirtoIndex = tiedonSiirtoIndex + (suoritus.henkiloOid -> newHenk)
  }

  override def index(old: Option[Suoritus with Identified[UUID]], current: Option[Suoritus with Identified[UUID]]) {
    def removeOld(suoritus: Suoritus with Identified[UUID]) = {
      tiedonSiirtoIndex = Option(tiedonSiirtoIndex).getOrElse(Map())
      val newIndexSeq = tiedonSiirtoIndex.get(suoritus.henkiloOid).flatMap((i) => i.get(year(suoritus))).map(_.filter((s) => s != suoritus || s.id != suoritus.id))
      val newHenkiloIndex: Option[Map[String, Seq[Suoritus with Identified[UUID]]]] = newIndexSeq.flatMap((newSeq) =>
        tiedonSiirtoIndex.get(suoritus.henkiloOid).map((henk) => henk + (year(suoritus) -> newSeq))
      )
      val newIndex = newHenkiloIndex.map((henk)=>
        tiedonSiirtoIndex + (suoritus.henkiloOid -> henk)
      )

      tiedonSiirtoIndex = newIndex.getOrElse(tiedonSiirtoIndex)
    }

    old.foreach(removeOld)
    current.foreach(addNew)
  }


}

trait SuoritusService extends InMemQueryingResourceService[Suoritus, UUID] with SuoritusRepository {

  override val emptyQuery: PartialFunction[Query[Suoritus], Boolean] = {
    case SuoritusQuery(None, None, None, None) => true

  }

  override val optimize: PartialFunction[Query[Suoritus], Future[Seq[Suoritus with Identified[UUID]]]] = {
    case SuoritusQuery(Some(henkilo), None, Some(vuosi), None) => Future.successful(tiedonSiirtoIndex.get(henkilo).flatMap(_.get(vuosi)).getOrElse(Seq()))
    case SuoritusQuery(Some(henkilo), kausi, Some(vuosi), myontaja) =>
      val filtered = tiedonSiirtoIndex.get(henkilo).flatMap(_.get(vuosi)).getOrElse(Seq())
      executeQuery(filtered)(SuoritusQuery(Some(henkilo), kausi, Some(vuosi), myontaja))
    case SuoritusQuery(Some(henkilo), None, None, None) =>
      Future.successful(tiedonSiirtoIndex.get(henkilo).map(_.values.reduce(_ ++ _)).getOrElse(Seq()))
    case SuoritusQuery(Some(henkilo), kausi, vuosi, myontaja) =>
      val filtered = tiedonSiirtoIndex.get(henkilo).map(_.values.reduce(_ ++ _)).getOrElse(Seq())
      executeQuery(filtered)(SuoritusQuery(Some(henkilo), kausi, vuosi, myontaja))
  }

  val matcher: PartialFunction[Query[Suoritus], (Suoritus with Identified[UUID]) => Boolean] = {
    case SuoritusQuery(henkilo, kausi, vuosi, myontaja) =>  (s: Suoritus with Identified[UUID]) =>
      checkHenkilo(henkilo)(s) && checkVuosi(vuosi)(s) && checkKausi(kausi)(s) && checkMyontaja(myontaja)(s)
  }

  def checkMyontaja(myontaja: Option[String])(suoritus: Suoritus):Boolean = (suoritus, myontaja) match {
    case (s:VirallinenSuoritus, Some(oid)) => s.myontaja.equals(oid)
    case (s:VapaamuotoinenSuoritus, Some(oid)) => false
    case (_, None) => true
  }

  def checkHenkilo(henkilo: Option[String])(s: Suoritus):Boolean = henkilo match {
    case Some(oid) => s.henkiloOid.equals(oid)
    case None => true
  }

  def beforeYearEnd(vuosi:String)(date:LocalDate): Boolean = {
    date.getYear <= vuosi.toInt
  }

  def checkVuosi(vuosi: Option[String])(suoritus:Suoritus):Boolean = (suoritus, vuosi) match {
    case (s:VirallinenSuoritus, Some(vuosi:String)) =>  duringYear(vuosi)(s.valmistuminen)
    case (s:VapaamuotoinenSuoritus, Some(vuosi:String)) => Try(vuosi.toInt).map((hakuvuosi) => s.vuosi == hakuvuosi).getOrElse(false)
    case (_, None) => true
  }

  def checkKausi(kausi: Option[Kausi])(suoritus: Suoritus):Boolean = (suoritus,kausi) match{
    case (s: VirallinenSuoritus, Some(Kevät)) => duringFirstHalf(s.valmistuminen)
    case (s: VirallinenSuoritus, Some(Syksy)) => !duringFirstHalf(s.valmistuminen)
    case (s: VirallinenSuoritus, Some(_)) => true
    case (s: VapaamuotoinenSuoritus, Some(_)) => false
    case (_, None) => true
  }

  implicit def local2IntervalDate(date: LocalDate): IntervalDate = IntervalDate(date)


  def duringFirstHalf(date: LocalDate):Boolean = {
    date isBetween newYear(date.getYear) and startOfAutumn(date.getYear)
  }

  def duringYear(year: String)(date: LocalDate): Boolean = {
    Try(
      date isBetween newYear(year.toInt) and newYear(year.toInt + 1)
    ).getOrElse(false)
  }

  case class IntervalStart(start: ReadableInstant, contained: LocalDate){

    def and(end: ReadableInstant) = start to end contains contained.toDateTimeAtStartOfDay


  }

  case class IntervalDate(date: LocalDate) {



    def isBetween(start: ReadableInstant) = IntervalStart(start, date)

  }


  def startOfAutumn(year: Int): DateTime = {
    new MonthDay(8, 1).toLocalDate(year).toDateTimeAtStartOfDay
  }

  def newYear(year: Int): DateTime = {
    new MonthDay(1, 1).toLocalDate(year).toDateTimeAtStartOfDay
  }
}

class SuoritusActor(val journal:Journal[Suoritus, UUID] = new InMemJournal[Suoritus, UUID]) extends ResourceActor[Suoritus, UUID] with SuoritusRepository with SuoritusService {
  override val logger = Logging(context.system, this)
}





