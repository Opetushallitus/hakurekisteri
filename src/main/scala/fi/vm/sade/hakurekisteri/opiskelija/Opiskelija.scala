package fi.vm.sade.hakurekisteri.opiskelija

import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.DateTime
import fi.vm.sade.hakurekisteri.rest.support.Resource

case class Opiskelija(oppilaitosOid: String, luokkataso: String, luokka: String, henkiloOid: String, alkuPaiva: DateTime, loppuPaiva: Option[DateTime] = None, source: String) extends Resource[UUID] {
   override def identify(id: UUID): this.type with Identified[UUID] = Opiskelija.identify(this,id).asInstanceOf[this.type with Identified[UUID]]
}


object Opiskelija {

  def identify(o:Opiskelija, identity:UUID) = {
    new Opiskelija(o.oppilaitosOid, o.luokkataso, o.luokka, o.henkiloOid, o.alkuPaiva, o.loppuPaiva, o.source) with Identified[UUID]{
      val id: UUID = identity
    }
  }

  def identify(o:Opiskelija): Opiskelija with Identified[UUID] = o match {
    case o: Opiskelija with Identified[UUID] => o
    case _ => o.identify(UUID.randomUUID())
  }

}

