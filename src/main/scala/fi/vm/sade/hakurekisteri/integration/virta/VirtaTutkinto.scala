package fi.vm.sade.hakurekisteri.integration.virta

import org.joda.time.LocalDate

case class VirtaTutkinto(
  suoritusPvm: LocalDate,
  koulutuskoodi: Option[String],
  myontaja: String,
  kieli: String
)
