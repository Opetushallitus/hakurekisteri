package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.UUID

import akka.Done
import akka.actor._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, _}
import fi.vm.sade.hakurekisteri.integration.hakemus.IHakemusService
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.storage.{Identified, UpsertResource}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery, VirallinenSuoritus, yksilollistaminen, _}
import org.joda.time._
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait KokelasPersister {
  def persistSingle(kokelas: KokelasWithPersonAliases)(implicit ec: ExecutionContext): Future[Unit]
  def persistMultiple(kokelakset: Iterator[Kokelas], personOidsWithAliases: PersonOidsWithAliases,
                      parallelism: Int = 1)(implicit ec: ExecutionContext): Future[Unit]
}

class YtlKokelasPersister(system: ActorSystem,
                          suoritusRekisteri: ActorRef,
                          arvosanaRekisteri: ActorRef,
                          hakemusService: IHakemusService,
                          timeout: Timeout,
                          howManyTries: Int) extends KokelasPersister {
  private val logger = LoggerFactory.getLogger(getClass)
  implicit val materializer: ActorMaterializer = ActorMaterializer()(context = system)

  override def persistSingle(k: KokelasWithPersonAliases)(implicit ec: ExecutionContext): Future[Unit] = {
    val kokelas = k.kokelas
    logger.debug(s"sending ytl data for kokelas ${kokelas.oid} yo=${kokelas.yo} timeout=${timeout.duration}")
    val yoSuoritusUpdateActor: ActorRef = system.actorOf(YoSuoritusUpdateActor.props(
      kokelas.yo,
      k.personOidsWithAliases,
      suoritusRekisteri))
    val arvosanaUpdateActor: ActorRef = system.actorOf(ArvosanaUpdateActor.props(
      kokelas.yoTodistus ++ kokelas.osakokeet,
      arvosanaRekisteri, timeout.duration))

    def arvosanaUpdateWithRetries(suoritus: Suoritus with Identified[UUID]): Future[Unit] = {
      def arvosanaUpdateRetry(remainingRetries: Int): Future[Unit] = {
        val suoritusTxt = s"henkilö=${suoritus.henkiloOid} suoritus=${suoritus.id}"
        logger.debug(s"ArvosanaUpdate send suoritus to Actor for $suoritusTxt")
        (arvosanaUpdateActor ? ArvosanaUpdateActor.Update(suoritus))(timeout).mapTo[Unit].recoverWith {
          case t: Throwable =>
            logger.error(s"ArvosanaUpdate Got exception when updating $suoritusTxt, retries left=${remainingRetries-1}", t)
            if (remainingRetries <= 1) {
              Future.failed(new RuntimeException(s"ArvosanaUpdate: Run out of retries $suoritusTxt", t))
            } else {
              arvosanaUpdateRetry(remainingRetries - 1)
            }
        }
      }
      arvosanaUpdateRetry(howManyTries)
    }

    val allOperations = for {
      virallinenSuoritus <- akka.pattern.ask(yoSuoritusUpdateActor, YoSuoritusUpdateActor.Update)(timeout).mapTo[VirallinenSuoritus with Identified[UUID]]
      if (virallinenSuoritus.id.isInstanceOf[UUID] && virallinenSuoritus.komo == YoTutkinto.yotutkinto)
      arvosanaUpdateResult <- arvosanaUpdateWithRetries(virallinenSuoritus)
    } yield arvosanaUpdateResult

    def stopTemporaryActors(): Unit = {
      system.stop(yoSuoritusUpdateActor)
      system.stop(arvosanaUpdateActor)
    }

    def addCleanerCallbacks(): Unit = {
      allOperations onComplete {
        case Success(_) =>
          logger.debug(s"KokelasPersister succeeded for ${kokelas.oid}")
          stopTemporaryActors()
        case Failure(ex) =>
          logger.error(s"KokelasPersister failed for ${kokelas.oid}", ex)
          stopTemporaryActors()
      }
    }

    addCleanerCallbacks()
    allOperations
  }

  override def persistMultiple(kokelakset: Iterator[Kokelas], personOidsWithAliases: PersonOidsWithAliases,
                               parallelism: Int = 1)(implicit ec: ExecutionContext): Future[Unit] = {
    logger.debug(s"persistMultiple: parallelism=$parallelism")
    val doneFuture: Future[Done] = Source
      .fromIterator(() => kokelakset)
      .mapAsync(parallelism = parallelism)(kokelas => persistSingle(KokelasWithPersonAliases(kokelas, personOidsWithAliases.intersect(Set(kokelas.oid)))))
      .runForeach {identity}
    doneFuture.flatMap {_ => Future.unit}
  }
}

