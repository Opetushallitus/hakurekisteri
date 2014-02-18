package fi.vm.sade.hakurekisteri.suoritus

import fi.vm.sade.hakurekisteri.rest.support.{Query, Kausi}
import Kausi._
import fi.vm.sade.hakurekisteri.storage._
import scala.Some
import com.github.nscala_time.time.Imports._
import fi.vm.sade.hakurekisteri.storage.repository._
import scala.Some


trait SuoritusRepository extends JournaledRepository[Suoritus] {


  def identify(o:Suoritus): Suoritus with Identified = o match {
    case o: Suoritus with Identified => o
    case _ => Suoritus.identify(o)
  }

}

trait SuoritusService extends ResourceService[Suoritus] { this: Repository[Suoritus] =>

  val matcher: PartialFunction[Query[Suoritus], (Suoritus with Identified) => Boolean] = {
    case SuoritusQuery(henkilo, kausi, vuosi) =>  (s: Suoritus with Identified) =>
      checkHenkilo(henkilo)(s) && checkVuosi(vuosi)(s) && checkKausi(kausi)(s)
  }

  def checkHenkilo(henkilo: Option[String])(s:Suoritus):Boolean  =  henkilo match {
    case Some(oid) => s.henkiloOid.equals(oid)
    case None => true
  }

  def beforeYearEnd(vuosi:String)(date:LocalDate): Boolean = {
    date.getYear <= vuosi.toInt
  }

  def checkVuosi(vuosi: Option[String])(s:Suoritus):Boolean = vuosi match {

    case Some(vuosi:String) => beforeYearEnd(vuosi)(s.valmistuminen)
    case None => true
  }

  def checkKausi(kausi: Option[Kausi])(s: Suoritus):Boolean = kausi match{
    case Some(Kevät) => duringFirstHalf(s.valmistuminen)
    case Some(Syksy) => !duringFirstHalf(s.valmistuminen)
    case None => true
    case _ => true
  }

  def duringFirstHalf(date: LocalDate):Boolean = {
    (newYear(date.getYear) to startOfAutumn(date.getYear)).contains(date.toDateTimeAtStartOfDay)
  }


  def startOfAutumn(year: Int): DateTime = {
    new MonthDay(8, 1).toLocalDate(year).toDateTimeAtStartOfDay
  }

  def newYear(year: Int): DateTime = {
    new MonthDay(1, 1).toLocalDate(year).toDateTimeAtStartOfDay
  }
}

class SuoritusActor(val journal:Journal[Suoritus] = new InMemJournal[Suoritus]) extends ResourceActor[Suoritus] with SuoritusRepository with SuoritusService {






}





