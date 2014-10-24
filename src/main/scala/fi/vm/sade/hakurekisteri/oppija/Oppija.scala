package fi.vm.sade.hakurekisteri.oppija

import fi.vm.sade.hakurekisteri.rest.support.Resource
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import fi.vm.sade.hakurekisteri.arvosana.Arvosana
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus

case class Oppija(oppijanumero: String, opiskelu:Seq[Opiskelija], suoritukset: Seq[Todistus], opiskeluoikeudet: Seq[Opiskeluoikeus], ensikertalainen: Option[Boolean]) extends Resource[String, Oppija] with Identified[String] {

  override val id = oppijanumero

  override def identify(identity: String): Oppija with Identified[String] = this
  override val source = "1.2.246.562.10.00000000001"

  def newId = oppijanumero

  override val core: AnyRef = oppijanumero
}

case class Todistus(suoritus: Suoritus, arvosanat: Seq[Arvosana])


