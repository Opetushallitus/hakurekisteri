package fi.vm.sade.hakurekisteri.integration.koski

import java.util.UUID

import akka.actor.ActorRef
import akka.event.Logging
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri._
import fi.vm.sade.hakurekisteri.arvosana.{Arvio, Arvio410, Arvosana}
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.storage.{Identified, InsertResource, LogMessage}
import fi.vm.sade.hakurekisteri.suoritus._
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, LocalDate}
import org.json4s.DefaultFormats

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object KoskiArvosanaTrigger {

  import scala.language.implicitConversions
  implicit val formats = DefaultFormats

  val root_org_id = "1.2.246.562.10.00000000001"
  val kielet = Seq("A1", "A12", "A2", "A22", "B1", "B2", "B22", "B23", "B3", "B32", "B33")
  val oppiaineet = Seq("HI", "MU", "BI", "PS", "KT", "KO", "FI", "KE", "YH", "TE", "KS", "FY", "GE", "LI", "KU", "MA", "YL", "OP")
  val eivalinnaiset = kielet ++ oppiaineet ++ Seq("AI")
  val peruskoulunaineet = kielet ++ oppiaineet ++ Seq("AI")

  val peruskoulunArvosanat = Set[String]("4", "5", "6", "7", "8", "9", "10", "S")
  // koski to sure mapping oppiaineaidinkielijakirjallisuus -> aidinkielijakirjallisuus
  val aidinkieli = Map("AI1" -> "FI", "AI2" -> "SV", "AI3" -> "SE", "AI4" -> "RI", "AI5" -> "VK", "AI6" -> "XX", "AI7" -> "FI_2", "AI8" -> "SE_2", "AI9" -> "FI_SE", "AI10" -> "XX", "AI11" -> "FI_VK", "AI12" -> "SV_VK", "AIAI" -> "XX")

  def muodostaKoskiSuorituksetJaArvosanat(henkilo: KoskiHenkiloContainer, suoritusRekisteri: ActorRef, arvosanaRekisteri: ActorRef,
                                     personOidsWithAliases: PersonOidsWithAliases, logBypassed: Boolean = false)
                                    (implicit ec: ExecutionContext): Unit = {
    implicit val timeout: Timeout = 2.minutes
    def saveSuoritus(suor: Suoritus): Future[Suoritus with Identified[UUID]] =
      (suoritusRekisteri ? InsertResource[UUID, Suoritus](suor, personOidsWithAliases)).mapTo[Suoritus with Identified[UUID]].recoverWith {
        case t: AskTimeoutException => saveSuoritus(suor)
      }
    def fetchExistingSuoritukset(henkiloOid: String): Future[Seq[Suoritus]] = {
      val q = SuoritusQuery(henkilo = Some(henkiloOid))
      (suoritusRekisteri ? SuoritusQueryWithPersonAliases(q, personOidsWithAliases)).mapTo[Seq[Suoritus]].recoverWith {
        case t: AskTimeoutException => fetchExistingSuoritukset(henkiloOid)
      }
    }
    def suoritusExists(suor: VirallinenSuoritus, suoritukset: Seq[Suoritus]): Boolean = suoritukset.exists {
      case s: VirallinenSuoritus => s.core == suor.core
      case _ => false
    }
    henkilo.henkilö.oid.foreach(henkiloOid => {
      fetchExistingSuoritukset(henkiloOid).foreach(suoritukset => {
        createSuorituksetJaArvosanatFromKoski(henkilo).foreach {
          case (suor: VirallinenSuoritus, arvosanat:Seq[Arvosana]) =>
            if (!suoritusExists(suor, suoritukset)) {
              for (
                suoritus: Suoritus with Identified[UUID] <- saveSuoritus(suor)
              ) arvosanat.foreach(arvosana =>
                arvosanaRekisteri ! InsertResource[UUID, Arvosana](arvosanaForSuoritus(arvosana, suoritus), personOidsWithAliases)
              )
            } else if (logBypassed) {
              suoritusRekisteri ! LogMessage(s"suoritus already exists: $suor", Logging.DebugLevel)
            }
          case (_, _) =>
          // VapaamuotoinenSuoritus will not be saved
        }
      })
    })
  }

  def apply(suoritusRekisteri: ActorRef, arvosanaRekisteri: ActorRef)(implicit ec: ExecutionContext): KoskiTrigger = {
    KoskiTrigger {
      (koskiHenkilo: KoskiHenkiloContainer, personOidsWithAliases: PersonOidsWithAliases) => {
        muodostaKoskiSuorituksetJaArvosanat(koskiHenkilo, suoritusRekisteri, arvosanaRekisteri, personOidsWithAliases.intersect(koskiHenkilo.henkilö.oid.toSet))
      }
    }
  }

  def createArvosana(personOid: String, arvo: Arvio, aine: String, lisatieto: Option[String], valinnainen: Boolean, jarjestys: Option[Int] = None): Arvosana = {
    Arvosana(suoritus = null, arvio = arvo, aine, lisatieto, valinnainen, myonnetty = None, source = personOid, Map(), jarjestys = jarjestys)
  }

  def createSuorituksetJaArvosanatFromKoski(henkilo: KoskiHenkiloContainer): Seq[(Suoritus, Seq[Arvosana])] = {
    getSuoritusArvosanatFromOpiskeluoikeus(henkilo.henkilö.oid.getOrElse(""), henkilo.opiskeluoikeudet)
  }

  import fi.vm.sade.hakurekisteri.tools.RicherString._
  def parseLocalDate(s: String): LocalDate =
    if (s.length() > 10) {
      DateTimeFormat.forPattern("yyyy-MM-ddZ").parseLocalDate(s)
    } else {
      DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate(s)
    }

  def getSuoritusArvosanatFromOpiskeluoikeus(personOid: String, opiskeluoikeudet: Seq[KoskiOpiskeluoikeus]): Seq[(Suoritus, Seq[Arvosana])] = {
    (for (
      opiskeluoikeus <- opiskeluoikeudet
    ) yield {
      createSuoritusArvosanat(personOid, opiskeluoikeus.suoritukset, opiskeluoikeus.tila.opiskeluoikeusjaksot)
    }).flatten
  }

  def parseYear(dateStr: String): Int = {
    val dateFormat = "yyyy-MM-dd"
    val dtf = java.time.format.DateTimeFormatter.ofPattern(dateFormat)
    val d = java.time.LocalDate.parse(dateStr, dtf)
    d.getYear
  }

  def matchOpetusOid(koulutusmoduuliTunnisteKoodiarvo: String): String = {
    koulutusmoduuliTunnisteKoodiarvo match {
      case "perusopetuksenoppimaara" => Oids.perusopetusKomoOid
      case "perusopetukseenvalmistavaopetus" => Oids.valmaKomoOid
      case "telma" => Oids.telmaKomoOid
      case "luva" => Oids.lukioonvalmistavaKomoOid
      case "perusopetuksenlisaopetus" => Oids.lisaopetusKomoOid
      case _ => koulutusmoduuliTunnisteKoodiarvo
    }
  }

  def arvosanaForSuoritus(arvosana: Arvosana, s: Suoritus with Identified[UUID]): Arvosana = {
    arvosana.copy(suoritus = s.id)
  }

  def isPK(osasuoritus: KoskiOsasuoritus): Boolean = {
    peruskoulunaineet.contains(osasuoritus.koulutusmoduuli.tunniste.getOrElse(KoskiKoodi("", "")).koodiarvo)
  }
  def isPKValue(arvosana: String): Boolean = {
    peruskoulunArvosanat.contains(arvosana)
  }

  def pkOsasuoritusToArvosana(personOid: String, orgOid: String, osasuoritukset: Seq[KoskiOsasuoritus]): Seq[Arvosana] = {
    var res = for {
      suoritus <- osasuoritukset;
      arviointi <- suoritus.arviointi
      if isPK(suoritus) && isPKValue(arviointi.arvosana.koodiarvo)
    } yield {
      val tunniste = suoritus.koulutusmoduuli.tunniste.getOrElse(KoskiKoodi("", ""))
      val lisatieto:Option[String] = (tunniste.koodiarvo, suoritus.koulutusmoduuli.kieli) match {
        case (a:String, b:Option[KoskiKieli]) if kielet.contains(a) => Option(b.get.koodiarvo)
        case (a:String, b:Option[KoskiKieli]) if a == "AI" => Option(aidinkieli(b.get.koodiarvo))
        case _ => None
      }
      val valinnainen = (tunniste.koodiarvo) match {
        case (a) if eivalinnaiset.contains(a) => false
        case _ => true
      }
      createArvosana(personOid, Arvio410(arviointi.arvosana.koodiarvo), tunniste.koodiarvo, lisatieto, valinnainen)
    }
    res
  }

  def lisapisteOsasuoritusToArvosana(personOid: String, orgOid: String, osasuoritukset: Seq[KoskiOsasuoritus]): Seq[Arvosana] = {
    var res = for (
      suoritus <- osasuoritukset;
      arviointi <- suoritus.arviointi
    ) yield {
      val tunniste = suoritus.koulutusmoduuli.tunniste.getOrElse(KoskiKoodi("", ""))
      val lisatieto:Option[String] = (tunniste.koodiarvo, suoritus.koulutusmoduuli.kieli) match {
        case (a:String, b:Option[KoskiKieli]) if kielet.contains(a) => Option(b.get.koodiarvo)
        case (a:String, b:Option[KoskiKieli]) if a == "AI" => Option(aidinkieli(b.get.koodiarvo))
        case _ => None
      }
      val valinnainen = (tunniste.koodiarvo) match {
        case (a) if eivalinnaiset.contains(a) => false
        case _ => true
      }
      createArvosana(personOid, Arvio410(arviointi.arvosana.koodiarvo), tunniste.koodiarvo, lisatieto, valinnainen)
    }
    res
  }

  def getValmistuminen(vahvistus: Option[KoskiVahvistus], alkuPvm: String, toimipiste: Option[KoskiOrganisaatio]): (Int, LocalDate, String) = {
    vahvistus match {
      case Some(k: KoskiVahvistus) => (parseYear(k.päivä), parseLocalDate(k.päivä), k.myöntäjäOrganisaatio.oid)
      case _ => (parseYear(alkuPvm), parseLocalDate(alkuPvm), toimipiste.getOrElse(KoskiOrganisaatio(root_org_id)).oid)
    }
  }

  def createSuoritusArvosanat(personOid: String, suoritukset: Seq[KoskiSuoritus], tilat: Seq[KoskiTila]): Seq[(Suoritus, Seq[Arvosana])] = {
    var result = Seq[(Suoritus, Seq[Arvosana])]()
    for ( suoritus <- suoritukset ) {
        val (vuosi, valmistumisPaiva, organisaatioOid) = getValmistuminen(suoritus.vahvistus, tilat.last.alku, suoritus.toimipiste)

        var suorituskieli = suoritus.suorituskieli.getOrElse(KoskiKieli("FI", "kieli"))

        var lastTila = tilat.last.tila.koodiarvo match {
          case "valmistunut" => "VALMIS"
          case "eronnut" | "erotettu" | "katsotaaneronneeksi" | "mitatoity" | "peruutettu" => "KESKEYTYNYT"
          case "loma" | "valiaikaisestikeskeytynyt" | "lasna" => "KESKEN"
          case _ => "KESKEN"
        }

        var oid = suoritus.tyyppi match {
          case Some(k) =>
            matchOpetusOid(k.koodiarvo)
          case _ => "999999"
        }

        var arvosanat: Seq[Arvosana] = oid match {
          case Oids.perusopetusKomoOid => pkOsasuoritusToArvosana(personOid, oid, suoritus.osasuoritukset)
          case Oids.valmaKomoOid => Seq()
          case Oids.telmaKomoOid => Seq()
          case Oids.lisaopetusKomoOid => lisapisteOsasuoritusToArvosana(personOid, oid, suoritus.osasuoritukset)
          case _ => Seq()
        }
        if (oid != "999999" && vuosi > 1970) {
          result = result :+ (VirallinenSuoritus(
            oid,
            organisaatioOid,
            lastTila,
            valmistumisPaiva,
            personOid,
            suoritus.yksilöllistettyOppimäärä match {
              case Some(true) => yksilollistaminen.Alueittain
              case _ => yksilollistaminen.Ei
            },
            suorituskieli.koodiarvo,
            None,
            true,
            root_org_id), arvosanat)
        }
      }
      result
  }
}