package fi.vm.sade.hakurekisteri.integration.henkilo

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import slick.lifted.Tag

class HenkiloViiteTable(tag: Tag) extends Table[(String, String)](tag, "henkiloviite") {
  def personOid = column[String]("person_oid")
  def linkedOid = column[String]("linked_oid")

  def * = (personOid, linkedOid)
}
