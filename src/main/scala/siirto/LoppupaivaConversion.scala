package siirto

import java.util.UUID

import akka.actor.Status.Failure
import akka.actor.{Props, ActorLogging, Actor, ActorSystem}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.{ServiceConfig, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus}
import org.joda.time.DateTime

import scala.concurrent.ExecutionContext

object LoppupaivaConversion {
  def run(virkailijaUrl: String = "https://testi.virkailija.opintopolku.fi", user: String, password: String) = {
    implicit val system = ActorSystem("conversion")
    implicit val ec: ExecutionContext = system.dispatcher

    val sureClient = new VirkailijaRestClient(ServiceConfig(
      casUrl = Some(s"$virkailijaUrl/cas"),
      serviceUrl = s"$virkailijaUrl/suoritusrekisteri",
      user = Some(user),
      password = Some(password)
    ))

    system.actorOf(Props(new ConversionActor(sureClient)))
  }
}

object FetchSuoritukset
case class OpiskelijaWithId(id: UUID,
                            oppilaitosOid: String,
                            luokkataso: String,
                            luokka: String,
                            henkiloOid: String,
                            alkuPaiva: DateTime,
                            loppuPaiva: Option[DateTime] = None,
                            source: String)
case class Suoritukset(suoritukset: Seq[Suoritus])
case class Opiskelijat(opiskelijat: Seq[OpiskelijaWithId])
case class SavedOpiskelija(opiskelija: OpiskelijaWithId)
import akka.pattern.pipe
import scala.concurrent.duration._

class ConversionActor(sureClient: VirkailijaRestClient) extends Actor with ActorLogging {
  implicit val ec: ExecutionContext = context.system.dispatcher
  var suoritukset: Seq[Suoritus] = Seq()
  var opiskelijat: Seq[OpiskelijaWithId] = Seq()
  var saved: Seq[OpiskelijaWithId] = Seq()
  var errors: Seq[Throwable] = Seq()

  private def filterSuoritus(o: OpiskelijaWithId)(s: Suoritus): Boolean = (o.luokkataso, s) match {
    case ("9", v: VirallinenSuoritus) if v.komo == Config.perusopetusKomoOid => true
    case ("10", v: VirallinenSuoritus) if v.komo == Config.lisaopetusKomoOid => true
    case ("A", v: VirallinenSuoritus) if v.komo == Config.ammattistarttiKomoOid => true
    case ("M", v: VirallinenSuoritus) if v.komo == Config.ammatilliseenvalmistavaKomoOid => true
    case ("V", v: VirallinenSuoritus) if v.komo == Config.valmentavaKomoOid => true
    case _ => false
  }

  var updating: Boolean = false

  override def preStart(): Unit = {
    self ! FetchSuoritukset
    super.preStart()
  }

  def stop() = {
    context.stop(self)
    context.system.shutdown()
    context.system.awaitTermination(5.seconds)
  }

  case class SaveError(t: Throwable) extends Exception(t)

  override def receive: Receive = {
    case FetchSuoritukset => sureClient.readObject[Seq[Suoritus]]("/rest/v1/suoritukset", 200).map(Suoritukset) pipeTo self

    case Suoritukset(s) =>
      suoritukset = s
      sureClient.readObject[Seq[OpiskelijaWithId]]("/rest/v1/opiskelijat", 200).map(Opiskelijat) pipeTo self

    case Opiskelijat(os) =>
      val opiskelijatWithoutLoppupaiva = os.filter(_.loppuPaiva == None)
      val groupedSuoritukset: Map[String, Seq[Suoritus]] = suoritukset.groupBy(_.henkiloOid)
      log.info(s"loppupäivättömiä opiskelijoita: ${opiskelijatWithoutLoppupaiva.size}")
      val opiskelijatWithLoppupaiva = opiskelijatWithoutLoppupaiva.map((o: OpiskelijaWithId) => {
        groupedSuoritukset.get(o.henkiloOid) match {
          case Some(suor) =>
            suor.find(filterSuoritus(o)) match {
              case Some(suoritus: VirallinenSuoritus) =>
                Some(o.copy(loppuPaiva = Some(suoritus.valmistuminen.toDateTimeAtStartOfDay)))
              case _ => None
            }
          case _ => None
        }
      }).flatten
      opiskelijat = opiskelijatWithLoppupaiva
      if (opiskelijatWithLoppupaiva.isEmpty) {
        log.info("no rows to save, stopping.")
        stop()
      } else
        opiskelijatWithLoppupaiva.foreach(o => self ! o)

    case t: OpiskelijaWithId if !updating =>
      updating = true
      val id = t.id.toString
      log.debug(s"sending POST request to ${sureClient.serviceUrl}/rest/v1/opiskelijat/$id")
      sureClient.postObject[OpiskelijaWithId, OpiskelijaWithId](s"/rest/v1/opiskelijat/$id", 200, t).map(SavedOpiskelija).recover {
        case t: Throwable => SaveError(t)
      } pipeTo self

    case t: OpiskelijaWithId if updating => context.system.scheduler.scheduleOnce(100.milliseconds, self, t)

    case SavedOpiskelija(o) =>
      log.debug(s"got opiskelija $o")
      saved = saved :+ o
      updating = false
      if (saved.length + errors.length == opiskelijat.length) {
        log.info("all saved.")
        stop()
      }

    case Failure(t: SaveError) =>
      log.error(t, "got save error")
      errors = errors :+ t
      if (saved.length + errors.length == opiskelijat.length) {
        log.info("all done.")
        stop()
      }

    case Failure(t: Throwable) =>
      log.error(t, "got serious error")
      stop()

  }
}