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
  val VASTAANOTTANUT = Value("VASTAANOTTANUT_SITOVASTI")
  val EI_VASTAANOTETTU_MAARA_AIKANA = Value("EI_VASTAANOTETTU_MAARA_AIKANA")
  val PERUNUT = Value("PERUNUT")
  val PERUUTETTU = Value("PERUUTETTU")
  val OTTANUT_VASTAAN_TOISEN_PAIKAN = Value("OTTANUT_VASTAAN_TOISEN_PAIKAN")
  val EHDOLLISESTI_VASTAANOTTANUT = Value("EHDOLLISESTI_VASTAANOTTANUT")

  def valueOption(t: String): Option[Vastaanottotila.Value] = {
    Try(withName(t)).toOption
  }

  def isVastaanottanut(t: Vastaanottotila): Boolean =
    t == VASTAANOTTANUT || t == EHDOLLISESTI_VASTAANOTTANUT
}

object Ilmoittautumistila extends Enumeration {
  type Ilmoittautumistila = Value
  val EI_TEHTY = Value("EI_TEHTY") // Ei tehty
  val LASNA_KOKO_LUKUVUOSI = Value("LASNA_KOKO_LUKUVUOSI") // Läsnä (koko lukuvuosi)
  val POISSA_KOKO_LUKUVUOSI = Value("POISSA_KOKO_LUKUVUOSI") // Poissa (koko lukuvuosi)
  val EI_ILMOITTAUTUNUT = Value("EI_ILMOITTAUTUNUT") // Ei ilmoittautunut
  val LASNA_SYKSY = Value("LASNA_SYKSY") // Läsnä syksy, poissa kevät
  val POISSA_SYKSY = Value("POISSA_SYKSY") // Poissa syksy, läsnä kevät
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

case class HyvaksymisenEhto(
  ehdollisestiHyvaksyttavissa: Boolean,
  ehtoKoodi: Option[String],
  ehtoFI: Option[String],
  ehtoSV: Option[String],
  ehtoEN: Option[String]
)

case class ValintaTulosJono(
  oid: String,
  nimi: String,
  pisteet: Option[BigDecimal],
  alinHyvaksyttyPistemaara: Option[BigDecimal],
  valintatila: Valintatila,
  julkaistavissa: Boolean,
  valintatapajonoPrioriteetti: Option[Int],
  ehdollisestiHyvaksyttavissa: Boolean,
  varasijanumero: Option[Int],
  eiVarasijatayttoa: Boolean,
  varasijat: Option[Int],
  varasijasaannotKaytossa: Boolean
)
case class ValintaTulosHakutoive(
  hakukohdeOid: String,
  tarjoajaOid: String,
  valintatila: Valintatila,
  vastaanottotila: Vastaanottotila,
  ilmoittautumistila: HakutoiveenIlmoittautumistila,
  hyvaksyttyJaJulkaistuDate: Option[String],
  ehdollisestiHyvaksyttavissa: Boolean,
  ehdollisenHyvaksymisenEhtoKoodi: Option[String],
  ehdollisenHyvaksymisenEhtoFI: Option[String],
  ehdollisenHyvaksymisenEhtoSV: Option[String],
  ehdollisenHyvaksymisenEhtoEN: Option[String],
  pisteet: Option[BigDecimal],
  valintatapajonoOid: String,
  varasijanumero: Option[Int],
  julkaistavissa: Boolean,
  jonokohtaisetTulostiedot: Seq[ValintaTulosJono]
)

case class ValintaTulos(hakemusOid: String, hakutoiveet: Seq[ValintaTulosHakutoive])

@SerialVersionUID(4)
case class SijoitteluTulos(
  hakuOid: String,
  pisteet: Map[(String, String), BigDecimal],
  valintatila: Map[(String, String), Valintatila],
  vastaanottotila: Map[(String, String), Vastaanottotila],
  ilmoittautumistila: Map[(String, String), Ilmoittautumistila],
  valintatapajono: Map[(String, String), String],
  varasijanumero: Map[(String, String), Option[Int]],
  hyvaksymisenEhto: Map[(String, String), HyvaksymisenEhto],
  valinnanAikaleima: Map[(String, String), String]
)

object SijoitteluTulos {
  def apply(hakuOid: String, valintatulos: ValintaTulos): SijoitteluTulos = {
    new SijoitteluTulos(
      hakuOid,
      valintatulos.hakutoiveet
        .filter(h => h.hakukohdeOid.nonEmpty && h.pisteet.nonEmpty)
        .map(h => (valintatulos.hakemusOid, h.hakukohdeOid) -> h.pisteet.get)
        .toMap,
      valintatulos.hakutoiveet
        .map(h => (valintatulos.hakemusOid, h.hakukohdeOid) -> h.valintatila)
        .toMap,
      valintatulos.hakutoiveet
        .map(h => (valintatulos.hakemusOid, h.hakukohdeOid) -> h.vastaanottotila)
        .toMap,
      valintatulos.hakutoiveet
        .map(h =>
          (valintatulos.hakemusOid, h.hakukohdeOid) -> h.ilmoittautumistila.ilmoittautumistila
        )
        .toMap,
      valintatulos.hakutoiveet
        .filter(h => h.valintatapajonoOid.nonEmpty)
        .map(h => (valintatulos.hakemusOid, h.hakukohdeOid) -> h.valintatapajonoOid)
        .toMap,
      valintatulos.hakutoiveet
        .filter(h => h.valintatapajonoOid.nonEmpty)
        .map(h => (valintatulos.hakemusOid, h.hakukohdeOid) -> h.varasijanumero)
        .toMap,
      valintatulos.hakutoiveet
        .map(h =>
          (valintatulos.hakemusOid, h.hakukohdeOid) ->
            HyvaksymisenEhto(
              ehdollisestiHyvaksyttavissa = h.ehdollisestiHyvaksyttavissa,
              ehtoKoodi = h.ehdollisenHyvaksymisenEhtoKoodi,
              ehtoFI = h.ehdollisenHyvaksymisenEhtoFI,
              ehtoSV = h.ehdollisenHyvaksymisenEhtoSV,
              ehtoEN = h.ehdollisenHyvaksymisenEhtoEN
            )
        )
        .toMap,
      valintatulos.hakutoiveet
        .filter(h => h.tarjoajaOid.nonEmpty && h.hyvaksyttyJaJulkaistuDate.nonEmpty)
        .map(h => (valintatulos.hakemusOid, h.hakukohdeOid) -> h.hyvaksyttyJaJulkaistuDate.get)
        .toMap
    )
  }

