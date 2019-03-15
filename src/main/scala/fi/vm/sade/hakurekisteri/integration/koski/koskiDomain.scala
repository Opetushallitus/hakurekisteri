package fi.vm.sade.hakurekisteri.integration.koski

import fi.vm.sade.hakurekisteri.Oids

import scala.math.BigDecimal

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
                         sukunimi: Option[String]) {
}
case class KoskiOpiskeluoikeus(
                                oid: Option[String], //LUVA data does not have an OID
                                oppilaitos: Option[KoskiOrganisaatio],
                                tila: KoskiOpiskeluoikeusjakso,
                                päättymispäivä: Option[String],
                                lisätiedot: Option[KoskiLisatiedot],
                                suoritukset: Seq[KoskiSuoritus],
                                tyyppi: Option[KoskiKoodi],
                                aikaleima: Option[String]) {

  def isStateContainingOpiskeluoikeus: Boolean =
    oppilaitos.isDefined && oppilaitos.get.oid.isDefined && tila.opiskeluoikeusjaksot.nonEmpty

  def isAikuistenPerusopetus: Boolean =
    tyyppi.getOrElse(KoskiKoodi("","")).koodiarvo.contentEquals("aikuistenperusopetus")
}

case class KoskiOpiskeluoikeusjakso(opiskeluoikeusjaksot: Seq[KoskiTila]) {
  def determineSuoritusTila: String = {
    opiskeluoikeusjaksot match {
      case o if o.exists(_.tila.koodiarvo == "valmistunut") => "VALMIS"
      case o if o.exists(_.tila.koodiarvo == "eronnut") => "KESKEYTYNYT"
      case o if o.exists(_.tila.koodiarvo == "erotettu") => "KESKEYTYNYT"
      case o if o.exists(_.tila.koodiarvo == "katsotaaneronneeksi") => "KESKEYTYNYT"
      case o if o.exists(_.tila.koodiarvo == "mitatoity") => "KESKEYTYNYT"
      case o if o.exists(_.tila.koodiarvo == "peruutettu") => "KESKEYTYNYT"
      // includes these "loma" | "valiaikaisestikeskeytynyt" | "lasna" => "KESKEN"
      case _ => "KESKEN"
    }
  }
}

case class KoskiTila(alku: String, tila:KoskiKoodi)

case class KoskiOrganisaatio(oid: Option[String])

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
                          tila: Option[KoskiKoodi] = None) {

  def opintopisteitaVahintaan(min: BigDecimal): Boolean = {
    val sum = osasuoritukset
      .filter(_.arviointi.exists(_.hyväksytty.contains(true)))
      .flatMap(_.koulutusmoduuli.laajuus)
      .map(_.arvo.getOrElse(BigDecimal(0)))
      .sum
    sum >= min
  }

  def getKomoOid(isAikuistenPerusOpetus: Boolean): String = {
    tyyppi match {
      case Some(k) =>
        if(isAikuistenPerusOpetus && k.koodiarvo == "perusopetuksenoppiaineenoppimaara") {
          Oids.perusopetuksenOppiaineenOppimaaraOid
        } else {
          k.koodiarvo match {
            case "perusopetuksenoppimaara" | "perusopetuksenoppiaineenoppimaara" | "aikuistenperusopetuksenoppimaara" => Oids.perusopetusKomoOid
            case "perusopetuksenvuosiluokka" => Oids.perusopetusLuokkaKomoOid
            case "valma" => Oids.valmaKomoOid
            case "telma" => Oids.telmaKomoOid
            case "luva" => Oids.lukioonvalmistavaKomoOid
            case "perusopetuksenlisaopetus" => Oids.lisaopetusKomoOid
            case "ammatillinentutkinto" =>
              koulutusmoduuli.koulutustyyppi match {
                case Some(KoskiKoodi("12", _)) => Oids.erikoisammattitutkintoKomoOid
                case Some(KoskiKoodi("11", _)) => Oids.ammatillinentutkintoKomoOid
                case _ => Oids.ammatillinenKomoOid
              }
            case "lukionoppimaara" => Oids.lukioKomoOid
            case _ => Oids.DUMMYOID
          }
        }
      case _ => Oids.DUMMYOID
    }
  }

  def getLuokkataso(isAikuistenPerusOpetus: Boolean): Option[String] = {
    tyyppi match {
      case Some(k) =>
        if((isAikuistenPerusOpetus && k.koodiarvo == "perusopetuksenoppiaineenoppimaara")
          || k.koodiarvo == "aikuistenperusopetuksenoppimaara") {
          Some(KoskiUtil.AIKUISTENPERUS_LUOKKAASTE)
        } else {
          k.koodiarvo match {
            case "perusopetuksenoppimaara" | "perusopetuksenvuosiluokka" => koulutusmoduuli.tunniste.flatMap(k => Some(k.koodiarvo))
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
    val laajuus: Option[KoskiValmaLaajuus] = koulutusmoduuli.laajuus.filter(_.yksikkö.koodiarvo == "2")
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

case class KoskiArviointi(arvosana: KoskiKoodi, hyväksytty: Option[Boolean], päivä: Option[String]) {
  def isPKValue: Boolean = {
    KoskiUtil.peruskoulunArvosanat.contains(arvosana.koodiarvo) || arvosana.koodiarvo == "H"
  }
}

case class KoskiKoulutusmoduuli(tunniste: Option[KoskiKoodi],
                                kieli: Option[KoskiKieli],
                                koulutustyyppi:
                                Option[KoskiKoodi],
                                laajuus: Option[KoskiValmaLaajuus],
                                pakollinen: Option[Boolean])

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
                            erityisenTuenPäätös: Option[KoskiErityisenTuenPaatos],
                            vuosiluokkiinSitoutumatonOpetus: Option[Boolean])

case class KoskiErityisenTuenPaatos(opiskeleeToimintaAlueittain: Option[Boolean])

case class KoskiSuoritusHakuParams(saveLukio: Boolean = false, saveAmmatillinen: Boolean = false)


