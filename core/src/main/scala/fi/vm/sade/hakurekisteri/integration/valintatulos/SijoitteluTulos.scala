package fi.vm.sade.hakurekisteri.integration.valintatulos

import scala.util.Try

object Valintatila extends Enumeration {
  type Valintatila = Value
  val HYVAKSYTTY = Value("HYVAKSYTTY")
  val HARKINNANVARAISESTI_HYVAKSYTTY = Value("HARKINNANVARAISESTI_HYVAKSYTTY")
  val VARASIJALTA_HYVAKSYTTY = Value("VARASIJALTA_HYVAKSYTTY")
  val VARALLA = Value("VARALLA")
  val PERUUTETTU = Value("PERUUTETTU")
  val PERUNUT = Value("PERUNUT")
  val HYLATTY = Value("HYLATTY")
  val PERUUNTUNUT = Value("PERUUNTUNUT")
  val KESKEN = Value("KESKEN")

  def valueOption(t: String): Option[Valintatila.Value] = {
    Try(withName(t)).toOption
  }

  def isHyvaksytty(t: Valintatila): Boolean =
    t == HYVAKSYTTY || t == HARKINNANVARAISESTI_HYVAKSYTTY || t == VARASIJALTA_HYVAKSYTTY
}

object Vastaanottotila extends Enumeration {
  type Vastaanottotila = Value
  val KESKEN = Value("KESKEN")
  val VASTAANOTTANUT = Value("VASTAANOTTANUT")
  val EI_VASTAANOTETTU_MAARA_AIKANA = Value("EI_VASTAANOTETTU_MAARA_AIKANA")
  val PERUNUT = Value("PERUNUT")
  val PERUUTETTU = Value("PERUUTETTU")
  val EHDOLLISESTI_VASTAANOTTANUT = Value("EHDOLLISESTI_VASTAANOTTANUT")
  val VASTAANOTTANUT_SITOVASTI = Value("VASTAANOTTANUT_SITOVASTI")

  def valueOption(t: String): Option[Vastaanottotila.Value] = {
    Try(withName(t)).toOption
  }

  def isVastaanottanut(t: Vastaanottotila): Boolean =
    t == VASTAANOTTANUT || t == EHDOLLISESTI_VASTAANOTTANUT || t == VASTAANOTTANUT_SITOVASTI
}

object Ilmoittautumistila extends Enumeration {
  type Ilmoittautumistila = Value
  val EI_TEHTY = Value("EI_TEHTY") // Ei tehty
  val LASNA_KOKO_LUKUVUOSI = Value("LASNA_KOKO_LUKUVUOSI") // Läsnä (koko lukuvuosi)
  val POISSA_KOKO_LUKUVUOSI = Value("POISSA_KOKO_LUKUVUOSI") // Poissa (koko lukuvuosi)
  val EI_ILMOITTAUTUNUT = Value("EI_ILMOITTAUTUNUT") // Ei ilmoittautunut
  val LASNA_SYKSY = Value("LASNA_SYKSY") // Läsnä syksy, poissa kevät
  val POISSA_SYKSY = Value ("POISSA_SYKSY") // Poissa syksy, läsnä kevät
  val LASNA = Value("LASNA") // Läsnä, keväällä alkava koulutus
  val POISSA = Value("POISSA") // Poissa, keväällä alkava koulutus

  def valueOption(t: String): Option[Ilmoittautumistila.Value] = {
    Try(withName(t)).toOption
  }
}

import Valintatila.Valintatila
import Vastaanottotila.Vastaanottotila
import Ilmoittautumistila.Ilmoittautumistila

case class HakutoiveenIlmoittautumistila(ilmoittautumistila: Ilmoittautumistila)

case class ValintaTulosHakutoive(hakukohdeOid: String,
                                 tarjoajaOid: String,
                                 valintatila: Valintatila,
                                 vastaanottotila: Vastaanottotila,
                                 ilmoittautumistila: HakutoiveenIlmoittautumistila,
                                 pisteet: Option[BigDecimal])

case class ValintaTulos(hakemusOid: String, hakutoiveet: Seq[ValintaTulosHakutoive])

trait SijoitteluTulos {
  def pisteet(hakemus: String, kohde: String): Option[BigDecimal]
  def valintatila(hakemus: String, kohde: String): Option[Valintatila]
  def vastaanottotila(hakemus: String, kohde: String): Option[Vastaanottotila]
  def ilmoittautumistila(hakemus: String, kohde: String): Option[Ilmoittautumistila]
}