  def apply(hakuOid: String, valintatulokset: Seq[ValintaTulos]): SijoitteluTulos = {
    new SijoitteluTulos(
      hakuOid,
      valintatulokset
        .flatMap(valintatulos => {
          valintatulos.hakutoiveet
            .filter(h => h.hakukohdeOid.nonEmpty && h.pisteet.nonEmpty)
            .map(h => (valintatulos.hakemusOid, h.hakukohdeOid) -> h.pisteet.get)
        })
        .toMap,
      valintatulokset
        .flatMap(valintatulos => {
          valintatulos.hakutoiveet.map(h =>
            (valintatulos.hakemusOid, h.hakukohdeOid) -> h.valintatila
          )
        })
        .toMap,
      valintatulokset
        .flatMap(valintatulos => {
          valintatulos.hakutoiveet.map(h =>
            (valintatulos.hakemusOid, h.hakukohdeOid) -> h.vastaanottotila
          )
        })
        .toMap,
      valintatulokset
        .flatMap(valintatulos => {
          valintatulos.hakutoiveet.map(h =>
            (valintatulos.hakemusOid, h.hakukohdeOid) -> h.ilmoittautumistila.ilmoittautumistila
          )
        })
        .toMap,
      valintatulokset
        .flatMap(valintatulos => {
          valintatulos.hakutoiveet
            .filter(h => h.valintatapajonoOid.nonEmpty)
            .map(h => (valintatulos.hakemusOid, h.hakukohdeOid) -> h.valintatapajonoOid)
        })
        .toMap,
      Map.empty,
      valintatulokset
        .flatMap(valintatulos => {
          valintatulos.hakutoiveet
            .map(h =>
              (valintatulos.hakemusOid, h.hakukohdeOid) ->
                HyvaksymisenEhto(
                  ehdollisestiHyvaksyttavissa = h.ehdollisestiHyvaksyttavissa,
                  ehtoKoodi = h.ehdollisenHyvaksymisenEhtoKoodi,
                  ehtoFI = h.ehdollisenHyvaksymisenEhtoFI,
                  ehtoSV = h.ehdollisenHyvaksymisenEhtoSV,
                  ehtoEN = h.ehdollisenHyvaksymisenEhtoEN
                )
            )
        })
        .toMap,
      valintatulokset
        .flatMap(valintatulos => {
          valintatulos.hakutoiveet
            .filter(h => h.tarjoajaOid.nonEmpty && h.hyvaksyttyJaJulkaistuDate.nonEmpty)
            .map(h => (valintatulos.hakemusOid, h.hakukohdeOid) -> h.hyvaksyttyJaJulkaistuDate.get)
        })
        .toMap
    )
  }
}
