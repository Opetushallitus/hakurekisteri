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
import scala.language.implicitConversions


trait SuoritusRepository extends JournaledRepository[Suoritus, UUID] {
  var tiedonSiirtoIndex: Map[String, Map[String, Seq[Suoritus with Identified[UUID]]]] = Option(tiedonSiirtoIndex).getOrElse(Map())

  var suoritusTyyppiIndex: Map[String, Map[String, Seq[VirallinenSuoritus with Identified[UUID]]]] = Option(suoritusTyyppiIndex).getOrElse(Map())

  var myontajaIndex: Map[(String, String), Seq[Suoritus with Identified[UUID]]] = Option(myontajaIndex).getOrElse(Map())

  var komoIndex: Map[String, Seq[VirallinenSuoritus with Identified[UUID]]] = Option(komoIndex).getOrElse(Map())

  def year(suoritus:Suoritus): String = suoritus match {
    case s: VirallinenSuoritus =>  s.valmistuminen.getYear.toString
    case s: VapaamuotoinenSuoritus => s.vuosi.toString
  }

  def addNew(suoritus: Suoritus with Identified[UUID]) = {
    tiedonSiirtoIndex = Option(tiedonSiirtoIndex).getOrElse(Map())

    val newIndexSeq: Seq[Suoritus with Identified[UUID]] =  suoritus +: tiedonSiirtoIndex.get(suoritus.henkiloOid).flatMap((i) => i.get(year(suoritus))).getOrElse(Seq())
    val newHenk: Map[String, Seq[Suoritus with Identified[UUID]]] = tiedonSiirtoIndex.getOrElse(suoritus.henkiloOid, Map()) + (year(suoritus) -> newIndexSeq)
    tiedonSiirtoIndex = tiedonSiirtoIndex + (suoritus.henkiloOid -> newHenk)

    suoritusTyyppiIndex = Option(suoritusTyyppiIndex).getOrElse(Map())
    for (
      virallinen: VirallinenSuoritus with Identified[UUID] <- Option(suoritus).collect{case v:VirallinenSuoritus => v}
    ) {
      val newTyypiIndexSeq: Seq[VirallinenSuoritus with Identified[UUID]] = virallinen +: suoritusTyyppiIndex.get(virallinen.henkiloOid).flatMap(_.get(virallinen.komo)).getOrElse(Seq())
      val newTyyppiHenk: Map[String, Seq[VirallinenSuoritus with Identified[UUID]]] =  suoritusTyyppiIndex.getOrElse(suoritus.henkiloOid, Map()) + (virallinen.komo -> newTyypiIndexSeq)
      suoritusTyyppiIndex = suoritusTyyppiIndex + (virallinen.henkiloOid -> newTyyppiHenk)
    }

    myontajaIndex = Option(myontajaIndex).getOrElse(Map())
    for (
      virallinen <- Option(suoritus).collect { case v: VirallinenSuoritus => v }
    ) myontajaIndex = myontajaIndex + ((virallinen.myontaja, virallinen.valmistuminen.getYear.toString) -> (myontajaIndex.getOrElse((virallinen.myontaja, virallinen.valmistuminen.getYear.toString), Seq()) :+ virallinen))

    komoIndex = Option(komoIndex).getOrElse(Map())
    komoIndex = suoritus match {
      case virallinen: VirallinenSuoritus =>
        komoIndex.updated(virallinen.komo, virallinen +: komoIndex.getOrElse(virallinen.komo, Seq()))
      case _ => komoIndex
    }
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

      suoritusTyyppiIndex = Option(suoritusTyyppiIndex).getOrElse(Map())
      for (
        virallinen: VirallinenSuoritus with Identified[UUID] <- Option(suoritus).collect{ case v: VirallinenSuoritus => v };
        newSeq: Seq[VirallinenSuoritus with Identified[UUID]] <- suoritusTyyppiIndex.get(virallinen.henkiloOid).flatMap((i) => i.get(virallinen.komo)).map(_.filter((s) => s != virallinen || s.id != virallinen.id));
        henk: Map[String, Seq[VirallinenSuoritus with Identified[UUID]]] <- suoritusTyyppiIndex.get(virallinen.henkiloOid).map((henk) => henk + (year(suoritus) -> newSeq))
      ) suoritusTyyppiIndex = suoritusTyyppiIndex + (virallinen.henkiloOid -> henk)

      myontajaIndex = Option(myontajaIndex).getOrElse(Map())
      for (
        virallinen <- Option(suoritus).collect { case v: VirallinenSuoritus => v }
      ) {
        val suoritukset = myontajaIndex.getOrElse((virallinen.myontaja, virallinen.valmistuminen.getYear.toString), Seq()).filterNot(_.id == virallinen.id)
        if (suoritukset.isEmpty)
          myontajaIndex = myontajaIndex - ((virallinen.myontaja, virallinen.valmistuminen.getYear.toString))
        else
          myontajaIndex = myontajaIndex + ((virallinen.myontaja, virallinen.valmistuminen.getYear.toString) -> suoritukset)
      }

      komoIndex = Option(komoIndex).getOrElse(Map())
      komoIndex = suoritus match {
        case virallinen: VirallinenSuoritus =>
          komoIndex.get(virallinen.komo) map (_ filterNot ((s) => s.id == virallinen.id)) match {
            case Some(suoritukset) if suoritukset.nonEmpty => komoIndex.updated(virallinen.komo, suoritukset)
            case Some(_) => komoIndex - virallinen.komo
            case None => komoIndex
          }
        case _ => komoIndex
      }
    }

