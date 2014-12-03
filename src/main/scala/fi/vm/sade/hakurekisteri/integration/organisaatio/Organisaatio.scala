package fi.vm.sade.hakurekisteri.integration.organisaatio

case class
Organisaatio(oid: String,
                        nimi: Map[String, String],
                        toimipistekoodi: Option[String],
                        oppilaitosKoodi: Option[String],
                        parentOid: Option[String])
