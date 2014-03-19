package fi.vm.sade.hakurekisteri.hakija

trait Henkilopalvelu {

  def find(henkiloOid: String): Option[Henkilo]

}

object RestHenkilopalvelu extends Henkilopalvelu {

  override def find(henkiloOid: String): Option[Henkilo] = {
    None
  }

}