    old.foreach(removeOld)
    current.foreach(addNew)
  }
}

trait SuoritusService extends InMemQueryingResourceService[Suoritus, UUID] with SuoritusRepository {

  override val emptyQuery: PartialFunction[Query[Suoritus], Boolean] = {
    case SuoritusQuery(None, None, None, None, None, None) => true
  }

  override val optimize: PartialFunction[Query[Suoritus], Future[Seq[Suoritus with Identified[UUID]]]] = {
    case SuoritusQuery(Some(henkilo), None, Some(vuosi), None, None, None) =>
      Future {
        tiedonSiirtoIndex.get(henkilo).flatMap(_.get(vuosi)).getOrElse(Seq())
      }

    case SuoritusQuery(Some(henkilo), kausi, Some(vuosi), myontaja, komo, muokattuJalkeen) =>
      Future {
        tiedonSiirtoIndex.get(henkilo).flatMap(_.get(vuosi)).getOrElse(Seq())
      } flatMap(filtered => executeQuery(filtered)(SuoritusQuery(Some(henkilo), kausi, Some(vuosi), myontaja, komo, muokattuJalkeen)))

    case SuoritusQuery(Some(henkilo), None, None, None, None, None) =>
      Future { getByHenkilo(henkilo) }

    case SuoritusQuery(None, None, Some(vuosi), Some(myontaja), None, None) =>
      Future { myontajaIndex.getOrElse((myontaja, vuosi), Seq()) }

    case SuoritusQuery(None, None, None, Some(myontaja), None, None) =>
      Future {
        myontajaIndex.collect {
          case ((oid, _), value) if oid == myontaja => value
        }.foldLeft[Seq[Suoritus with Identified[UUID]]](Seq())(_ ++ _).distinct
      }

    case SuoritusQuery(None, None, None, None, Some(komo), None) =>
      Future { komoIndex.getOrElse(komo, Seq()) }

    case SuoritusQuery(None, None, None, None, Some(komo), Some(muokattuJalkeen)) =>
      Future { komoIndex.getOrElse(komo, Seq()) } flatMap ((filtered) =>
        executeQuery(filtered)(SuoritusQuery(None, None, None, None, None, Some(muokattuJalkeen)))
      )

    case SuoritusQuery(Some(henkilo), kausi, vuosi, myontaja, komo, muokattuJalkeen) =>
      Future {
        tiedonSiirtoIndex.get(henkilo).map(_.values.reduce(_ ++ _)).getOrElse(Seq())
      } flatMap(filtered => executeQuery(filtered)(SuoritusQuery(Some(henkilo), kausi, vuosi, myontaja, komo, muokattuJalkeen)))

    case SuoritusHenkilotQuery(henkilot) =>
      Future {
        tiedonSiirtoIndex.collect {
          case (oid, value) if henkilot.contains(oid) => value
        }.map(_.values.foldLeft[Seq[Suoritus with Identified[UUID]]](Seq())(_ ++ _)).
          foldLeft[Seq[Suoritus with Identified[UUID]]](Seq())(_ ++ _)
      }

    case SuoritysTyyppiQuery(henkilo, komo) => Future {
      (for (
        henk <- suoritusTyyppiIndex.get(henkilo);
        suoritukset <- henk.get(komo)
      ) yield suoritukset).getOrElse(Seq())
    }

    case AllForMatchinHenkiloSuoritusQuery(Some(vuosi), Some(myontaja)) =>
      Future { myontajaIndex.getOrElse((myontaja, vuosi), Seq()) }.map(_.flatMap(s => getByHenkilo(s.henkiloOid)).toSet.toSeq)

    case AllForMatchinHenkiloSuoritusQuery(None, Some(myontaja)) =>
      Future {
        myontajaIndex.collect {
          case ((oid, _), value) if oid == myontaja => value
        }.foldLeft[Seq[Suoritus with Identified[UUID]]](Seq())(_ ++ _).distinct
      }.map(_.flatMap(s => getByHenkilo(s.henkiloOid)).toSet.toSeq)

  }


  def getByHenkilo(henkilo: String): Seq[Suoritus with Identified[UUID]] = {
    tiedonSiirtoIndex.get(henkilo).map(_.values.reduce(_ ++ _)).getOrElse(Seq())
  }

  val matcher: PartialFunction[Query[Suoritus], (Suoritus with Identified[UUID]) => Boolean] = {
    case SuoritusQuery(henkilo, kausi, vuosi, myontaja, komo, muokattuJalkeen) => (s: Suoritus with Identified[UUID]) =>
      checkHenkilo(henkilo)(s) && checkVuosi(vuosi)(s) && checkKausi(kausi)(s) && checkMyontaja(myontaja)(s) && checkKomoOption(komo)(s)

    case SuoritysTyyppiQuery(henkilo, komo) => (s: Suoritus with Identified[UUID]) =>
      checkHenkilo(Some(henkilo))(s)  && checkKomo(komo)(s)
  }

  def checkKomoOption(komo: Option[String])(s: Suoritus with Identified[UUID]) = (komo, s) match {
    case (Some(k), v: VirallinenSuoritus) if v.komo == k => true
    case (Some(_), _) => false
    case (None, _) => true
  }

  def checkKomo(komo:String)(s: Suoritus with Identified[UUID]) = s match {
    case v: VirallinenSuoritus => v.komo == komo
    case _ => false
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

class SuoritusActor(val journal:Journal[Suoritus, UUID] = new InMemJournal[Suoritus, UUID]) extends ResourceActor[Suoritus, UUID] with SuoritusService {
  override val logger = Logging(context.system, this)
}
