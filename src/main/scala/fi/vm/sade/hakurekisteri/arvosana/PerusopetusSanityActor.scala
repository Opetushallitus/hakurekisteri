package fi.vm.sade.hakurekisteri.arvosana

import akka.actor.{ActorRef, Cancellable, Actor}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusQuery, Suoritus}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository.{InMemJournal, JournaledRepository, Journal}
import scala.concurrent.{Future, ExecutionContext}
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, Kausi}
import akka.pattern._
import java.util.UUID
import akka.event.Logging

import dispatch._
import scala.Some
import fi.vm.sade.hakurekisteri.suoritus.VirallinenSuoritus
import scala.util.Try

class PerusopetusSanityActor(val serviceUrl: String = "https://itest-virkailija.oph.ware.fi/koodisto-service", val suoritusRekisteri: ActorRef, val journal:Journal[Arvosana, UUID] = new InMemJournal[Arvosana, UUID]) extends Actor with ArvosanaService with JournaledRepository[Arvosana, UUID] with HakurekisteriJsonSupport {

  override val deduplicate = false

  override val logger = Logging(context.system, this)


  implicit val executionContext: ExecutionContext = context.dispatcher
  val perusopetus = "1.2.246.562.13.62959769647"
  val perusopetuksenlisa = "1.2.246.562.5.2013112814572435044876"

  var problems: Seq[Problem]  = Seq()

  import scala.concurrent.duration._

  var pakolliset:Set[String] = Set()

  var suoritusRequests:Cancellable = new Cancellable {
    override def cancel(): Boolean = true

    override def isCancelled: Boolean = true
  }

  override def postStop(): Unit = {
    suoritusRequests.cancel()
  }

  override def preStart(): Unit = {


    findPakolliset.onSuccess{
      case aineet:Set[String] => pakolliset = aineet
      suoritusRequests = scheduleSuoritusRequest(10.seconds)
      logger.info(s"perusopetus sanity actor started with mandatory subjects $pakolliset")
    }


  }


  def scheduleSuoritusRequest(seconds: FiniteDuration = 1.hour): Cancellable = {
    logger.info(s"scheduling suoritus fetch in $seconds")
    context.system.scheduler.scheduleOnce(seconds, self, ReloadAndCheck)
  }

  def findPakolliset: Future[Set[String]] = {
    for (muut <- getAineetWith(findPerusasteenAineet, "oppiaineenkielisyys_0")) yield Set("A1") ++ muut.toSet
  }

  def getAineetWith(perusaste: Future[Seq[(Koodi, Set[String])]], elem: String): Future[Seq[String]] = {
    for (aineet <- perusaste) yield
      for (aine <- aineet;
           if aine._2.contains(elem)) yield aine._1("koodiArvo").toString
  }

  def findPerusasteenAineet =
    for (koodisto <- resolveKoodisto) yield
      for (koodi <- koodisto;
         if koodi._2.contains("onperusasteenoppiaine_1")) yield koodi

  def resolveKoodisto = {

    def withSisaltyy(k:Option[Seq[Koodi]]): Future[Seq[(Koodi, Set[String])]] = Future.sequence(
      for (koodi: Koodi <- k.getOrElse(Seq())) yield
        for (s <- sisaltyy(koodi)) yield (koodi, s.getOrElse(Seq()).map(_("koodiUri").toString).toSet))

    def yl: Future[Option[Seq[Map[String, Any]]]] = {
      val req = url(s"$serviceUrl/rest/json/oppiaineetyleissivistava/koodi/")
      val res: Future[String] = Http(req OK as.String)
      parseBody(res)
    }





    for (k: Option[Seq[Map[String, Any]]] <- yl;
         s <- withSisaltyy(k)) yield s
  }


  def parseBody(res: Future[String]): Future[Option[Seq[Map[String, Any]]]] = {
    import org.json4s.jackson.JsonMethods.parse
    for (result <- res) yield
      for (json <- Try(parse(result)).toOption) yield json.values.asInstanceOf[Seq[Map[String, Any]]]
  }

  type Koodi = Map[String, Any]
  def sisaltyy(koodi: Koodi): Future[Option[Seq[Map[String, Any]]]] = {
    val req = url(s"$serviceUrl/rest/json/relaatio/sisaltyy-alakoodit/${koodi("koodiUri")}")
    val res: Future[String] = Http(req OK as.String)
    parseBody(res)
  }


  override def receive: Actor.Receive = {
    case ReloadAndCheck =>
      suoritusRekisteri ! SuoritusQuery(None, Some(Kausi.Kevät), Some("2014"), None)
      suoritusIndex = Map()
      loadJournal()
    case Problems => logger.info(s"$sender requested problem list returning ${problems.size} problems")
                     sender ! problems
    case s:Stream[_] => for (first <- s.headOption) goThrough(first, s.tail)
    case s::rest  => goThrough(s, rest)
    case su: Suoritus with Identified[_] if su.id.isInstanceOf[UUID] =>
      val s = su.asInstanceOf[Suoritus with Identified[UUID]]
      findBy(ArvosanaQuery(Some(s.id))).map(Todistus(s, _)) pipeTo self
    case Todistus(suoritus, arvosanas) =>
      (suoritus.id, suoritus.asInstanceOf[Suoritus]) match {
        case (id, VirallinenSuoritus(`perusopetus`, oppilaitos, _, _ ,oppilas ,_,_,_, _, _)) =>

          checkTodistus(arvosanas, oppilas, id, oppilaitos, "perusopetus")
        case (id, VirallinenSuoritus(`perusopetuksenlisa`, oppilaitos, _, _ ,oppilas ,_,_, _, _, _)) =>
          checkTodistus(arvosanas, oppilas, id, oppilaitos, "perusopetuksen lisäopetus")



        case _ =>
      }

  }


