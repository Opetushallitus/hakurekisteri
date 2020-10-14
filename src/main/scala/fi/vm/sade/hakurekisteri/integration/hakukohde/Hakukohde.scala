package fi.vm.sade.hakurekisteri.integration.hakukohde

@SerialVersionUID(1L)
case class Hakukohde(
  oid: String,
  hakukohdeKoulutusOids: Seq[String],
  ulkoinenTunniste: Option[String],
  tarjoajaOids: Option[Set[String]]
)
