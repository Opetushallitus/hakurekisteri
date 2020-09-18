package fi.vm.sade.hakurekisteri.integration.virta

import org.joda.time.LocalDate

case class VirtaOpiskeluoikeus(
  alkuPvm: LocalDate,
  loppuPvm: Option[LocalDate],
  myontaja: String,
  koulutuskoodit: Seq[String],
  kieli: String
)
