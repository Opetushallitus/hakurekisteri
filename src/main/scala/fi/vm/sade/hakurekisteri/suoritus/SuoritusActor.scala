package fi.vm.sade.hakurekisteri.suoritus

import akka.actor.Actor
import java.util.Date
import java.text.SimpleDateFormat
import fi.vm.sade.hakurekisteri.rest.support.Kausi
import Kausi._

class SuoritusActor(var suoritukset:Seq[Suoritus] = Seq()) extends Actor{

  def receive: Receive = {
    case SuoritusQuery(henkilo, kausi, vuosi) =>
      sender ! findBy(henkilo, vuosi, kausi)
    case s:Suoritus =>
      sender ! saveSuoritus(s)
  }

  def findBy(henkilo: Option[String], vuosi: Option[String], kausi: Option[Kausi]): Seq[Suoritus] = {
    suoritukset.filter(checkHenkilo(henkilo)).filter(checkVuosi(vuosi)).filter(checkKausi(kausi))
  }

  def saveSuoritus(s: Suoritus) {
    suoritukset = (suoritukset.toList :+ s).toSeq
    suoritukset
  }

  def checkHenkilo(henkilo: Option[String])(s:Suoritus):Boolean  =  henkilo match {
    case Some(oid) => s.henkiloOid.equals(oid)
    case None => true
  }

  def beforeYearEnd(vuosi:String)(date:Date): Boolean = {
    new SimpleDateFormat("yyyyMMdd").parse(vuosi + "1231").after(date)
  }

  def checkVuosi(vuosi: Option[String])(s:Suoritus):Boolean = vuosi match {

    case Some(vuosi:String) => beforeYearEnd(vuosi)(s.valmistuminen)
    case None => true
  }

  def checkKausi(kausi: Option[Kausi])(s: Suoritus):Boolean = kausi match{
    case Some(Kevät) => duringFirstHalf(s.valmistuminen)
    case Some(Syksy) => !duringFirstHalf(s.valmistuminen)
    case None => true
  }

  def duringFirstHalf(date: Date):Boolean = {
    new SimpleDateFormat("yyyyMMdd").parse(new SimpleDateFormat("yyyy").format(date) + "0701").after(date)
  }







}





