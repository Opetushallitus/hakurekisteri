package fi.vm.sade.hakurekisteri.integration.haku

case class RestHaku(
  oid: Option[String],
  hakuaikas: List[RestHakuAika],
  nimi: Map[String, String],
  hakukausiUri: String,
  hakutapaUri: String,
  hakukausiVuosi: Int,
  koulutuksenAlkamiskausiUri: Option[String],
  koulutuksenAlkamisVuosi: Option[Int],
  kohdejoukkoUri: Option[String],
  kohdejoukonTarkenne: Option[String],
  tila: String
) {
  def isJatkotutkintohaku = kohdejoukonTarkenne.exists(_.startsWith("haunkohdejoukontarkenne_3#"))
}
