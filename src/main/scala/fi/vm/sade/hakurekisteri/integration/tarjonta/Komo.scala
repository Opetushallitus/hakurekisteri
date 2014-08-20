package fi.vm.sade.hakurekisteri.integration.tarjonta

case class Koulutusala(uri: String, arvo: String, nimi: String)

case class Opintoala(uri: String, arvo: String, nimi: String)

case class Komo(oid: String, koulutusala: Koulutusala, opintoala: Opintoala, koulutusmoduuliTyyppi: String, koulutusasteTyyppi: String)
