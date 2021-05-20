package fi.vm.sade.hakurekisteri.integration.koski

case class OppivelvollisuusTieto(
  oid: String,
  oppivelvollisuusVoimassaAsti: Option[String],
  oikeusMaksuttomaanKoulutukseenVoimassaAsti: Option[String]
)