case class KokelasWithPersonAliases(kokelas: Kokelas, personOidsWithAliases: PersonOidsWithAliases)

case class Kokelas(oid: String,
                   yo: VirallinenSuoritus,
                   yoTodistus: Seq[Koe],
                   osakokeet: Seq[Koe])

trait Koe {
  def isValinnainenRooli(aineyhdistelmarooli: String) = aineyhdistelmarooli != null && aineyhdistelmarooli.equals("optional-subject")
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

  def apply(suorittaja: String, valmistuminen: LocalDate, kieli: String, valmis: Boolean = true, lahdeArvot: Map[String,String], vahvistettu: Boolean = true) = {
    VirallinenSuoritus(
      komo = yotutkinto,
      myontaja = YTL,
      tila = if (valmis) "VALMIS" else "KESKEN",
      valmistuminen = valmistuminen,
      henkilo = suorittaja,
      yksilollistaminen = yksilollistaminen.Ei,
      suoritusKieli = kieli,
      vahv = vahvistettu,
      lahde = YTL,
      lahdeArvot = lahdeArvot)
  }
}
private object Koe {
  private val EXAM_ROLE_CONVERTER: Map[String, Map[String, String]] = Map("mother-tongue" -> Map(
    "A" -> "11",
    "Z" -> "11",
    "O" -> "11",
    "W" -> "11",
    "I" -> "11",
    "J" -> "13",
    "A5" -> "14",
    "O5" -> "14"
  ), "mandatory-subject" -> Map("CA" -> "21",
    "S2" -> "21",
    "T1" -> "21",
    "E2" -> "21",
    "P1" -> "21",
    "F1" -> "21",
    "CB" -> "21",
    "V2" -> "21",
    "S1" -> "21",
    "P2" -> "21",
    "V1" -> "21",
    "G2" -> "21",
    "F2" -> "21",
    "T2" -> "21",
    "E1" -> "21",
    "H2" -> "21",
    "BB" -> "21",
    //"RR","21",
    //"VC","21",
    //"PA","21",
    //"M","21",
    //"FA","21",
    //"N","21",
    //"SC","21",
    //"SA","21",
    //"BA","21",
    //"PC","21",
    //"EA","21",
    //"FC","21",
    //"TC","21",
    //"VA","21",
    //"EC","21",
    /* END OF 21 */

    "O" -> "22",
    "W" -> "22",
    "Z" -> "22",
    "A" -> "22",
    /* END OF 22 */
    "PC" -> "31",
    "DC" -> "31",
    "TC" -> "31",
    "SC" -> "31",
    "SA" -> "31",
    "BA" -> "21",
    //"BB","31",
    "FC" -> "31",
    "EA" -> "31",
    "PA" -> "31",
    "L1" -> "31",
    "VC" -> "31",
    "CC" -> "31",
    "EB" -> "31",
    "EC" -> "31",
    "S9" -> "31",
    "L7" -> "31",
    "VA" -> "31",
    "FA" -> "31",
    /* END OF 31 */

    "UE" -> "41",
    "RY" -> "41",
    "HI" -> "41",
    "GE" -> "41",
    "PS" -> "41",
    "YH" -> "41",
    "FY" -> "41",
    "RO" -> "41",
    "TE" -> "41",
    "BI" -> "41",
    "KE" -> "41",
    "FF" -> "41",
    "UO" -> "41",
    "ET" -> "41",
    "RR" -> "41",
    "M" -> "42",
    "N" -> "42"
  ), "optional-subject" -> Map("A" -> "60",
    "O" -> "60",
    "Z" -> "60",
    "I" -> "60",
    "W" -> "60",
    /* END OF 60 */
    "L7" -> "61",
    "VA" -> "61",
    "IC" -> "61",
    "L1" -> "61",
    "VC" -> "61",
    "EB" -> "61",
    "PC" -> "61",
    "DC" -> "61",
    "SA" -> "61",
    "S9" -> "61",
    "EC" -> "61",
    "FA" -> "61",
    "KC" -> "61",
    "TC" -> "61",
    "GC" -> "61",
    "QC" -> "61",
    "P2" -> "61",
    "EA" -> "61",
    "FC" -> "61",
    "PA" -> "61",
    "SC" -> "61",
    /* END OF 61 */
    "CA" -> "62",
    "BB" -> "62",
    "A5" -> "62",
    "O5" -> "62",
    "BA" -> "62",
    "CB" -> "62",
    /* END OF 71 */
    "RR" -> "71",
    "PS" -> "71",
    "UE" -> "71",
    "GE" -> "71",
    "RY" -> "71",
    "KE" -> "71",
    "FF" -> "71",
    "YH" -> "71",
    "HI" -> "71",
    "ET" -> "71",
    "TE" -> "71",
    "RO" -> "71",
    "FY" -> "71",
    "UO" -> "71",
    "BI" -> "71",
    /* END OF 71 */
    "M" -> "81",
    "N" -> "81"
  ))

