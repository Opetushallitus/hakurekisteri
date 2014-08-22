package fi.vm.sade.hakurekisteri.suoritus

import fi.vm.sade.hakurekisteri.rest.support.{Query, Kausi}
import Kausi._
import fi.vm.sade.hakurekisteri.storage._
import com.github.nscala_time.time.Imports._
import fi.vm.sade.hakurekisteri.storage.repository._
import scala.Some
import scala.concurrent.Future
import java.util.UUID


trait SuoritusRepository extends JournaledRepository[Suoritus, UUID] {
  var tiedonSiirtoIndex: Map[String, Map[String, Seq[Suoritus with Identified[UUID]]]] = Option(tiedonSiirtoIndex).getOrElse(Map())
  //var tiedonSiirtoIndexSnapShot: Map[String, Map[String, Seq[Suoritus with Identified]]] = Option(tiedonSiirtoIndexSnapShot).getOrElse(Map())

  def addNew(suoritus: Suoritus with Identified[UUID]) = {
    tiedonSiirtoIndex = Option(tiedonSiirtoIndex).getOrElse(Map())
    val newIndexSeq =  suoritus +: tiedonSiirtoIndex.get(suoritus.henkiloOid).flatMap((i) => i.get(suoritus.valmistuminen.getYear.toString)).getOrElse(Seq())
    val newHenk = tiedonSiirtoIndex.get(suoritus.henkiloOid).getOrElse(Map()) + (suoritus.valmistuminen.getYear.toString -> newIndexSeq)
    tiedonSiirtoIndex = tiedonSiirtoIndex + (suoritus.henkiloOid -> newHenk)
  }

  override def index(old: Option[Suoritus with Identified[UUID]], current: Option[Suoritus with Identified[UUID]]) {
    def removeOld(suoritus: Suoritus with Identified[UUID]) = {
      tiedonSiirtoIndex = Option(tiedonSiirtoIndex).getOrElse(Map())
      val newIndexSeq = tiedonSiirtoIndex.get(suoritus.henkiloOid).flatMap((i) => i.get(suoritus.valmistuminen.getYear.toString)).map(_.filter((s) => s != suoritus || s.id != suoritus.id))
      val newHenkiloIndex: Option[Map[String, Seq[Suoritus with Identified[UUID]]]] = newIndexSeq.flatMap((newSeq) =>
        tiedonSiirtoIndex.get(suoritus.henkiloOid).map((henk) => henk + (suoritus.valmistuminen.getYear.toString -> newSeq))
      )
      val newIndex = newHenkiloIndex.map((henk)=>
        tiedonSiirtoIndex + (suoritus.henkiloOid -> henk)
      )

      tiedonSiirtoIndex = newIndex.getOrElse(tiedonSiirtoIndex)
    }

    old.foreach(removeOld)
    current.foreach(addNew)
  }

  def identify(o:Suoritus): Suoritus with Identified[UUID] = Suoritus.identify(o)
}

trait SuoritusService extends ResourceService[Suoritus, UUID] with SuoritusRepository {
  override val optimize:PartialFunction[Query[Suoritus], Future[Seq[Suoritus with Identified[UUID]]]] = {
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

  def checkMyontaja(myontaja: Option[String])(s: Suoritus):Boolean = myontaja match {
    case Some(oid) => s.myontaja.equals(oid)
    case None => true
  }

  def checkHenkilo(henkilo: Option[String])(s: Suoritus):Boolean = henkilo match {
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

class SuoritusActor(val journal:Journal[Suoritus, UUID] = new InMemJournal[Suoritus, UUID]) extends ResourceActor[Suoritus, UUID] with SuoritusRepository with SuoritusService {

}





