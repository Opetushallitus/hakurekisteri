package fi.vm.sade.hakurekisteri.integration.organisaatio

case class
Organisaatio(oid: String,
             nimi: Map[String, String],
             toimipistekoodi: Option[String],
             oppilaitosKoodi: Option[String],
             parentOid: Option[String],
             parentOidPath: Option[String],
             children: Seq[Organisaatio])

object Organisaatio {
  def isOrganisaatioOid(s: String): Boolean = s.matches("1(\\.[0-9]+)+")
}

case class ChildOids(oids: Seq[String])
