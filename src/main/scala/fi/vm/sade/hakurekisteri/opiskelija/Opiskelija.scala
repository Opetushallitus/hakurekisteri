package fi.vm.sade.hakurekisteri.opiskelija

import java.util.UUID

import fi.vm.sade.hakurekisteri.rest.support.UUIDResource
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.DateTime

case class OpiskelijaCore(oppilaitosOid: String, luokkataso: String, henkiloOid: String)

case class Opiskelija(oppilaitosOid: String, luokkataso: String, luokka: String, henkiloOid: String, alkuPaiva: DateTime, loppuPaiva: Option[DateTime] = None, source: String) extends UUIDResource[Opiskelija] {

  if (loppuPaiva.isDefined)
    require(!loppuPaiva.get.isBefore(alkuPaiva), "HenkiloOid: " + henkiloOid + " : loppuPaiva must be after alkuPaiva. Alku: "+alkuPaiva+", loppu: "+loppuPaiva)

  override def identify(identity: UUID): Opiskelija with Identified[UUID] = new IdentifiedOpiskelija(this, identity)

  override val core = OpiskelijaCore(oppilaitosOid: String, luokkataso: String, henkiloOid: String)

  override def toString: String = {
    s"Opiskelija(oppilaitosOid=$oppilaitosOid, luokkataso=$luokkataso, luokka=$luokka, henkiloOid=$henkiloOid, alkuPaiva=$alkuPaiva, loppuPaiva=$loppuPaiva, source=$source)"
  }
}

class IdentifiedOpiskelija(o: Opiskelija, val id: UUID) extends Opiskelija(o.oppilaitosOid, o.luokkataso, o.luokka, o.henkiloOid, o.alkuPaiva, o.loppuPaiva, o.source) with Identified[UUID]
