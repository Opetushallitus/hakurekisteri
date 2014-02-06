package fi.vm.sade.hakurekisteri.suoritus

import java.util.{UUID, Date}
import java.text.SimpleDateFormat
import fi.vm.sade.hakurekisteri.rest.support.{Query, Kausi}
import Kausi._
import fi.vm.sade.hakurekisteri.storage.{ResourceActor, ResourceService, Identified, Repository}


trait SuoritusRepository extends Repository[Suoritus] {


  var store:Map[UUID,Suoritus with Identified] = Map()

  def identify(o:Suoritus): Suoritus with Identified = o match {
    case o: Suoritus with Identified => o
    case _ => Suoritus.identify(o)
  }

  def save(o: Suoritus ): Suoritus with Identified = {
    val oid = identify(o)
    store = store + (oid.id -> oid)
    oid
  }

  def listAll(): Seq[Suoritus with Identified] = {
    store.values.toSeq
  }



}

trait SuoritusService extends ResourceService[Suoritus] { this: Repository[Suoritus] =>
  def findBy(q: Query[Suoritus]): Seq[Suoritus with Identified] = q match  {
    case SuoritusQuery(henkilo, kausi, vuosi) => listAll().filter(checkHenkilo(henkilo)).filter(checkVuosi(vuosi)).filter(checkKausi(kausi))
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

class SuoritusActor(val initialSuoritukset:Seq[Suoritus] = Seq()) extends ResourceActor[Suoritus] with SuoritusRepository with SuoritusService {

  initialSuoritukset.foreach((o) => save(o))

}





