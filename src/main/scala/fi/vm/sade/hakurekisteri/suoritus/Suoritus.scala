package fi.vm.sade.hakurekisteri.suoritus

import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.LocalDate
import fi.vm.sade.hakurekisteri.rest.support.{Kausi, Resource}
import fi.vm.sade.hakurekisteri.rest.support.Kausi.Kausi

object yksilollistaminen extends Enumeration {
  type Yksilollistetty = Value
  val Ei = Value("Ei")
  val Osittain = Value("Osittain")
  val Kokonaan = Value("Kokonaan")
  val Alueittain = Value("Alueittain")
}

import yksilollistaminen._

case class Komoto(oid: String, komo: String, tarjoaja: String, alkamisvuosi: String, alkamiskausi: Kausi)

case class Suoritus(komo: String,
                    myontaja: String,
                    tila: String,
                    valmistuminen: LocalDate,
                    henkiloOid: String,
                    yksilollistaminen: Yksilollistetty,
                    suoritusKieli: String,
                    opiskeluoikeus: Option[UUID] = None, source: String ) extends Resource[UUID] {
  override def identify(id: UUID): this.type with Identified[UUID] = Suoritus.identify(this,id).asInstanceOf[this.type with Identified[UUID]]
}

object Suoritus {
  def identify(o: Suoritus): Suoritus with Identified[UUID] = o match {
    case o: Suoritus with Identified[UUID] => o
    case _ => o.identify(UUID.randomUUID)
  }

  def identify(o: Suoritus, identity: UUID) = {
    new Suoritus(o.komo, o.myontaja, o.tila, o.valmistuminen, o.henkiloOid, o.yksilollistaminen, o.suoritusKieli, o.opiskeluoikeus, o.source) with Identified[UUID] {
      val id: UUID = identity
    }
  }
}

