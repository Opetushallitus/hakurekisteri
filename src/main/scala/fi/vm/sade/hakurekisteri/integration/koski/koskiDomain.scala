package fi.vm.sade.hakurekisteri.integration.koski

import fi.vm.sade.hakurekisteri.Oids
import org.joda.time.LocalDate

import scala.collection.immutable.ListMap

case class MuuttuneetOppijatResponse(result: Seq[String], mayHaveMore: Boolean, nextCursor: String)

case class KoskiHenkiloContainer(
  henkilö: KoskiHenkilo,
  opiskeluoikeudet: Seq[KoskiOpiskeluoikeus]
)

case class KoskiHenkilo(
  oid: Option[String],
  hetu: Option[String],
  syntymäaika: Option[String],
  etunimet: Option[String],
  kutsumanimi: Option[String],
  sukunimi: Option[String]
) {}
case class KoskiOpiskeluoikeus(
  oid: Option[String], //LUVA data does not have an OID
  oppilaitos: Option[KoskiOrganisaatio],
  tila: KoskiOpiskeluoikeusjakso,
  päättymispäivä: Option[String],
  lisätiedot: Option[KoskiLisatiedot],
  suoritukset: Seq[KoskiSuoritus],
  tyyppi: Option[KoskiKoodi],
  aikaleima: Option[String]
) {

  def opiskeluoikeusSisaltaaErityisentutkinnon: Boolean =
    suoritukset.exists(_.isErityinentutkinto())

  def opiskeluoikeusSisaltaaPeruskoulunLuokkaAsteen: Boolean =
    suoritukset.exists(
      _.koulutusmoduuli.tunniste.exists(_.koodistoUri == "perusopetuksenluokkaaste")
    )

  def opiskeluoikeusSisaltaaYsisuorituksen: Boolean =
    suoritukset.exists(_.koulutusmoduuli.tunniste.exists(_.koodiarvo == "9"))

  def opiskeluoikeusSisaltaaPerusopetuksenOppiaineenOppimaaran: Boolean =
    (hasPerusopetuksenOppiaineenOppimaara || hasNuortenPerusopetuksenOppiaineenOppimaara) && !opiskeluoikeusSisaltaaPeruskoulunLuokkaAsteen

  def getLatestYsiSuoritus: KoskiSuoritus = suoritukset
    .filter(s => s.koulutusmoduuli.tunniste.getOrElse(null).koodiarvo.equals("9"))
    .sortBy(_.alkamispäivä.getOrElse(null))(Ordering[String].reverse)
    .head

  def getLatestSeiskaKasiSuoritus: Option[KoskiSuoritus] = {
    suoritukset
      .filter(s =>
        s.koulutusmoduuli.tunniste.exists(tunniste =>
          tunniste.koodiarvo.equals("7") || tunniste.koodiarvo.equals("8")
        )
      )
      .sortBy(_.alkamispäivä.getOrElse(null))(Ordering[String].reverse)
      .headOption
  }

  def getSeiskaKasiluokanAlkamispaiva: Option[LocalDate] = {
    getLatestSeiskaKasiSuoritus.flatMap(suoritus =>
      suoritus.alkamispäivä.map(ap => LocalDate.parse(ap))
    )
  }
  def getYsiluokanAlkamispaiva: Option[LocalDate] = {
    if (opiskeluoikeusSisaltaaYsisuorituksen) {
      getLatestYsiSuoritus.alkamispäivä.map(ap => LocalDate.parse(ap))
    } else
      None
  }

  def isStateContainingOpiskeluoikeus: Boolean =
    oppilaitos.isDefined && oppilaitos.get.oid.isDefined && tila.opiskeluoikeusjaksot.nonEmpty

  def isAikuistenPerusopetus: Boolean =
    tyyppi.getOrElse(KoskiKoodi("", "")).koodiarvo.contentEquals("aikuistenperusopetus")

  def isAikuistenPerusopetuksenOppimaara: Boolean =
    isAikuistenPerusopetus && suoritukset.exists(
      _.tyyppi.exists(_.koodiarvo == "aikuistenperusopetuksenoppimaara")
    )

  def hasPerusopetuksenOppiaineenOppimaara: Boolean =
    suoritukset.exists(
      _.tyyppi.exists(_.koodiarvo == "perusopetuksenoppiaineenoppimaara")
    )

  def hasNuortenPerusopetuksenOppiaineenOppimaara: Boolean =
    suoritukset.exists(
      _.tyyppi.exists(_.koodiarvo == "nuortenperusopetuksenoppiaineenoppimaara")
    )

  def isKotiopetuslainen: Boolean = {
    getYsiluokanAlkamispaiva match {
      case Some(alkamispaiva) =>
        lisätiedot.exists(lt =>
          lt.kotiopetusjaksot
            .getOrElse(List.empty)
            .exists(koj =>
              koj.alku.nonEmpty && (koj.loppu.isEmpty ||
                alkamispaiva.isBefore(LocalDate.parse(koj.loppu.get)))
            ) ||
            lt.kotiopetus.toList.exists(koj =>
              koj.alku.nonEmpty && (koj.loppu.isEmpty ||
                alkamispaiva.isBefore(LocalDate.parse(koj.loppu.get)))
            )
        )
      case None =>
        lisätiedot.exists(lt =>
          lt.kotiopetusjaksot.getOrElse(List.empty).exists(koj => koj.alku.nonEmpty)
            || lt.kotiopetus.toList.exists(koj => koj.alku.nonEmpty)
        )
    }
  }
}