  def convertToOldRole(id: String, newRole: String, henkiloOid: String): String = {
    val v: Map[String, String] = EXAM_ROLE_CONVERTER.getOrElse(newRole, throw new RuntimeException(s"(Hakija: ${henkiloOid} ) Unrecognized examRole: ${newRole}"))
    v.getOrElse(id, throw new RuntimeException(s"(Hakija: ${henkiloOid} ) Unrecognized examRole and examId pair: ${newRole} => ${id}"))
  }

  def lahdeArvot(koetunnus: String, aineyhdistelmarooli: String, aineyhdistelmarooliLegacy: Option[Int], henkiloOid: String): Map[String, String] = {
    aineyhdistelmarooliLegacy match {
      case Some(oldRooli) =>
        Map("koetunnus" -> koetunnus, "aineyhdistelmarooli" -> oldRooli.toString)
      case _ =>
        Map("koetunnus" -> koetunnus, "aineyhdistelmarooli" -> convertToOldRole(koetunnus, aineyhdistelmarooli, henkiloOid))
    }
  }
}
case class Osakoe(arvio: ArvioOsakoe, koetunnus: String, osakoetunnus: String, aineyhdistelmarooli: String, aineyhdistelmarooliLegacy: Option[Int], myonnetty: LocalDate, personOid: String) extends Koe {
  val aine = Aine(koetunnus, Some(Koe.convertToOldRole(koetunnus, aineyhdistelmarooli, personOid)))
  val isValinnainen = isValinnainenRooli(aineyhdistelmarooli)

  def toArvosana(suoritus: Suoritus with Identified[UUID]) = {
    Arvosana(suoritus.id, arvio, aine.aine + "_" + osakoetunnus: String,
      Some(aine.lisatiedot),
      isValinnainen: Boolean,
      Some(myonnetty),
      YoTutkinto.YTL,
      Koe.lahdeArvot(koetunnus, aineyhdistelmarooli, aineyhdistelmarooliLegacy, suoritus.henkiloOid))
  }
}

case class YoKoe(arvio: ArvioYo, koetunnus: String, aineyhdistelmarooli: String, aineyhdistelmarooliLegacy: Option[Int], myonnetty: LocalDate, personOid: String) extends Koe {
  val aine = Aine(koetunnus, Some(Koe.convertToOldRole(koetunnus, aineyhdistelmarooli, personOid)))
  val isValinnainen = isValinnainenRooli(aineyhdistelmarooli) //

  def toArvosana(suoritus: Suoritus with Identified[UUID]):Arvosana = {
    Arvosana(suoritus.id, arvio, aine.aine: String,
      Some(aine.lisatiedot),
      isValinnainen: Boolean,
      Some(myonnetty),
      YoTutkinto.YTL,
      Koe.lahdeArvot(koetunnus, aineyhdistelmarooli, aineyhdistelmarooliLegacy, suoritus.henkiloOid))
  }
}

object YoSuoritusUpdateActor {
  case object Update

  def props(yoSuoritus: VirallinenSuoritus,
            personOidsWithAliases: PersonOidsWithAliases,
            suoritusRekisteri: ActorRef): Props = Props(new YoSuoritusUpdateActor(yoSuoritus: VirallinenSuoritus,
                                                                                  personOidsWithAliases: PersonOidsWithAliases,
                                                                                  suoritusRekisteri: ActorRef))
}

