package fi.vm.sade.hakurekisteri.integration.tarjonta

case class Komo(oid: String,
                koulutusmoduuliTyyppi: String,
                koulutusasteTyyppi: String) {
  def isKorkeakoulututkinto: Boolean = koulutusmoduuliTyyppi == "TUTKINTO" && koulutusasteTyyppi == "KORKEAKOULUTUS"
}
