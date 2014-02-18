package fi.vm.sade.hakurekisteri.opiskelija

import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.DateTime
import fi.vm.sade.hakurekisteri.rest.support.Resource

case class Opiskelija(oppilaitosOid: String, luokkataso: String, luokka: String, henkiloOid: String, alkuPaiva: DateTime, loppuPaiva: Option[DateTime] = None) extends Resource{
   override def identify[R <: Opiskelija](id: UUID): R with Identified = Opiskelija.identify(this,id).asInstanceOf[R with Identified]

}


object Opiskelija{


  def identify(o:Opiskelija, identity:UUID) = {
    new Opiskelija(o.oppilaitosOid, o.luokkataso, o.luokka, o.henkiloOid, o.alkuPaiva, o.loppuPaiva) with Identified{
      val id: UUID = identity
    }
  }

  def identify(o:Opiskelija): Opiskelija with Identified = o match {
    case o: Opiskelija with Identified => o
    case _ => o.identify(UUID.randomUUID())
  }


}

