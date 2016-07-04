package fi.vm.sade.hakurekisteri.integration.tarjonta

case class Koulutuskoodi(arvo: String)
case class Komo(oid: String,
                koulutuskoodi: Koulutuskoodi,
                koulutusmoduuliTyyppi: String,
                koulutusasteTyyppi: String) {
  def isKorkeakoulututkinto: Boolean = koulutusmoduuliTyyppi == "TUTKINTO" && koulutusasteTyyppi == "KORKEAKOULUTUS"
}
