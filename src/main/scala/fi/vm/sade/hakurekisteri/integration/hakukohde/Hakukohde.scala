package fi.vm.sade.hakurekisteri.integration.hakukohde

@SerialVersionUID(2L)
case class Hakukohde(
  oid: String,
  hakukohteenNimet: Map[String, String],
  hakukohdeKoulutusOids: Seq[String],
  ulkoinenTunniste: Option[String],
  tarjoajaOids: Option[Set[String]],
  alinValintaPistemaara: Option[Int]
)