case class KoskiOpiskeluoikeusjakso(opiskeluoikeusjaksot: Seq[KoskiTila]) {
  def findEarliestLasnaDate: Option[LocalDate] = {
    opiskeluoikeusjaksot
      .filter(_.tila.koodiarvo == "lasna")
      .map(o => LocalDate.parse(o.alku))
      .sortBy(_.toDate)
      .headOption
  }
  def determineSuoritusTila: String = {
    opiskeluoikeusjaksot
      .sortBy(o => LocalDate.parse(o.alku).toDate)
      .lastOption
      .flatMap(t => KoskiOpiskeluoikeusjakso.koskiTilaToSureSuoritusTila.get(t.tila.koodiarvo))
      .getOrElse {
        throw new IllegalArgumentException(
          s"Ei löytynyt mäppäystä Koski-tilasta Suren suorituksen tilaan:" +
            s"$opiskeluoikeusjaksot (mäppäykset: ${KoskiOpiskeluoikeusjakso.koskiTilaToSureSuoritusTila})"
        )
      }
  }
}

object KoskiOpiskeluoikeusjakso {
  val koskiTilaToSureSuoritusTila: ListMap[String, String] = ListMap(
    "eronnut" -> "KESKEYTYNYT",
    "hyvaksytystisuoritettu" -> "VALMIS",
    "katsotaaneronneeksi" -> "KESKEYTYNYT",
    "keskeytynyt" -> "KESKEYTYNYT",
    "lasna" -> "KESKEN",
    "loma" -> "KESKEN",
    "mitatoity" -> "KESKEYTYNYT",
    "paattynyt" -> "KESKEYTYNYT",
    "peruutettu" -> "KESKEYTYNYT",
    "valiaikaisestikeskeytynyt" -> "KESKEYTYNYT",
    "valmistunut" -> "VALMIS"
  )
}

case class KoskiTila(alku: String, tila: KoskiKoodi)

case class KoskiOrganisaatio(oid: Option[String])

case class KoskiSuoritustapa(koodiarvo: String)

case class KoskiKoulusivistyskieli(koodiarvo: String)

