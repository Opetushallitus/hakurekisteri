package fi.vm.sade.hakurekisteri.opiskelija

import java.util.{UUID, Date}

case class Opiskelija(oppilaitosOid: String, luokkataso: String, luokka: String, henkiloOid: String, alkuPaiva: Date = new Date, loppuPaiva: Option[Date] = None)

object Opiskelija{

  def apply(o:Opiskelija, identity:UUID): (Opiskelija, UUID) = {
    (Opiskelija(o.oppilaitosOid, o.luokkataso, o.luokka, o.henkiloOid, o.alkuPaiva, o.loppuPaiva), identity)

  }

  def identify(o:Opiskelija, identity:UUID) = {
    new Opiskelija(o.oppilaitosOid, o.luokkataso, o.luokka, o.henkiloOid, o.alkuPaiva, o.loppuPaiva) with Identified{
      val id: UUID = identity
    }
  }

}

trait Identified {

  val id:UUID

}