package fi.vm.sade.hakurekisteri.integration.hakukohde

case class Hakukohde(
  oid: String,
  hakukohdeKoulutusOids: Seq[String],
  ulkoinenTunniste: Option[String],
  tarjoajaOids: Option[Set[String]]
)
