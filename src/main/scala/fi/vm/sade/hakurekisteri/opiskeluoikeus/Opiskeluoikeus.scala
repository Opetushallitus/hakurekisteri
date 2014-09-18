package fi.vm.sade.hakurekisteri.opiskeluoikeus

import java.util.UUID

import fi.vm.sade.hakurekisteri.rest.support.Resource
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.{ReadableInstant, LocalDate}
import fi.vm.sade.hakurekisteri.dates.Ajanjakso

case class Opiskeluoikeus(aika: Ajanjakso,
                          henkiloOid: String,
                          komo: String,
                          myontaja: String,
                          source : String) extends Resource[UUID] {
  override def identify(id: UUID): this.type with Identified[UUID] = Opiskeluoikeus.identify(this,id).asInstanceOf[this.type with Identified[UUID]]
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



  def identify(o: Opiskeluoikeus): Opiskeluoikeus with Identified[UUID] = o match {
    case o: Opiskeluoikeus with Identified[UUID] => o
    case _ => o.identify(UUID.randomUUID)
  }

  def identify(o: Opiskeluoikeus, identity: UUID) = {
    new Opiskeluoikeus(o.aika, o.henkiloOid, o.komo, o.myontaja, o.source) with Identified[UUID] {
      val id: UUID = identity
    }
  }
}