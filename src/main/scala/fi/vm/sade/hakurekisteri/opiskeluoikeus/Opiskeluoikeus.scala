package fi.vm.sade.hakurekisteri.opiskeluoikeus

import java.util.UUID

import fi.vm.sade.hakurekisteri.rest.support.Resource
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.LocalDate

case class Opiskeluoikeus(alkuPaiva: LocalDate,
                          loppuPaiva: Option[LocalDate],
                          henkiloOid: String,
                          komo: String,
                          myontaja: String,
                          source : String) extends Resource[UUID] {
  override def identify(id: UUID): this.type with Identified[UUID] = Opiskeluoikeus.identify(this,id).asInstanceOf[this.type with Identified[UUID]]
}

object Opiskeluoikeus {
  def identify(o: Opiskeluoikeus): Opiskeluoikeus with Identified[UUID] = o match {
    case o: Opiskeluoikeus with Identified[UUID] => o
    case _ => o.identify(UUID.randomUUID)
  }

  def identify(o: Opiskeluoikeus, identity: UUID) = {
    new Opiskeluoikeus(o.alkuPaiva, o.loppuPaiva, o.henkiloOid, o.komo, o.myontaja, o.source) with Identified[UUID] {
      val id: UUID = identity
    }
  }
}