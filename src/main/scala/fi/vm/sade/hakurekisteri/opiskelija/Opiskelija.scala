package fi.vm.sade.hakurekisteri.opiskelija

import java.util.{UUID, Date}
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.DateTime

case class Opiskelija(oppilaitosOid: String, luokkataso: String, luokka: String, henkiloOid: String, alkuPaiva: DateTime, loppuPaiva: Option[DateTime] = None)

object Opiskelija{


  def identify(o:Opiskelija, identity:UUID) = {
    new Opiskelija(o.oppilaitosOid, o.luokkataso, o.luokka, o.henkiloOid, o.alkuPaiva, o.loppuPaiva) with Identified{
      val id: UUID = identity
    }
  }

  def identify(o:Opiskelija): Opiskelija with Identified = o match {
    case o: Opiskelija with Identified => o
    case _ => new Opiskelija(o.oppilaitosOid, o.luokkataso, o.luokka, o.henkiloOid, o.alkuPaiva, o.loppuPaiva) with Identified{
      val id: UUID = UUID.randomUUID()
    }
  }

}

