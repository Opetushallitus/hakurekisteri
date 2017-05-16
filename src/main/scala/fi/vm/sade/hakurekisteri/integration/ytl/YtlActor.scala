package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, _}
import fi.vm.sade.hakurekisteri.integration.hakemus.IHakemusService
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery, VirallinenSuoritus, yksilollistaminen}
import org.joda.time._

import scala.concurrent.Future
import scala.concurrent.duration._

class YtlActor(suoritusRekisteri: ActorRef, arvosanaRekisteri: ActorRef, hakemusService: IHakemusService, config: Option[YTLConfig]) extends Actor with ActorLogging {
  implicit val ec = context.dispatcher

  var haut = Set[String]()

  def nextSendTime: Option[DateTime] = {
    val times = config.map(_.sendTimes).filter(_.nonEmpty)
    Timer.countNextSend(times)
  }

  if (config.isEmpty) log.warning("Starting ytlActor without config")

  var kokelaat = Map[String, Kokelas]()
  var suoritusKokelaat = Map[UUID, (Suoritus with Identified[UUID], Kokelas)]()

  override def receive: Actor.Receive = {
    case HakuList(current) => haut = current

    case k: Kokelas =>
      log.debug(s"sending ytl data for ${k.oid} yo: ${k.yo} lukio: ${k.lukio}")
      context.actorOf(Props(new YoSuoritusUpdateActor(k.yo, suoritusRekisteri)))
      kokelaat = kokelaat + (k.oid -> k)
      k.lukio foreach (suoritusRekisteri ! _)

    case vs: VirallinenSuoritus with Identified[_] if vs.id.isInstanceOf[UUID] && vs.komo == YoTutkinto.yotutkinto =>
      val s = vs.asInstanceOf[VirallinenSuoritus with Identified[UUID]]
      for (
        kokelas <- kokelaat.get(s.henkiloOid)
      ) {
        context.actorSelection(s.id.toString) ! Identify(s.id)
        kokelaat = kokelaat - kokelas.oid
        suoritusKokelaat = suoritusKokelaat + (s.id -> (s, kokelas))
      }

    case ActorIdentity(id: UUID, Some(ref)) =>
      for (
        (suoritus, kokelas) <- suoritusKokelaat.get(id)
      ) {
        ref ! kokelas
        suoritusKokelaat = suoritusKokelaat - id
      }

    case ActorIdentity(id: UUID, None) =>
      try {
        for (
          (suoritus, kokelas) <- suoritusKokelaat.get(id)
        ) {
          context.actorOf(Props(new ArvosanaUpdateActor(suoritus, kokelas.yoTodistus ++ kokelas.osakokeet, arvosanaRekisteri)), id.toString)
          suoritusKokelaat = suoritusKokelaat - id
        }
      } catch {
        case t: Throwable =>
          context.actorSelection(id.toString) ! Identify(id)
          log.warning(s"problem creating arvosana update for ${id.toString} retrying search", t)
      }

  }
}

case class Kokelas(oid: String,
                   yo: VirallinenSuoritus,
                   lukio: Option[Suoritus],
                   yoTodistus: Seq[Koe],
                   osakokeet: Seq[Koe])

object IsSaving

object Send

object Timer {
  implicit val dtOrder: Ordering[LocalTime] = new Ordering[LocalTime] {
    override def compare(x: LocalTime, y: LocalTime) = x match {
      case _ if x isEqual y => 0
      case _ if x isAfter y => 1
      case _ if y isAfter x => -1
    }
  }

  def countNextSend(timeConf: Option[Seq[LocalTime]]): Option[DateTime] = {
    timeConf.map {
      (times) =>
        times.sorted.map(_.toDateTimeToday).find((t) => {
          val searchTime: DateTime = DateTime.now
          t.isAfter(searchTime)
        }).getOrElse(times.sorted.head.toDateTimeToday.plusDays(1))
    }
  }
}

trait Koe {
  def toArvosana(suoritus: Suoritus with Identified[UUID]): Arvosana
}
case class Aine(aine: String, lisatiedot: String)

