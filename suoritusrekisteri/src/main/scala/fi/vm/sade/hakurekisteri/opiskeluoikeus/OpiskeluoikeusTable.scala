package fi.vm.sade.hakurekisteri.opiskeluoikeus

import java.util.UUID

import org.joda.time.DateTime
import slick.lifted.ShapedValue
import fi.vm.sade.hakurekisteri.rest.support.JournalTable
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._

object OpiskeluoikeusRow {
  type OpiskeluoikeusType = (Long, Option[Long], String, String, String, String)
}

import OpiskeluoikeusRow._

class OpiskeluoikeusTable(tag: Tag)
    extends JournalTable[Opiskeluoikeus, UUID, OpiskeluoikeusType](tag, "opiskeluoikeus") {
  def alkuPaiva: Rep[Long] = column[Long]("alku_paiva")
  def loppuPaiva: Rep[Option[Long]] = column[Option[Long]]("loppu_paiva")
  def henkiloOid: Rep[String] = column[String]("henkilo_oid")
  def komo: Rep[String] = column[String]("komo")
  def myontaja: Rep[String] = column[String]("myontaja")

  override def resourceShape = (alkuPaiva, loppuPaiva, henkiloOid, komo, myontaja, source).shaped

  override def row(oo: Opiskeluoikeus): Option[OpiskeluoikeusType] = Some(
    oo.aika.alku.getMillis,
    oo.aika.loppuOption.map(_.getMillis),
    oo.henkiloOid,
    oo.komo,
    oo.myontaja,
    oo.source
  )

  override val deletedValues: String => OpiskeluoikeusType = (lahde) =>
    (
      0L,
      None,
      "",
      "",
      "",
      lahde
    )
  override val resource: OpiskeluoikeusType => Opiskeluoikeus = {
    case (
          alkuPaiva: Long,
          loppuPaiva: Option[Long],
          henkiloOid: String,
          komo: String,
          myontaja: String,
          source
        ) =>
      Opiskeluoikeus(
        new DateTime(alkuPaiva),
        loppuPaiva.map(new DateTime(_)),
        henkiloOid,
        komo,
        myontaja,
        source
      )
  }
  override val extractSource: OpiskeluoikeusType => String = _._6
}
