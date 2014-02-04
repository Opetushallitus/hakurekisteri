package fi.vm.sade.hakurekisteri.opiskelija

import java.util.Date
import java.text.SimpleDateFormat
import com.github.nscala_time.time.Imports._
import akka.actor.Actor
import org.slf4j.LoggerFactory

class OpiskelijaActor(var opiskelijat:Seq[Opiskelija] = Seq()) extends Actor {

  val logger = LoggerFactory.getLogger(getClass)

  def receive: Receive = {
    case OpiskelijaQuery(henkilo, kausi, vuosi) =>
      sender ! findBy(henkilo, vuosi, kausi)
    case o:Opiskelija =>
      sender ! saveOpiskelija(o)
  }

  def getInterval(vuosi: Int, kausi: Option[String]): Interval = kausi match {
    case None => year(vuosi)
    case Some("K") => newYear(vuosi).toDateTimeAtStartOfDay to startOfAutumn(vuosi).toDateTimeAtStartOfDay
    case Some("S") => startOfAutumn(vuosi).toDateTimeAtStartOfDay to newYear(vuosi + 1).toDateTimeAtStartOfDay
    case Some(_) => throw new IllegalArgumentException("Not a kausi")
  }


  def startOfAutumn(v:Int): LocalDate = {
    new MonthDay(8, 1).toLocalDate(v)
  }

  def duringKausi(kausi: Option[String], start: Date, end: Option[Date]): Boolean = (kausi, end) match {
    case (Some("S"), Some(date)) => (new DateTime(date).getYear != new DateTime(start).getYear) || startOfAutumn(new DateTime(date).getYear).toDateTimeAtStartOfDay <= new DateTime(date)
    case (Some("K"), Some(date)) => (new DateTime(date).getYear != new DateTime(start).getYear) || startOfAutumn(new DateTime(date).getYear).toDateTimeAtStartOfDay > new DateTime(start)
    case (Some(_), _ ) => throw new IllegalArgumentException("Not a kausi")
    case (None, _) => true
    case (_, None) => true

  }

  def checkVuosiAndKausi(vuosi: Option[String], kausi: Option[String])(o:Opiskelija): Boolean = vuosi match {
    case Some(v) => during(getInterval(v.toInt,kausi), o.alkuPaiva, o.loppuPaiva)
    case None => duringKausi(kausi, o.alkuPaiva, o.loppuPaiva)

  }


  def findBy(henkilo: Option[String], vuosi: Option[String], kausi: Option[String]): Seq[Opiskelija] = {
    logger.debug("finding opiskelutiedot by: " + henkilo + ", " + vuosi + ", " + kausi)
    opiskelijat.filter(checkHenkilo(henkilo)).filter(checkVuosiAndKausi(vuosi, kausi))
  }

  def saveOpiskelija(o: Opiskelija) {
    DateTime.nextDay
    opiskelijat = o +: opiskelijat
    opiskelijat
  }

  def checkHenkilo(henkilo: Option[String])(o:Opiskelija):Boolean  =  henkilo match {
    case Some(oid) => o.henkiloOid.equals(oid)
    case None => true
  }


  def during(interval: Interval, start: Date, end: Option[Date]) = end match {
    case Some(date) => new DateTime(start) to new DateTime(date) overlaps interval
    case None => interval.end > new DateTime(start)

  }


  def year(y:Int): Interval = {
    new Interval(newYear(y).toDateTimeAtStartOfDay, 1.year)
  }


  def newYear(y: Int): LocalDate = {
    new MonthDay(1, 1).toLocalDate(y)
  }



  def duringFirstHalf(date: Date):Boolean = {
    new SimpleDateFormat("yyyyMMdd").parse(new SimpleDateFormat("yyyy").format(date) + "0701").after(date)
  }



}
