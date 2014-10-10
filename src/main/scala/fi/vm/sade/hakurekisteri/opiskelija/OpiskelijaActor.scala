package fi.vm.sade.hakurekisteri.opiskelija

import com.github.nscala_time.time.Imports._
import fi.vm.sade.hakurekisteri.rest.support.Kausi._
import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.hakurekisteri.storage._
import scala.Some
import fi.vm.sade.hakurekisteri.storage.repository._
import scala.Some
import fi.vm.sade.hakurekisteri.henkilo.Henkilo
import java.util.UUID


trait OpiskelijaRepository extends JournaledRepository[Opiskelija, UUID] {



}


trait OpiskelijaService extends InMemQueryingResourceService[Opiskelija, UUID] { this: Repository[Opiskelija, UUID] =>

  val matcher: PartialFunction[Query[Opiskelija], (Opiskelija with Identified[UUID]) => Boolean] = {
    case OpiskelijaQuery(henkilo, kausi, vuosi, paiva, oppilaitosOid, luokka) =>
      (o: Opiskelija with Identified[UUID]) => checkHenkilo(henkilo)(o) &&
                                         checkVuosiAndKausi(vuosi, kausi)(o) &&
                                         checkPaiva(paiva)(o) &&
                                         checkOppilaitos(oppilaitosOid)(o) &&
                                         checkLuokka(luokka)(o)
  }



  def checkOppilaitos(oppilaitos:Option[String])(o:Opiskelija):Boolean = oppilaitos match {
    case Some(oid) => o.oppilaitosOid.equals(oid)
    case None => true

  }

  def checkLuokka(luokka:Option[String])(o:Opiskelija) = luokka match {
    case Some(l) => o.luokka.equals(l)
    case None => true
  }

  def checkPaiva(paiva:Option[DateTime])(o:Opiskelija) = paiva match {
    case Some(date) => o.loppuPaiva match {
      case Some(end) =>  (new DateTime(o.alkuPaiva) to new DateTime(end)).contains(date)
      case None => new DateTime(o.alkuPaiva).isBefore(date)
    }
    case None => true
  }

  def checkHenkilo(henkilo: Option[String])(o:Opiskelija):Boolean  =  henkilo match {
    case Some(oid) => o.henkiloOid.equals(oid)
    case None => true
  }

  def checkVuosiAndKausi(vuosi: Option[String], kausi: Option[Kausi])(o:Opiskelija): Boolean = vuosi match {
    case Some(v) => during(getInterval(v.toInt,kausi), o.alkuPaiva, o.loppuPaiva)
    case None => duringKausi(kausi, o.alkuPaiva, o.loppuPaiva)

  }


  def during(interval: Interval, start: DateTime, end: Option[DateTime]) = end match {
    case Some(date) => new DateTime(start) to new DateTime(date) overlaps interval
    case None => interval.end > new DateTime(start)

  }

  def getInterval(vuosi: Int, kausi: Option[Kausi]): Interval = kausi match {
    case None => year(vuosi)
    case Some(Kevät) => newYear(vuosi).toDateTimeAtStartOfDay to startOfAutumn(vuosi).toDateTimeAtStartOfDay
    case Some(Syksy) => startOfAutumn(vuosi).toDateTimeAtStartOfDay to newYear(vuosi + 1).toDateTimeAtStartOfDay
    case Some(_) => throw new IllegalArgumentException("Not a kausi")
  }

  def year(y:Int): Interval = {
    new Interval(newYear(y).toDateTimeAtStartOfDay, 1.year)
  }


  def newYear(y: Int): LocalDate = {
    new MonthDay(1, 1).toLocalDate(y)
  }


  def duringKausi(kausi: Option[Kausi], start: DateTime, end: Option[DateTime]): Boolean = (kausi, end) match {
    case (Some(Syksy), Some(date)) => (new DateTime(date).getYear != new DateTime(start).getYear) || startOfAutumn(new DateTime(date).getYear).toDateTimeAtStartOfDay <= new DateTime(date)
    case (Some(Kevät), Some(date)) => (new DateTime(date).getYear != new DateTime(start).getYear) || startOfAutumn(new DateTime(date).getYear).toDateTimeAtStartOfDay > new DateTime(start)
    case (Some(_), _ ) => throw new IllegalArgumentException("Not a kausi")
    case (None, _) => true
    case (_, None) => true

  }

  def startOfAutumn(v:Int): LocalDate = {
    new MonthDay(8, 1).toLocalDate(v)
  }

}




class OpiskelijaActor(val journal:Journal[Opiskelija, UUID] = new InMemJournal[Opiskelija, UUID]) extends ResourceActor[Opiskelija, UUID] with OpiskelijaRepository with OpiskelijaService {

}
