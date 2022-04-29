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
import fi.vm.sade.hakurekisteri.suoritus.{
  Suoritus,
  SuoritusQuery,
  VirallinenSuoritus,
  yksilollistaminen,
  _
}
import org.joda.time._
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait KokelasPersister {
  def persistSingle(kokelas: KokelasWithPersonAliases)(implicit ec: ExecutionContext): Future[Unit]
}

class YtlKokelasPersister(
  system: ActorSystem,
  suoritusRekisteri: ActorRef,
  arvosanaRekisteri: ActorRef,
  hakemusService: IHakemusService,
  timeout: Timeout,
  howManyTries: Int
) extends KokelasPersister {
  private val logger = LoggerFactory.getLogger(getClass)
  implicit val materializer: ActorMaterializer = ActorMaterializer()(context = system)

  override def persistSingle(
    k: KokelasWithPersonAliases
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val kokelas = k.kokelas
    logger.debug(
      s"sending ytl data for kokelas ${kokelas.oid} yo=${kokelas.yo} timeout=${timeout.duration}"
    )
    val yoSuoritusUpdateActor: ActorRef = system.actorOf(
      YoSuoritusUpdateActor.props(kokelas.yo, k.personOidsWithAliases, suoritusRekisteri)
    )
    val arvosanaUpdateActor: ActorRef = system.actorOf(
      ArvosanaUpdateActor.props(
        kokelas.yoTodistus,
        arvosanaRekisteri,
        timeout.duration
      )
    )

    def arvosanaUpdateWithRetries(suoritus: Suoritus with Identified[UUID]): Future[Unit] = {
      def arvosanaUpdateRetry(remainingRetries: Int): Future[Unit] = {
        val suoritusTxt = s"henkilö=${suoritus.henkiloOid} suoritus=${suoritus.id}"
        logger.debug(s"ArvosanaUpdate send suoritus to Actor for $suoritusTxt")
        (arvosanaUpdateActor ? ArvosanaUpdateActor.Update(suoritus))(timeout)
          .mapTo[Unit]
          .recoverWith { case t: Throwable =>
            logger.error(
              s"ArvosanaUpdate Got exception when updating $suoritusTxt, retries left=${remainingRetries - 1}",
              t
            )
            if (remainingRetries <= 1) {
              Future.failed(
                new RuntimeException(s"ArvosanaUpdate: Run out of retries $suoritusTxt", t)
              )
            } else {
              arvosanaUpdateRetry(remainingRetries - 1)
            }
          }
      }
      arvosanaUpdateRetry(howManyTries)
    }

    val allOperations = for {
      virallinenSuoritus <- akka.pattern
        .ask(yoSuoritusUpdateActor, YoSuoritusUpdateActor.Update)(timeout)
        .mapTo[VirallinenSuoritus with Identified[UUID]]
      if (virallinenSuoritus.id
        .isInstanceOf[UUID] && virallinenSuoritus.komo == YoTutkinto.yotutkinto)
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
}

case class KokelasWithPersonAliases(kokelas: Kokelas, personOidsWithAliases: PersonOidsWithAliases)

case class Kokelas(oid: String, yo: VirallinenSuoritus, yoTodistus: Seq[Koe])

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

  def apply(koetunnus: String): Aine =
    aineet(koetunnus)
}
object YoTutkinto {
  val YTL: String = Oids.ytlOrganisaatioOid
  val yotutkinto = Oids.yotutkintoKomoOid

  def apply(
    suorittaja: String,
    valmistuminen: LocalDate,
    kieli: String,
    valmis: Boolean = true,
    lahdeArvot: Map[String, String],
    vahvistettu: Boolean = true
  ) = {
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
      lahdeArvot = lahdeArvot
    )
  }
}

case class YoKoe(
  arvio: ArvioYo,
  koetunnus: String,
  myonnetty: LocalDate,
  personOid: String
) extends Koe {
  val aine = Aine(koetunnus)

  def toArvosana(suoritus: Suoritus with Identified[UUID]): Arvosana = {
    Arvosana(
      suoritus.id,
      arvio,
      aine.aine: String,
      Some(aine.lisatiedot),
      true,
      Some(myonnetty),
      YoTutkinto.YTL,
      Map.empty
    )
  }
}

object YoSuoritusUpdateActor {
  case object Update

  def props(
    yoSuoritus: VirallinenSuoritus,
    personOidsWithAliases: PersonOidsWithAliases,
    suoritusRekisteri: ActorRef
  ): Props = Props(
    new YoSuoritusUpdateActor(
      yoSuoritus: VirallinenSuoritus,
      personOidsWithAliases: PersonOidsWithAliases,
      suoritusRekisteri: ActorRef
    )
  )
}

