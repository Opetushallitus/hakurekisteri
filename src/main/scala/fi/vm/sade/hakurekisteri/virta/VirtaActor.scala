package fi.vm.sade.hakurekisteri.virta

import akka.actor.Actor
import akka.event.Logging
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen, Suoritus}
import org.joda.time.LocalDate

import scala.concurrent.{Future, ExecutionContext}

class VirtaActor(virtaClient: VirtaClient) extends Actor {
  implicit val executionContext: ExecutionContext = context.dispatcher
  val log = Logging(context.system, this)

  def receive: Receive = {
    case (oppijanumero: Option[String], hetu: Option[String]) => convert(virtaClient.getOpiskelijanTiedot(oppijanumero = oppijanumero, hetu = hetu), oppijanumero) pipeTo sender
  }

  def opiskeluoikeus2suoritus(o: VirtaOpiskeluoikeus): Suoritus = {
    val valmistuminen = o.loppuPvm match {
      case None => new LocalDate(1900 + o.alkuPvm.getYear + 7, 12, 31)
      case Some(l) => l
    }
    val tila = valmistuminen match {
      case v: LocalDate if v.isBefore(new LocalDate()) => "VALMIS"
      case _ => "KESKEN"
    }
    Suoritus(
      komo = o.koulutuskoodit.head, // FIXME
      myontaja = o.myontaja, // FIXME
      valmistuminen = valmistuminen,
      tila = tila,
      henkiloOid = "", // FIXME
      yksilollistaminen = yksilollistaminen.Ei,
      suoritusKieli = o.kieli
    )
  }

  def tutkinto2suoritus(t: VirtaTutkinto): Suoritus = {
    val valmistuminen = t.suoritusPvm
    val tila = valmistuminen match {
      case v: LocalDate if v.isBefore(new LocalDate()) => "VALMIS"
      case _ => "KESKEN"
    }
    Suoritus(
      komo = t.koulutuskoodi.get, // FIXME
      myontaja = t.myontaja, // FIXME
      valmistuminen = valmistuminen,
      tila = tila,
      henkiloOid = "", // FIXME
      yksilollistaminen = yksilollistaminen.Ei,
      suoritusKieli = t.kieli
    )
  }

  def convert(f: Future[Option[VirtaResult]], oppijanumero: Option[String]): Future[Seq[Suoritus]] = {
    f.map {
      case None => Seq()
      case Some(r) => r.opiskeluoikeudet.map(opiskeluoikeus2suoritus) ++ r.tutkinnot.map(tutkinto2suoritus)
    }
  }
}
