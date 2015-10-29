package fi.vm.sade.hakurekisteri.ensikertalainen


import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.tarjonta.{GetKomoQuery, KomoResponse}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.ValintarekisteriQuery
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery, VirallinenSuoritus}
import org.joda.time.{DateTimeZone, DateTime, LocalDate}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.implicitConversions
import scalaz.Scalaz._
import scalaz._
import scalaz.concurrent.Task
import scalaz.stream._

case class EnsikertalainenQuery(henkiloOid: String,
                                suoritukset: Option[Seq[Suoritus]] = None,
                                opiskeluoikeudet: Option[Seq[Opiskeluoikeus]] = None,
                                paivamaara: Option[DateTime] = None)

object QueryCount

case class QueriesRunning(count: Map[String, Int], timestamp: Long = Platform.currentTime)

sealed trait MenettamisenPeruste

case object KkVastaanotto extends MenettamisenPeruste

case object SuoritettuKkTutkinto extends MenettamisenPeruste

case object HakemuksellaIlmoitettuKkTutkinto extends MenettamisenPeruste

case class Ensikertalainen(ensikertalainen: Boolean, menettamisenPeruste: Option[MenettamisenPeruste])

case class HetuNotFoundException(message: String) extends Exception(message)

class EnsikertalainenActor(suoritusActor: ActorRef, valintarekisterActor: ActorRef, tarjontaActor: ActorRef, config: Config)(implicit val ec: ExecutionContext) extends Actor with ActorLogging {

  val syksy2014: DateTime = new DateTime(2014, 8, 1, 0, 0, 0, 0, DateTimeZone.forID("Europe/Helsinki"))
  val Oid = "(1\\.2\\.246\\.562\\.[0-9.]+)".r
  val KkKoulutusUri = "koulutus_[67][1-9][0-9]{4}".r

  implicit val defaultTimeout: Timeout = 2.minutes

  implicit def future2Task[A](future: Future[A]): Task[A] = Task.async[A] {
    register =>
      future.onComplete {
        case scala.util.Success(v) => register(v.right)
        case scala.util.Failure(ex) => register(ex.left)
      }
  }

  log.info(s"started ensikertalaisuus actor: $self")

  val queryEc = ec

  override def receive: Receive = {
    case q: EnsikertalainenQuery =>
      val promise = Promise[Ensikertalainen]()

      Future {

        val henkiloOid = Process(q.henkiloOid).toSource

        val henkilonSuoritukset =
          channel.lift[Task, String, Seq[Suoritus]]((henkiloOid: String) => q.suoritukset.map(Task.now).getOrElse((suoritusActor ? SuoritusQuery(henkilo = Some(henkiloOid))).mapTo[Seq[Suoritus]]))

        val isKkTutkinto =
          channel.lift[Task, VirallinenSuoritus, Boolean]((s: VirallinenSuoritus) => s.komo match {
            case KkKoulutusUri() => Task.now(true)
            case Oid(komo) =>
              (tarjontaActor ? GetKomoQuery(komo)).mapTo[KomoResponse].flatMap(_.komo match {
                case Some(k) => Future.successful(k.isKorkeakoulututkinto)
                case None => Future.failed(new Exception(s"komo $komo not found"))
              })
            case _ => Task.now(false)
          })

        val kkVastaanotto =
          channel.lift[Task, String, Option[DateTime]]((henkiloOid: String) => (valintarekisterActor ? ValintarekisteriQuery(henkiloOid, syksy2014)).mapTo[Option[DateTime]])

        val kkTutkinnot = henkiloOid through henkilonSuoritukset pipe process1.unchunk collect {
          case vs: VirallinenSuoritus => vs
        } observeThrough isKkTutkinto collect {
          case (VirallinenSuoritus(_, _, "VALMIS", valmistuminen, _, _, _, _, _, _), true) => valmistuminen.toDateTimeAtStartOfDay
        } minimumBy (_.getMillis) pipe process1.awaitOption

        val ensimmainenVastaanotto = henkiloOid through kkVastaanotto

        val query = kkTutkinnot.flatMap {
          case tutkintoDate@Some(_) => Process.emit((tutkintoDate, None)).toSource
          case tutkintoDate@None => ensimmainenVastaanotto.map(vastaanottoDate => (tutkintoDate, vastaanottoDate))
        }

        query.runLastOr(None -> None).
          map(ensikertalaisuusPaattely(q.paivamaara.getOrElse(new LocalDate().toDateTimeAtStartOfDay))).
          timed(1.minutes.toMillis).
          runAsync {
            case -\/(failure) => promise.tryFailure(failure)
            case \/-(ensikertalainen) => promise.trySuccess(ensikertalainen)
          }

      }(queryEc)

      promise.future pipeTo sender

    case QueryCount =>
      sender ! QueriesRunning(Map[String, Int]())
  }

  def ensikertalaisuusPaattely(leikkuripaiva: DateTime)(t: (Option[DateTime], Option[DateTime])) = t match {
    case (Some(tutkintopaiva), _) if tutkintopaiva.isBefore(leikkuripaiva) => Ensikertalainen(false, Some(SuoritettuKkTutkinto))
    case (_, Some(vastaanottopaiva)) if vastaanottopaiva.isBefore(leikkuripaiva) => Ensikertalainen(false, Some(KkVastaanotto))
    case default => Ensikertalainen(true, None)
  }


  class FetchResource[T, R](query: Query[T], wrapper: (Seq[T]) => R, receiver: ActorRef, resourceActor: ActorRef) extends Actor {
    override def preStart(): Unit = {
      resourceActor ! query
    }

    override def receive: Actor.Receive = {
      case s: Seq[T] =>
        receiver ! wrapper(s)
        context.stop(self)
    }
  }

}