case class KoskiSuoritus(
  luokka: Option[String],
  koulutusmoduuli: KoskiKoulutusmoduuli,
  tyyppi: Option[KoskiKoodi],
  kieli: Option[KoskiKieli],
  pakollinen: Option[Boolean],
  toimipiste: Option[KoskiOrganisaatio],
  vahvistus: Option[KoskiVahvistus],
  suorituskieli: Option[KoskiKieli],
  arviointi: Option[Seq[KoskiArviointi]],
  yksilöllistettyOppimäärä: Option[Boolean],
  osasuoritukset: Seq[KoskiOsasuoritus],
  ryhmä: Option[String],
  alkamispäivä: Option[String],
  //jääLuokalle is only used for peruskoulu
  jääLuokalle: Option[Boolean],
  tutkintonimike: Seq[KoskiKoodi] = Nil,
  tila: Option[KoskiKoodi] = None,
  suoritustapa: Option[KoskiSuoritustapa],
  koulusivistyskieli: Option[Seq[KoskiKoulusivistyskieli]]
) {

  def isOpistovuosi(): Boolean = {
    tyyppi.exists(_.koodiarvo == "vstoppivelvollisillesuunnattukoulutus")
  }

  def isErityinentutkinto(): Boolean = {
    suoritustapa.exists(tapa => tapa.koodiarvo == "erityinentutkinto")
  }

  def isTuva(): Boolean = {
    tyyppi.exists(_.koodiarvo == "tuvakoulutuksensuoritus")
  }

  def isLukionOrPerusopetuksenoppimaara(): Boolean = {
    tyyppi.exists(_.koodiarvo == "perusopetuksenoppimaara") ||
    tyyppi.exists(_.koodiarvo == "lukionoppimaara") ||
    tyyppi.exists(_.koodiarvo == "aikuistenperusopetuksenoppimaara")
  }

  def laajuusVahintaan(min: BigDecimal): Boolean = {
    val sum =
      if (isOpistovuosi) {
        osasuoritukset
          .map(o =>
            o.osasuoritukset
              .map(os =>
                os.filter(_.arviointi.exists(_.hyväksytty.contains(true)))
                  .flatMap(_.koulutusmoduuli.laajuus)
                  .map(_.arvo.getOrElse(BigDecimal(0)))
                  .sum
              )
              .getOrElse(BigDecimal(0))
          )
          .sum
      } else {
        osasuoritukset
          .filter(_.arviointi.exists(_.hyväksytty.contains(true)))
          .flatMap(_.koulutusmoduuli.laajuus)
          .map(_.arvo.getOrElse(BigDecimal(0)))
          .sum
      }
    sum >= min
  }

  def getKomoOid(isAikuistenPerusOpetus: Boolean): String = {
    tyyppi match {
      case Some(k) =>
        if (isAikuistenPerusOpetus && k.koodiarvo == "perusopetuksenoppiaineenoppimaara") {
          Oids.perusopetuksenOppiaineenOppimaaraOid
        } else {
          k.koodiarvo match {
            case "perusopetuksenoppimaara" | "perusopetuksenoppiaineenoppimaara" |
                "aikuistenperusopetuksenoppimaara" =>
              Oids.perusopetusKomoOid
            case "nuortenperusopetuksenoppiaineenoppimaara" =>
              Oids.perusopetuksenOppiaineenOppimaaraOid
            case "perusopetuksenvuosiluokka"             => Oids.perusopetusLuokkaKomoOid
            case "valma"                                 => Oids.valmaKomoOid
            case "telma"                                 => Oids.telmaKomoOid
            case "luva"                                  => Oids.lukioonvalmistavaKomoOid
            case "tuvakoulutuksensuoritus"               => Oids.tuvaKomoOid
            case "vstoppivelvollisillesuunnattukoulutus" => Oids.opistovuosiKomoOid
            case "perusopetuksenlisaopetus"              => Oids.lisaopetusKomoOid
            case "ammatillinentutkinto" =>
              koulutusmoduuli.koulutustyyppi match {
                case Some(KoskiKoodi("12", _)) => Oids.erikoisammattitutkintoKomoOid
                case Some(KoskiKoodi("11", _)) => Oids.ammatillinentutkintoKomoOid
                case _                         => Oids.ammatillinenKomoOid
              }
            case "lukionoppimaara" => Oids.lukioKomoOid
            case _                 => Oids.DUMMYOID
          }
        }
      case _ => Oids.DUMMYOID
    }
  }

  def getLuokkataso(isAikuistenPerusOpetus: Boolean): Option[String] = {
    tyyppi match {
      case Some(k) =>
        if (
          (isAikuistenPerusOpetus && k.koodiarvo == "perusopetuksenoppiaineenoppimaara")
          || k.koodiarvo == "aikuistenperusopetuksenoppimaara"
        ) {
          Some(KoskiUtil.AIKUISTENPERUS_LUOKKAASTE)
        } else {
          k.koodiarvo match {
            case "perusopetuksenoppimaara" | "perusopetuksenvuosiluokka" =>
              koulutusmoduuli.tunniste.flatMap(k => Some(k.koodiarvo))
            case _ => None
          }
        }
      case _ => None
    }
  }
}

