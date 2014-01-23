package fi.vm.sade.hakurekisteri.actor

import akka.actor.Actor
import java.util.Date
import java.text.SimpleDateFormat
import fi.vm.sade.hakurekisteri.domain.Suoritus
import fi.vm.sade.hakurekisteri.query.SuoritusQuery

class SuoritusActor(var suoritukset:Seq[Suoritus] = Seq()) extends Actor{

  def checkHenkilo(henkilo: Option[String])(s:Suoritus):Boolean  =  henkilo match {
    case Some(oid) => s.henkiloOid.equals(oid)
    case None => true
  }

  def beforeYearEnd(vuosi:String)(date:Date): Boolean = {
    new SimpleDateFormat("yyyyMMdd").parse(vuosi + "1231").after(date)
  }

  def checkVuosi(vuosi: Option[String])(s:Suoritus):Boolean = vuosi match {

    case Some(vuosi:String) => beforeYearEnd(vuosi)(s.arvioituValmistuminen)
    case None => true
  }


  def duringFirstHalf(date: Date):Boolean = {
    new SimpleDateFormat("yyyyMMdd").parse(new SimpleDateFormat("yyyy").format(date) + "0701").after(date)
  }

  def checkKausi(kausi: Option[String])(s: Suoritus):Boolean = kausi match{
    case Some("K") => duringFirstHalf(s.arvioituValmistuminen)
    case Some("S") => !duringFirstHalf(s.arvioituValmistuminen)
    case Some(_) => throw new IllegalArgumentException("not a kausi")
    case None => true
  }

  def receive = {
    case SuoritusQuery(henkilo, kausi, vuosi) =>
      val filter = suoritukset.filter(checkHenkilo(henkilo)).filter(checkVuosi(vuosi)).filter(checkKausi(kausi))
      println(henkilo + " " + kausi + " " + vuosi)
      println(filter)
      sender ! filter
    case s:Suoritus =>

      suoritukset = (suoritukset.toList :+ s).toSeq
      println(suoritukset)
      sender ! suoritukset
  }
}





