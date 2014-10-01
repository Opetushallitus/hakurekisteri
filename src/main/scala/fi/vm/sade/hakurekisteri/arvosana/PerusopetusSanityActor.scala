package fi.vm.sade.hakurekisteri.arvosana

import akka.actor.{ActorRef, Cancellable, Actor}
import fi.vm.sade.hakurekisteri.suoritus.{VirallinenSuoritus, SuoritusQuery, Suoritus}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository.{InMemJournal, JournaledRepository, Journal}
import scala.concurrent.{Future, ExecutionContext}
import fi.vm.sade.hakurekisteri.rest.support.Kausi
import akka.pattern._
import java.util.UUID
import com.stackmob.newman.dsl._
import com.stackmob.newman.ApacheHttpClient
import java.net.URL
import net.liftweb.json.JsonAST.JValue
import akka.event.Logging

class PerusopetusSanityActor(val serviceUrl: String = "https://itest-virkailija.oph.ware.fi/koodisto-service", val suoritusRekisteri: ActorRef, val journal:Journal[Arvosana, UUID] = new InMemJournal[Arvosana, UUID]) extends Actor with ArvosanaService with JournaledRepository[Arvosana, UUID] {

  override val deduplicate = false

  val log = Logging(context.system, this)


  implicit val executionContext: ExecutionContext = context.dispatcher
  val perusopetus = "1.2.246.562.13.62959769647"
  val perusopetuksenlisa = "1.2.246.562.5.2013112814572435044876"

  var problems: Seq[Problem]  = Seq()

  import scala.concurrent.duration._

  implicit val httpClient = new ApacheHttpClient(socketTimeout = 60.seconds.toMillis.toInt)()


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
      log.info(s"perusopetus sanity actor started with mandatory subjects $pakolliset")
    }


  }


  def scheduleSuoritusRequest(seconds: FiniteDuration = 1.hour): Cancellable = {
    log.info(s"scheduling suoritus fetch in $seconds")
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

    val req = GET(new URL(s"$serviceUrl/rest/json/oppiaineetyleissivistava/koodi/")).apply
    val yl =
      for (result <- req) yield
        for (json <- result.bodyAs[JValue].toOption) yield json.values.asInstanceOf[Seq[Map[String, Any]]]

    for (k: Option[Seq[Map[String, Any]]] <- yl;
         s <- withSisaltyy(k)) yield s
  }


  type Koodi = Map[String, Any]
  def sisaltyy(koodi: Koodi): Future[Option[Seq[Map[String, Any]]]] = {
    for (resp <- GET(new URL(s"$serviceUrl/rest/json/relaatio/sisaltyy-alakoodit/${koodi("koodiUri")}")).apply) yield
      for (json <- resp.bodyAs[JValue].toOption) yield json.values.asInstanceOf[Seq[Map[String, Any]]]
  }

  override def receive: Actor.Receive = {
    case ReloadAndCheck =>
      suoritusRekisteri ! SuoritusQuery(None, Some(Kausi.Kevät), Some("2014"), None)
      suoritusIndex = Map()
      loadJournal()
    case Problems => log.info(s"$sender requested problem list returning ${problems.size} problems")
                     sender ! problems
    case s:Stream[_] => for (first <- s.headOption) goThrough(first, s.tail)
    case s::rest  => goThrough(s, rest)
    case s: Suoritus with Identified[UUID] =>
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

    if (!missingMandatory.isEmpty) log.warning(s"problems with suoritus $id ($opetusTyyppi) for oppilas $oppilas from $oppilaitos missing mandatory subjects (${missingMandatory.mkString(",")})")

    val extraMan = extraMandatory(arvosanas)
    val probs = extraMan.map(ExtraGeneral(oppilas, id, _))
    problems = problems.filterNot(_ match {
      case ExtraGeneral(_, `id`, _) => true
      case _ => false
    }) ++ probs

    if (!extraMan.isEmpty) log.warning(s"problems with suoritus $id ($opetusTyyppi) for oppilas $oppilas from $oppilaitos more than one general course for subjects (${extraMan.mkString(",")})")

    val extraVol = extraVoluntary(arvosanas)
    val volProbs = extraVol.map(ExtraVoluntary(oppilas, id, _))
    problems = problems.filterNot(_ match {
      case ExtraVoluntary(_, `id`, _) => true
      case _ => false
    }) ++ volProbs

    if (!extraVol.isEmpty) log.warning(s"problems with suoritus $id ($opetusTyyppi) for oppilas $oppilas from $oppilaitos more than two optional courses for subjects (${extraVol.mkString(",")})")


    val orphanVoluntary = voluntaryWithoutMandatory(arvosanas)
    val orphans = orphanVoluntary.map(VoluntaryWithoutGeneral(oppilas, id, _))
    problems = problems.filterNot(_ match {
      case VoluntaryWithoutGeneral(_, `id`, _) => true
      case _ => false
    }) ++ orphans

    if (!orphanVoluntary.isEmpty) log.warning(s"problems with suoritus $id ($opetusTyyppi) for oppilas $oppilas from $oppilaitos optional courses without general for subjects (${orphanVoluntary.mkString(",")})")
  }

  def goThrough(s: Any, rest: Seq[Any]) {
    self ! s
    if (rest != Nil) self ! rest
    else suoritusRequests = scheduleSuoritusRequest()
  }

  def missing(arvosanas: Seq[Arvosana]): Set[String] = {
    val skaala = (4 to 10).map(_.toString).toSet
    pakolliset.filterNot(arvosanas.withFilter((a) => skaala.contains(a.arvio.arvosana)).map(_.aine).toSet.contains(_))
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

  override def identify(o: Arvosana): Arvosana with Identified[UUID] = ???

}

sealed trait Problem

case class MissingArvosana(henkilo: String, suoritus: UUID, aine:String) extends Problem
case class ExtraGeneral(henkilo: String, suoritus: UUID, aine:String) extends Problem
case class ExtraVoluntary(henkilo: String, suoritus: UUID, aine:String) extends Problem
case class VoluntaryWithoutGeneral(henkilo: String, suoritus: UUID, aine:String) extends Problem





case class Todistus(suoritus:Suoritus with Identified[UUID], arvosanas: Seq[Arvosana])

object Problems

object ReloadAndCheck
