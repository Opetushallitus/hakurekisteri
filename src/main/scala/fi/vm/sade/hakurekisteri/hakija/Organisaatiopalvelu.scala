package fi.vm.sade.hakurekisteri.hakija

trait Organisaatiopalvelu {

  def get(str: String): Option[Organisaatio]

}

object RestOrganisaatiopalvelu extends Organisaatiopalvelu {

  override def get(str: String): Option[Organisaatio] = {
    None
  }

}

case class Organisaatio(oid: String, nimi: Map[String, String], toimipistekoodi: String, oppilaitosKoodi: String)