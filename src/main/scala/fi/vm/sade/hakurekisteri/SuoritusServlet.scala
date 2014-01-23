package fi.vm.sade.hakurekisteri

import _root_.akka.actor.{Actor, ActorRef, ActorSystem}
import _root_.akka.pattern.ask

import _root_.akka.util.Timeout
import org.scalatra._
import scalate.ScalateSupport
import org.json4s.{Formats, DefaultFormats}
import org.scalatra.json._
import scala.concurrent.{ExecutionContext, Future}
import java.util.Date
import java.text.SimpleDateFormat

class SuoritusServlet(system: ActorSystem, suoritusActor: ActorRef) extends HakuJaValintarekisteriStack with JacksonJsonSupport with FutureSupport {

  protected implicit def executor: ExecutionContext = system.dispatcher

  protected implicit val jsonFormats: Formats = DefaultFormats

  implicit val defaultTimeout = Timeout(10)



  get("/") {

    contentType = formats("json")

    new AsyncResult() {
      val is = suoritusActor ? SuoritusQuery(params)
    }
  }



  post("/") {
    suoritusActor ! parsedBody.extract[Suoritus]
    Accepted()
  }
  
}

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
    case Some("S") => true
    case Some(_) => throw new IllegalArgumentException("not a kausi")
    case None => true
  }

  def receive = {
    case SuoritusQuery(henkilo, kausi, vuosi) =>  {

      sender ! suoritukset.filter(checkHenkilo(henkilo)).filter(checkVuosi(vuosi)).filter(checkKausi(kausi))

    }
    case s:Suoritus => {
      suoritukset = (suoritukset.toList :+ s).toSeq
      sender ! suoritukset
    }
  }
}

case class Suoritus(opilaitosOid: String, tila: String, luokkataso: String, arvioituValmistuminen: Date, luokka: String, henkiloOid: String)

case class SuoritusQuery(henkilo: Option[String], kausi: Option[String], vuosi: Option[String])

object SuoritusQuery{
  def apply(params: Map[String,String]): SuoritusQuery = {
    SuoritusQuery(params.get("henkilo"), params.get("kausi"), params.get("vuosi"))
  }
}
