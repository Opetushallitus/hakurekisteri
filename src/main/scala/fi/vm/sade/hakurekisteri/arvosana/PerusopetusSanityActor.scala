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
  val perusopetus = ""

  var problems: Seq[(String, UUID)]  = Seq()

  import scala.concurrent.duration._

  implicit val httpClient = new ApacheHttpClient(socketTimeout = 60.seconds.toMillis.toInt)()

  val serviceUrl = "https://itest-virkailija.oph.ware.fi/koodisto-service"

  var pakolliset:Set[String] = Set()

  var suoritusRequests:Cancellable = new Cancellable {
    override def cancel(): Boolean = true

    override def isCancelled: Boolean = true
  }

  override def preStart(): Unit = {


    findPakolliset.onSuccess{
      case aineet:Set[String] => pakolliset = aineet
      suoritusRequests = context.system.scheduler.schedule(10.seconds, 1.hour, suoritusRekisteri, SuoritusQuery(None, Some(Kausi.Kevät), Some("2014"), None))(executionContext, self)
      log.info("perusopetus sanity actor started")
    }


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
    case s :: rest => self ! s
                      if (rest != Nil) self ! rest
    case s: Suoritus with Identified => findBy(ArvosanaQuery(Some(s.id))).map(Todistus(s, _)) pipeTo self
    case Todistus(suoritus, arvosanas) =>  (suoritus.id, suoritus) match {
      case (id, Suoritus(`perusopetus`, _, _, _ ,oppilas ,_, _))  => if (invalid(arvosanas)) {
        problems = (oppilas, id) +: problems
        log.warning(s"problem with suoritus $id for oppilas $oppilas")
      }

    }
  }

  def invalid(arvosanas: Seq[Arvosana]) = {
    pakolliset.exists(!arvosanas.map(_.aine).toSet.contains(_))
  }

  override def identify(o: Arvosana): Arvosana with Identified = ???

}

case class Todistus(suoritus:Suoritus with Identified, arvosanas: Seq[Arvosana])