object Aine {
  val aineet = Map(
    "SA" -> Aine("A", "SA"),
    "EA" -> Aine("A", "EN"),
    "EB" -> Aine("B", "EN"),
    "HA" -> Aine("A", "UN"),
    "FB" -> Aine("B", "RA"),
    "E2" -> Aine("B", "EN"),
    "M" -> Aine("PITKA", "MA"),
    "SC" -> Aine("C", "SA"),
    "VA" -> Aine("A", "VE"),
    "F2" -> Aine("B", "RA"),
    "GB" -> Aine("B", "PG"),
    "PS" -> Aine("AINEREAALI", "PS"),
    "I" -> Aine("AI", "IS"),
    "HI" -> Aine("AINEREAALI", "HI"),
    "V2" -> Aine("B", "VE"),
    "RY" -> Aine("REAALI", "ET"),
    "TA" -> Aine("A", "IT"),
    "CB" -> Aine("B", "FI"),
    "CC" -> Aine("C", "FI"),
    "S9" -> Aine("SAKSALKOUL", "SA"),
    "G2" -> Aine("B", "PG"),
    "V1" -> Aine("A", "VE"),
    "HB" -> Aine("B", "UN"),
    "TB" -> Aine("B", "IT"),
    "O" -> Aine("AI", "RU"),
    "A" -> Aine("AI", "FI"),
    "P1" -> Aine("A", "ES"),
    "GC" -> Aine("C", "PG"),
    "S2" -> Aine("B", "SA"),
    "PC" -> Aine("C", "ES"),
    "FY" -> Aine("AINEREAALI", "FY"),
    "EC" -> Aine("C", "EN"),
    "L1" -> Aine("D", "LA"),
    "H1" -> Aine("A", "UN"),
    "O5" -> Aine("VI2", "RU"),
    "FA" -> Aine("A", "RA"),
    "CA" -> Aine("A", "FI"),
    "F1" -> Aine("A", "RA"),
    "J" -> Aine("KYPSYYS", "EN"),
    "A5" -> Aine("VI2", "FI"),
    "Z" -> Aine("AI", "ZA"),
    "IC" -> Aine("C", "IS"),
    "KE" -> Aine("AINEREAALI", "KE"),
    "T1" -> Aine("A", "IT"),
    "RO" -> Aine("REAALI", "UO"),
    "YH" -> Aine("AINEREAALI", "YH"),
    "BA" -> Aine("A", "RU"),
    "H2" -> Aine("B", "UN"),
    "BI" -> Aine("AINEREAALI", "BI"),
    "VC" -> Aine("C", "VE"),
    "FF" -> Aine("AINEREAALI", "FF"),
    "BB" -> Aine("B", "RU"),
    "E1" -> Aine("A", "EN"),
    "T2" -> Aine("B", "IT"),
    "DC" -> Aine("C", "ZA"),
    "GE" -> Aine("AINEREAALI", "GE"),
    "P2" -> Aine("B", "ES"),
    "TC" -> Aine("C", "IT"),
    "G1" -> Aine("A", "PG"),
    "UO" -> Aine("AINEREAALI", "UO"),
    "RR" -> Aine("REAALI", "UE"),
    "VB" -> Aine("B", "VE"),
    "KC" -> Aine("C", "KR"),
    "ET" -> Aine("AINEREAALI", "ET"),
    "PB" -> Aine("B", "ES"),
    "SB" -> Aine("B", "SA"),
    "S1" -> Aine("A", "SA"),
    "QC" -> Aine("C", "QC"),
    "N" -> Aine("LYHYT", "MA"),
    "L7" -> Aine("C", "LA"),
    "PA" -> Aine("A", "ES"),
    "FC" -> Aine("C", "RA"),
    "TE" -> Aine("AINEREAALI", "TE"),
    "GA" -> Aine("A", "PG"),
    "UE" -> Aine("AINEREAALI", "UE"),
    "W" -> Aine("AI", "QS")
  )

  def apply(koetunnus:String, aineyhdistelmärooli: Option[String] = None):Aine =
    if (aineyhdistelmärooli == Some("22"))
      Aine("TOINENKIELI", aineet(koetunnus).lisatiedot)
    else
      aineet(koetunnus)
}
object YoTutkinto {
  val YTL: String = Oids.ytlOrganisaatioOid
  val yotutkinto = Oids.yotutkintoKomoOid

  def apply(suorittaja: String, valmistuminen: LocalDate, kieli: String, valmis: Boolean = true, vahvistettu: Boolean = true) = {
    VirallinenSuoritus(
      komo = yotutkinto,
      myontaja = YTL,
      tila = if (valmis) "VALMIS" else "KESKEN",
      valmistuminen = valmistuminen,
      henkilo = suorittaja,
      yksilollistaminen = yksilollistaminen.Ei,
      suoritusKieli = kieli,
      vahv = vahvistettu,
      lahde = YTL)
  }
}
case class Osakoe(arvio: ArvioOsakoe, koetunnus: String, osakoetunnus: String, aineyhdistelmarooli: String, myonnetty: LocalDate) extends Koe {
  val aine = Aine(koetunnus, Some(aineyhdistelmarooli))
  val isValinnainen = aineyhdistelmarooli != null && aineyhdistelmarooli.toInt >= 60
  val lahdeArvot = Map("koetunnus" -> koetunnus, "aineyhdistelmarooli" -> aineyhdistelmarooli)

  def toArvosana(suoritus: Suoritus with Identified[UUID]) = {
    Arvosana(suoritus.id, arvio, aine.aine + "_" + osakoetunnus: String, Some(aine.lisatiedot), isValinnainen: Boolean, Some(myonnetty), YoTutkinto.YTL, lahdeArvot)
  }
}