class YoSuoritusUpdateActor(
  yoSuoritus: VirallinenSuoritus,
  personOidsWithAliases: PersonOidsWithAliases,
  suoritusRekisteri: ActorRef
) extends Actor
    with ActorLogging {
  import YoSuoritusUpdateActor._

  var asker: ActorRef = null

  private def ennenVuotta1990Valmistuneet(s: Seq[_]) = s
    .map {
      case v: VirallinenSuoritus with Identified[_] if v.id.isInstanceOf[UUID] =>
        v.asInstanceOf[VirallinenSuoritus with Identified[UUID]]
    }
    .filter(s =>
      s.valmistuminen.isBefore(new LocalDate(1990, 1, 1)) && s.tila == "VALMIS" && s.vahvistettu
    )

  override def receive: Actor.Receive = initialHandler

  def initialHandler: Receive = { case Update =>
    asker = sender()
    context.become(handleResponseFromSuoritusRekisteri)
    implicit val ec: ExecutionContext = context.dispatcher
    val suoritusQuery =
      SuoritusQuery(henkilo = Some(yoSuoritus.henkilo), komo = Some(Oids.yotutkintoKomoOid))
    val queryWithPersonAliases =
      SuoritusQueryWithPersonAliases(suoritusQuery, personOidsWithAliases)
    suoritusRekisteri ! queryWithPersonAliases
  }

  def handleResponseFromSuoritusRekisteri: Receive = {
    case s: Seq[_] =>
      if (s.isEmpty) {
        suoritusRekisteri ! UpsertResource[UUID, Suoritus](yoSuoritus, personOidsWithAliases)
      } else {
        val suoritukset = ennenVuotta1990Valmistuneet(s)
        if (suoritukset.nonEmpty) {
          asker ! suoritukset.head
          context.stop(self)
        } else {
          suoritusRekisteri ! UpsertResource[UUID, Suoritus](yoSuoritus, personOidsWithAliases)
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
      asker ! akka.actor.Status.Failure(new IllegalArgumentException(errorMessage))
      context.become(initialHandler)
  }
}

object ArvosanaUpdateActor {
  case class Update(suoritus: Suoritus with Identified[UUID])

  def props(kokeet: Seq[Koe], arvosanaRekisteri: ActorRef, timeout: FiniteDuration)(implicit
    ec: ExecutionContext
  ): Props = Props(
    new ArvosanaUpdateActor(kokeet: Seq[Koe], arvosanaRekisteri: ActorRef, timeout: FiniteDuration)
  )
}

class ArvosanaUpdateActor(
  var kokeet: Seq[Koe],
  arvosanaRekisteri: ActorRef,
  timeoutForArvosanaOperations: FiniteDuration
)(implicit ec: ExecutionContext)
    extends Actor
    with ActorLogging {
  import ArvosanaUpdateActor._

  implicit val timeoutForAskPattern = Timeout(timeoutForArvosanaOperations)

  var asker: ActorRef = null

  var suoritus: Suoritus with Identified[UUID] = null

  def isKorvaava(old: Arvosana) = (uusi: Arvosana) =>
    uusi.aine == old.aine && uusi.myonnetty == old.myonnetty && uusi.lisatieto == old.lisatieto &&
      (uusi.valinnainen == old.valinnainen || (uusi.valinnainen != old.valinnainen && uusi.lahdeArvot != old.lahdeArvot))

  override def receive: Actor.Receive = initialHandler

  def initialHandler: Receive = { case Update(s: Suoritus with Identified[UUID]) =>
    asker = sender()
    context.become(handleResponsesFromArvosanaRekisteri)
    suoritus = s
    arvosanaRekisteri ! ArvosanaQuery(suoritus.id)
  }

  def handleResponsesFromArvosanaRekisteri: Receive = {
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

      val uudetFutures = uudet.filterNot((uusi) =>
        s.exists { case old: Arvosana => isKorvaava(old)(uusi) }
      ) map (arvosanaRekisteri ? _)
      val allUudetFuture = Future.sequence(uudetFutures)

      log.debug(
        "ArvosanaUpdateActor person={} suoritus={} arv={} uudet={}",
        suoritus.henkiloOid,
        suoritus.id,
        arvosanaFutures.size,
        uudetFutures.size
      )

      val allArvosanaAndUudetFuture = Future.sequence(Seq(allArvosanaFuture, allUudetFuture))
      allArvosanaAndUudetFuture onComplete {
        case scala.util.Success(a) =>
          log.debug(
            "Updated arvosana person={} {}",
            suoritus.henkiloOid,
            if (a != null) a else "null"
          )
          val returnValue: Unit = ()
          asker ! returnValue
        case scala.util.Failure(t) =>
          log.error(
            "Failed to update arvosana person={} suoritus={} ({})",
            suoritus.henkiloOid,
            suoritus.id,
            t
          )
          asker ! akka.actor.Status.Failure(t)
      }

    case unknown =>
      val errorMessage = s"Got unknown message '$unknown'"
      log.error(errorMessage)
      asker ! akka.actor.Status.Failure(new IllegalArgumentException(errorMessage))
      context.become(initialHandler)
  }
}

case class HakuException(message: String, haku: String, cause: Throwable)
    extends Exception(message, cause)
