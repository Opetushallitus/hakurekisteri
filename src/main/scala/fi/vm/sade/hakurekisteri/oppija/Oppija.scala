package fi.vm.sade.hakurekisteri.oppija

import scala.collection.JavaConverters._
import fi.vm.sade.hakurekisteri._
import fi.vm.sade.hakurekisteri.rest.support.Resource
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import fi.vm.sade.hakurekisteri.arvosana.Arvosana
import fi.vm.sade.hakurekisteri.integration.hakemus.dto.SuoritusJaArvosanat
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus

case class Oppija(oppijanumero: String,
                  opiskelu: Seq[Opiskelija],
                  suoritukset: Seq[Todistus],
                  opiskeluoikeudet: Seq[Opiskeluoikeus],
                  ensikertalainen: Option[Boolean]) extends Resource[String, Oppija] with Identified[String] {

  override val id = oppijanumero

  override def identify(identity: String): Oppija with Identified[String] = this
  override val source = Oids.ophOrganisaatioOid

  def newId = oppijanumero

  override val core: AnyRef = oppijanumero

  def getSuorituksetJaArvosanat: Seq[SuoritusJaArvosanat] = {
    this.suoritukset.map { s =>
      val suoritus = s.suoritus.asJava
      val arvosanat = s.arvosanat.map(_.asJava).asJava
      new SuoritusJaArvosanat(suoritus, arvosanat)
    }
  }
}

case class Todistus(suoritus: Suoritus, arvosanat: Seq[Arvosana])

case class InvalidTodistus(suoritus: Suoritus, arvosanat: Seq[Arvosana], errors: Seq[String])

object InvalidTodistus {

  def apply(todistus: Todistus, errors: Seq[String]): InvalidTodistus = InvalidTodistus(todistus.suoritus, todistus.arvosanat, errors)

}


