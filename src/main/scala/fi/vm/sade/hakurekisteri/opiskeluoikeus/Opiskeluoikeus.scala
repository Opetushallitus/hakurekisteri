package fi.vm.sade.hakurekisteri.opiskeluoikeus

import java.util.UUID

import fi.vm.sade.hakurekisteri.rest.support.{UUIDResource, Resource}
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.{ReadableInstant, LocalDate}
import fi.vm.sade.hakurekisteri.dates.Ajanjakso

case class Opiskeluoikeus(aika: Ajanjakso,
                          henkiloOid: String,
                          komo: String,
                          myontaja: String,
                          source : String) extends UUIDResource[Opiskeluoikeus] {
  override def identify(identity: UUID): Opiskeluoikeus with Identified[UUID] = new IdentifiedOpiskeluOikeus(this, identity)
}

class IdentifiedOpiskeluOikeus(o: Opiskeluoikeus, identity: UUID) extends Opiskeluoikeus(o.aika, o.henkiloOid, o.komo, o.myontaja, o.source) with Identified[UUID] {
  val id: UUID = identity
}

object Opiskeluoikeus {

  def apply(alkuPaiva: LocalDate,
            loppuPaiva: Option[LocalDate],
            henkiloOid: String,
            komo: String,
            myontaja: String,
            source : String): Opiskeluoikeus =  Opiskeluoikeus(Ajanjakso(alkuPaiva, loppuPaiva), henkiloOid, komo, myontaja, source)

  def apply(alkuPaiva: ReadableInstant,
            loppuPaiva: Option[ReadableInstant],
            henkiloOid: String,
            komo: String,
            myontaja: String,
            source : String): Opiskeluoikeus = Opiskeluoikeus(Ajanjakso(alkuPaiva, loppuPaiva), henkiloOid, komo, myontaja, source)

  def apply(alkuPaiva: ReadableInstant,
            loppuPaiva: ReadableInstant,
            henkiloOid: String,
            komo: String,
            myontaja: String,
            source : String): Opiskeluoikeus = Opiskeluoikeus(Ajanjakso(alkuPaiva, loppuPaiva), henkiloOid, komo, myontaja, source)


}