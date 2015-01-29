package fi.vm.sade.hakurekisteri.integration.virta

import org.joda.time.LocalDate

case class VirtaTutkinto(suoritusPvm: LocalDate,
                         koulutuskoodi: Option[String],
                         opintoala1995: Option[String],
                         koulutusala2002: Option[String],
                         myontaja: String,
                         kieli: String)
