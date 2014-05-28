package fi.vm.sade.hakurekisteri.arvosana

import akka.actor.{ActorRef, Cancellable, Actor}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusQuery, Suoritus}
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

class PerusopetusSanityActor(val suoritusRekisteri: ActorRef, val journal:Journal[Arvosana] = new InMemJournal[Arvosana]) extends Actor with ArvosanaService with JournaledRepository[Arvosana] {

  val log = Logging(context.system, this)


  implicit val executionContext: ExecutionContext = context.dispatcher
  val perusopetus = "1.2.246.562.13.62959769647"

  var problems: Seq[(String, UUID, Problem)]  = Seq()

  import scala.concurrent.duration._

  implicit val httpClient = new ApacheHttpClient(socketTimeout = 60.seconds.toMillis.toInt)()

  val serviceUrl = "https://itest-virkailija.oph.ware.fi/koodisto-service"

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
    context.system.scheduler.scheduleOnce(seconds, suoritusRekisteri, SuoritusQuery(None, Some(Kausi.Kevät), Some("2014"), None))(executionContext, self)
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
    case Problems => sender ! problems
    case s:Stream[_] => for (first <- s.headOption) goThrough(first, s.tail)
    case s::rest  => goThrough(s, rest)
    case s: Suoritus with Identified =>
      log.debug(s"received suoritus for ${s.henkiloOid} creating todistus")
      findBy(ArvosanaQuery(Some(s.id))).map(Todistus(s, _)) pipeTo self
    case Todistus(suoritus, arvosanas) =>
      log.debug(s"received todistus for ${suoritus.henkiloOid} with ${arvosanas.size} arvosanas")
      (suoritus.id, suoritus.asInstanceOf[Suoritus]) match {
        case (id, Suoritus(`perusopetus`, _, _, _ ,oppilas ,_, _))  =>
          val validation = invalid(arvosanas)
          problems = problems.filter(_._2 != id) ++ validation.map((oppilas, id, _))
          if (!validation.isEmpty)log.warning(s"problems with suoritus $id for oppilas $oppilas ($validation)")
      }
    case unknown => log.debug(s"received ${unknown.getClass} unable to handle");

  }


  def goThrough(s: Any, rest: Seq[Any]) {
    self ! s
    if (rest != Nil) self ! rest
    else suoritusRequests = scheduleSuoritusRequest()
  }

  def invalid(arvosanas: Seq[Arvosana]): Seq[Problem] = {
    pakolliset.filterNot(arvosanas.map(_.aine).toSet.contains(_)).map(MissingArvosana).toList
  }

  override def identify(o: Arvosana): Arvosana with Identified = ???

}

sealed trait Problem

case class MissingArvosana(aine:String) extends Problem



case class Todistus(suoritus:Suoritus with Identified, arvosanas: Seq[Arvosana])

object Problems
