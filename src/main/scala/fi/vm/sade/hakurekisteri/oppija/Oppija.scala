package fi.vm.sade.hakurekisteri.oppija

import fi.vm.sade.hakurekisteri.rest.support.Resource
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import fi.vm.sade.hakurekisteri.arvosana.Arvosana
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus

case class Oppija(oppijanumero: String, opiskelu:Seq[Opiskelija], suoritukset: Seq[Todistus], opiskeluoikeudet: Seq[Opiskeluoikeus], ensikertalainen: Option[Boolean]) extends Resource[String]{
  override def identify(id: String): this.type with Identified[String] = Oppija.identify(this,id).asInstanceOf[this.type with Identified[String]]

  override val source = "1.2.246.562.10.00000000001"
}

case class Todistus(suoritus: Suoritus, arvosanat: Seq[Arvosana])


object Oppija {
  def identify(o:Oppija): Oppija with Identified[String] = o.identify(o.oppijanumero)

  def identify(o:Oppija, identity:String) =
    new Oppija(
      o.oppijanumero,
      o.opiskelu,
      o.suoritukset,
      o.opiskeluoikeudet,
      o.ensikertalainen
    ) with Identified[String] {
      val id: String = identity
    }
}

