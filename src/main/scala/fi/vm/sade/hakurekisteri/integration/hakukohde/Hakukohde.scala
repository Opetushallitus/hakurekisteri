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

object Hakukohde {
  private val KOUTA_OID_LENGTH: Int = 35

  def isKoutaHakukohdeOid(oid: String): Boolean = if (
    oid == null || oid.length < KOUTA_OID_LENGTH
  ) {
    false
  } else {
    true
  }
}