class YoSuoritusUpdateActor(yoSuoritus: VirallinenSuoritus,
                            personOidsWithAliases: PersonOidsWithAliases,
                            suoritusRekisteri: ActorRef) extends Actor with ActorLogging {
  import YoSuoritusUpdateActor._

  var asker: ActorRef = null

  private def ennenVuotta1990Valmistuneet(s: Seq[_]) = s.map {
    case v: VirallinenSuoritus with Identified[_] if v.id.isInstanceOf[UUID] =>
      v.asInstanceOf[VirallinenSuoritus with Identified[UUID]]
  }.filter(s => s.valmistuminen.isBefore(new LocalDate(1990, 1, 1)) && s.tila == "VALMIS" && s.vahvistettu)

  override def receive: Actor.Receive = {
    case Update =>
      asker = sender()
      implicit val ec: ExecutionContext = context.dispatcher
      val suoritusQuery = SuoritusQuery(henkilo = Some(yoSuoritus.henkilo), komo = Some(Oids.yotutkintoKomoOid))
      val queryWithPersonAliases = SuoritusQueryWithPersonAliases(suoritusQuery, personOidsWithAliases)
      suoritusRekisteri ! queryWithPersonAliases

    case s: Seq[_] =>
      if (s.isEmpty) {
        suoritusRekisteri ! UpsertResource[UUID,Suoritus](yoSuoritus, personOidsWithAliases)
      } else {
        val suoritukset = ennenVuotta1990Valmistuneet(s)
        if (suoritukset.nonEmpty) {
          asker ! suoritukset.head
          context.stop(self)
        } else {
          suoritusRekisteri ! UpsertResource[UUID,Suoritus](yoSuoritus, personOidsWithAliases)
        }
      }
    case v: VirallinenSuoritus with Identified[_] if v.id.isInstanceOf[UUID] =>
      asker ! v.asInstanceOf[VirallinenSuoritus with Identified[UUID]]
      context.stop(self)

    case Status.Failure(e) =>
      asker ! akka.actor.Status.Failure(e)
      context.stop(self)

    case unknown =>
      val errorMessage = s"Got unknown message '$unknown'"
      log.error(errorMessage)
      asker ! new IllegalArgumentException(errorMessage)
  }
}

object ArvosanaUpdateActor {
  case class Update(suoritus: Suoritus with Identified[UUID])

  def props(kokeet: Seq[Koe],
            arvosanaRekisteri: ActorRef,
            timeout: FiniteDuration)(implicit ec: ExecutionContext): Props = Props(new ArvosanaUpdateActor(
    kokeet: Seq[Koe],
    arvosanaRekisteri: ActorRef,
    timeout: FiniteDuration))
}

class ArvosanaUpdateActor(var kokeet: Seq[Koe],
                          arvosanaRekisteri: ActorRef,
                          timeoutForArvosanaOperations: FiniteDuration)(implicit ec: ExecutionContext) extends Actor with ActorLogging {
  import ArvosanaUpdateActor._

  implicit val timeoutForAskPattern = Timeout(timeoutForArvosanaOperations)

  var asker: ActorRef = null

  var suoritus: Suoritus with Identified[UUID] = null

  def isKorvaava(old: Arvosana) = (uusi: Arvosana) =>
    uusi.aine == old.aine && uusi.myonnetty == old.myonnetty && uusi.lisatieto == old.lisatieto &&
      (uusi.valinnainen == old.valinnainen || (uusi.valinnainen != old.valinnainen && uusi.lahdeArvot != old.lahdeArvot))

  override def receive: Actor.Receive = {
    case Update(s: Suoritus with Identified[UUID]) =>
      asker = sender()
      suoritus = s
      arvosanaRekisteri ! ArvosanaQuery(suoritus.id)

    case s: Seq[_] =>
      val uudet = kokeet.map(_.toArvosana(suoritus))
      val arvosanaFutures =
        s.map {
          case (as: Arvosana with Identified[_]) if as.id.isInstanceOf[UUID] =>
            val a = as.asInstanceOf[Arvosana with Identified[UUID]]
            val korvaava = uudet.find(isKorvaava(a))
            if (korvaava.isDefined) korvaava.get.identify(a.id)
            else a
        } map (arvosanaRekisteri ? _)
      val allArvosanaFuture = Future.sequence(arvosanaFutures)

      val uudetFutures = uudet.filterNot((uusi) => s.exists { case old: Arvosana => isKorvaava(old)(uusi) }) map (arvosanaRekisteri ? _)
      val allUudetFuture = Future.sequence(uudetFutures)

      log.debug("ArvosanaUpdateActor person={} suoritus={} arv={} uudet={}", suoritus.henkiloOid, suoritus.id, arvosanaFutures.size, uudetFutures.size)

      val allArvosanaAndUudetFuture = Future.sequence(Seq(allArvosanaFuture, allUudetFuture))
      allArvosanaAndUudetFuture onComplete {
        case scala.util.Success(a) =>
          log.debug("Updated arvosana person={} {}", suoritus.henkiloOid, if (a!=null) a else "null")
          val returnValue: Unit = ()
          asker ! returnValue
        case scala.util.Failure(t) =>
          log.error("Failed to update arvosana person={} suoritus={} ({})", suoritus.henkiloOid, suoritus.id, t)
          asker ! akka.actor.Status.Failure(t)
      }

    case unknown =>
      val errorMessage = s"Got unknown message '$unknown'"
      log.error(errorMessage)
      asker ! akka.actor.Status.Failure(new IllegalArgumentException(errorMessage))
  }
}

case class HakuException(message: String, haku: String, cause: Throwable) extends Exception(message, cause)