case class KoskiOsasuoritus(
  koulutusmoduuli: KoskiKoulutusmoduuli,
  tyyppi: KoskiKoodi,
  arviointi: Seq[KoskiArviointi],
  pakollinen: Option[Boolean],
  yksilöllistettyOppimäärä: Option[Boolean],
  osasuoritukset: Option[Seq[KoskiOsasuoritus]]
) {

  def opintopisteidenMaara: BigDecimal = {
    val laajuus: Option[KoskiValmaLaajuus] =
      koulutusmoduuli.laajuus.filter(_.yksikkö.koodiarvo == "2")
    val arvo: Option[BigDecimal] = laajuus.flatMap(_.arvo)
    arvo.getOrElse(KoskiUtil.ZERO)
  }

  def isLukioSuoritus: Boolean = {
    koulutusmoduuli.tunniste.map(_.koodiarvo) match {
      case Some(koodi) =>
        KoskiUtil.lukioaineetRegex.flatMap(_.findFirstIn(koodi)).nonEmpty
      case _ => false
    }
  }

  def isPK: Boolean = {
    koulutusmoduuli.tunniste.map(_.koodiarvo) match {
      case Some(koodi) =>
        KoskiUtil.peruskouluaineetRegex.flatMap(_.findFirstIn(koodi)).nonEmpty
      case _ => false
    }
  }
}

case class KoskiArviointi(
  arvosana: KoskiKoodi,
  hyväksytty: Option[Boolean],
  päivä: Option[String]
) {
  def isPKValue: Boolean = {
    KoskiUtil.peruskoulunArvosanat.contains(arvosana.koodiarvo)
  }
}

case class KoskiKoulutusmoduuli(
  tunniste: Option[KoskiKoodi],
  kieli: Option[KoskiKieli],
  koulutustyyppi: Option[KoskiKoodi],
  laajuus: Option[KoskiValmaLaajuus],
  pakollinen: Option[Boolean]
)

case class KoskiValmaLaajuus(arvo: Option[BigDecimal], yksikkö: KoskiKoodi)

case class KoskiKoodi(koodiarvo: String, koodistoUri: String) {
  def valinnainen: Boolean = {
    KoskiUtil.valinnaiset.contains(koodiarvo)
  }
  def eivalinnainen: Boolean = {
    KoskiUtil.eivalinnaiset.contains(koodiarvo)
  }
  def a2b2Kielet: Boolean = {
    KoskiUtil.a2b2Kielet.contains(koodiarvo)
  }
  def kielet: Boolean = {
    KoskiUtil.kielet.contains(koodiarvo)
  }
}

case class KoskiVahvistus(päivä: String, myöntäjäOrganisaatio: KoskiOrganisaatio)

case class KoskiKieli(koodiarvo: String, koodistoUri: String)

case class KoskiLisatiedot(
  erityisenTuenPäätös: Option[KoskiErityisenTuenPaatos], //legacy
  erityisenTuenPäätökset: Option[List[KoskiErityisenTuenPaatos]], //new format
  vuosiluokkiinSitoutumatonOpetus: Option[Boolean],
  kotiopetusjaksot: Option[List[Kotiopetusjakso]],
  kotiopetus: Option[Kotiopetusjakso] //Support for legacy Koski format
)

case class Kotiopetusjakso(alku: String, loppu: Option[String])

case class KoskiErityisenTuenPaatos(opiskeleeToimintaAlueittain: Option[Boolean])

case class KoskiSuoritusHakuParams(
  saveLukio: Boolean = false,
  saveAmmatillinen: Boolean = false,
  saveSeiskaKasiJaValmistava: Boolean = false,
  retryWaitMillis: Long = 10000
)