  def checkTodistus(arvosanas: Seq[Arvosana], oppilas: String, id: UUID, oppilaitos: String, opetusTyyppi:String) {
    val missingMandatory = missing(arvosanas)
    val validation = missingMandatory.map(MissingArvosana(oppilas, id, _))
    problems = problems.filterNot(_ match {
      case MissingArvosana(_, `id`, _) => true
      case _ => false
    }) ++ validation

    if (!missingMandatory.isEmpty) logger.warning(s"problems with suoritus $id ($opetusTyyppi) for oppilas $oppilas from $oppilaitos missing mandatory subjects (${missingMandatory.mkString(",")})")

    val extraMan = extraMandatory(arvosanas)
    val probs = extraMan.map(ExtraGeneral(oppilas, id, _))
    problems = problems.filterNot(_ match {
      case ExtraGeneral(_, `id`, _) => true
      case _ => false
    }) ++ probs

    if (!extraMan.isEmpty) logger.warning(s"problems with suoritus $id ($opetusTyyppi) for oppilas $oppilas from $oppilaitos more than one general course for subjects (${extraMan.mkString(",")})")

    val extraVol = extraVoluntary(arvosanas)
    val volProbs = extraVol.map(ExtraVoluntary(oppilas, id, _))
    problems = problems.filterNot(_ match {
      case ExtraVoluntary(_, `id`, _) => true
      case _ => false
    }) ++ volProbs

    if (!extraVol.isEmpty) logger.warning(s"problems with suoritus $id ($opetusTyyppi) for oppilas $oppilas from $oppilaitos more than two optional courses for subjects (${extraVol.mkString(",")})")


    val orphanVoluntary = voluntaryWithoutMandatory(arvosanas)
    val orphans = orphanVoluntary.map(VoluntaryWithoutGeneral(oppilas, id, _))
    problems = problems.filterNot(_ match {
      case VoluntaryWithoutGeneral(_, `id`, _) => true
      case _ => false
    }) ++ orphans

    if (!orphanVoluntary.isEmpty) logger.warning(s"problems with suoritus $id ($opetusTyyppi) for oppilas $oppilas from $oppilaitos optional courses without general for subjects (${orphanVoluntary.mkString(",")})")
  }

  def goThrough(s: Any, rest: Seq[Any]) {
    self ! s
    if (rest != Nil) self ! rest
    else suoritusRequests = scheduleSuoritusRequest()
  }

  def missing(arvosanas: Seq[Arvosana]): Set[String] = {
    val skaala = (4 to 10).map(_.toString).toSet
    val set = arvosanas.withFilter {
      case Arvosana(_, Arvio410(arvosana), _, _, _, _, _) => skaala.contains(arvosana)
    }.map(_.aine).toSet
    pakolliset.filterNot(set.contains(_))
  }

  def extraMandatory(arvosanas: Seq[Arvosana]): Set[String] = {
    (for (aine <- arvosanas.groupBy(_.aine).values
         if pakolliset(aine).length > 1) yield aine.head.aine).toSet
  }

  def extraVoluntary(arvosanas: Seq[Arvosana]): Set[String] = {
    (for (aine <- arvosanas.groupBy(_.aine).values
          if valinnaiset(aine).length > 2) yield aine.head.aine).toSet
  }

  def voluntaryWithoutMandatory(arvosanas: Seq[Arvosana]): Set[String] = {
    (for (aine <- arvosanas.groupBy(_.aine).values
          if valinnaiset(aine).length > 0 && pakolliset(aine).length == 0) yield aine.head.aine).toSet
  }

  def valinnaiset(aine: Seq[Arvosana]): Seq[Arvosana] = {
    valinnaisuus(aine).get(true).getOrElse(Seq())
  }


  def pakolliset(aine: Seq[Arvosana]): Seq[Arvosana] = {
    valinnaisuus(aine).get(false).getOrElse(Seq())
  }

  def valinnaisuus(aine: Seq[Arvosana]): Map[Boolean, Seq[Arvosana]] = {
    aine.groupBy(_.valinnainen)
  }


}

sealed trait Problem

case class MissingArvosana(henkilo: String, suoritus: UUID, aine:String) extends Problem
case class ExtraGeneral(henkilo: String, suoritus: UUID, aine:String) extends Problem
case class ExtraVoluntary(henkilo: String, suoritus: UUID, aine:String) extends Problem
case class VoluntaryWithoutGeneral(henkilo: String, suoritus: UUID, aine:String) extends Problem





case class Todistus(suoritus:Suoritus with Identified[UUID], arvosanas: Seq[Arvosana])

object Problems

object ReloadAndCheck