case class YoKoe(arvio: ArvioYo, koetunnus: String, aineyhdistelmarooli: String, myonnetty: LocalDate) extends Koe {
  val aine = Aine(koetunnus, Some(aineyhdistelmarooli))
  val isValinnainen = aineyhdistelmarooli != null && aineyhdistelmarooli.toInt >= 60
  val lahdeArvot = Map("koetunnus" -> koetunnus, "aineyhdistelmarooli" -> aineyhdistelmarooli)

  def toArvosana(suoritus: Suoritus with Identified[UUID]):Arvosana = {
    Arvosana(suoritus.id, arvio, aine.aine: String, Some(aine.lisatiedot), isValinnainen: Boolean, Some(myonnetty), YoTutkinto.YTL, lahdeArvot)
  }
}

class YoSuoritusUpdateActor(yoSuoritus: VirallinenSuoritus, suoritusRekisteri: ActorRef) extends Actor {
  private def ennenVuotta1990Valmistuneet(s: Seq[_]) = s.map {
    case v: VirallinenSuoritus with Identified[_] if v.id.isInstanceOf[UUID] =>
      v.asInstanceOf[VirallinenSuoritus with Identified[UUID]]
  }.filter(s => s.valmistuminen.isBefore(new LocalDate(1990, 1, 1)) && s.tila == "VALMIS" && s.vahvistettu)

  override def receive: Actor.Receive = {
    case s: Seq[_] =>
      fetch.foreach(_.cancel())
      if (s.isEmpty) {
        suoritusRekisteri ! yoSuoritus
      } else {
        val suoritukset = ennenVuotta1990Valmistuneet(s)
        if (suoritukset.nonEmpty) {
          context.parent ! suoritukset.head
          context.stop(self)
        } else {
          suoritusRekisteri ! yoSuoritus
        }
      }
    case v: VirallinenSuoritus with Identified[_] if v.id.isInstanceOf[UUID] =>
      context.parent ! v.asInstanceOf[VirallinenSuoritus with Identified[UUID]]
      context.stop(self)
  }

  var fetch: Option[Cancellable] = None

  override def preStart(): Unit = {
    implicit val ec = context.dispatcher
    fetch = Some(context.system.scheduler.schedule(1.millisecond, 130.seconds, suoritusRekisteri, SuoritusQuery(henkilo = Some(yoSuoritus.henkilo), komo = Some(Oids.yotutkintoKomoOid))))
  }
}


class ArvosanaUpdateActor(suoritus: Suoritus with Identified[UUID], var kokeet: Seq[Koe], arvosanaRekisteri: ActorRef) extends Actor {
  def isKorvaava(old: Arvosana) = (uusi: Arvosana) =>
    uusi.aine == old.aine && uusi.myonnetty == old.myonnetty && uusi.lisatieto == old.lisatieto && uusi.lahdeArvot == old.lahdeArvot

  override def receive: Actor.Receive = {
    case s: Seq[_] =>
      fetch.foreach(_.cancel())
      val uudet = kokeet.map(_.toArvosana(suoritus))
      s.map {
        case (as: Arvosana with Identified[_]) if as.id.isInstanceOf[UUID] =>
          val a = as.asInstanceOf[Arvosana with Identified[UUID]]
          val korvaava = uudet.find(isKorvaava(a))
          if (korvaava.isDefined) korvaava.get.identify(a.id)
          else a
      } foreach (arvosanaRekisteri ! _)
      uudet.filterNot((uusi) => s.exists { case old: Arvosana => isKorvaava(old)(uusi) }) foreach (arvosanaRekisteri ! _)
      context.stop(self)
    case Kokelas(_, _, _ , todistus, osakokeet) => kokeet = todistus ++ osakokeet
  }

  var fetch: Option[Cancellable] = None

  override def preStart(): Unit = {
    implicit val ec = context.dispatcher
    fetch = Some(context.system.scheduler.schedule(1.millisecond, 130.seconds, arvosanaRekisteri, ArvosanaQuery(suoritus.id)))
  }
}

case class YTLConfig(host: String, username: String, password: String, inbox: String, outbox: String, sendTimes: Seq[LocalTime], localStore: String)

case class HakuList(haut: Set[String])

case class HakuException(message: String, haku: String, cause: Throwable) extends Exception(message, cause)
