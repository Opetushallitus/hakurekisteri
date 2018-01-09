package fi.vm.sade.hakurekisteri.integration.koski

import java.util.UUID

import akka.actor.ActorRef
import akka.actor.Status.{Failure, Success}
import akka.event.Logging
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri._
import fi.vm.sade.hakurekisteri.arvosana.{Arvio, Arvio410, Arvosana, ArvosanaQuery}
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
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
                                          opiskelijaRekisteri: ActorRef, personOidsWithAliases: PersonOidsWithAliases,
                                          logBypassed: Boolean = false)
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

    def updateSuoritus(suoritus: VirallinenSuoritus with Identified[UUID], suor: VirallinenSuoritus): Future[VirallinenSuoritus with Identified[UUID]] =
    (suoritusRekisteri ? suoritus.copy(tila = suor.tila, valmistuminen = suor.valmistuminen, yksilollistaminen = suor.yksilollistaminen, suoritusKieli = suor.suoritusKieli)).mapTo[VirallinenSuoritus with Identified[UUID]].recoverWith{
      case t: AskTimeoutException => updateSuoritus(suoritus, suor)
    }

    def updateArvosana(arvosana: Arvosana with Identified[UUID], arv: Arvosana): Future[Arvosana with Identified[UUID]] =
      (suoritusRekisteri ? arvosana.copy(arvio = arv.arvio)).mapTo[Arvosana with Identified[UUID]]

    def fetchSuoritus(henkiloOid: String, oppilaitosOid: String, komo: String): Future[VirallinenSuoritus with Identified[UUID]] =
      (suoritusRekisteri ? SuoritusQuery(henkilo = Some(henkiloOid), myontaja = Some(oppilaitosOid), komo = Some(komo))).mapTo[Seq[VirallinenSuoritus with Identified[UUID]]].
      flatMap(suoritukset => suoritukset.headOption match {
        case Some(suoritus) if suoritukset.length == 1 => Future.successful(suoritus)
        case Some(_) if suoritukset.length > 1 => Future.failed(new MultipleSuoritusException(henkiloOid, oppilaitosOid, komo))
      })

    def fetchArvosanat(s: VirallinenSuoritus with Identified[UUID]): Future[Seq[Arvosana with Identified[UUID]]] = {
      (suoritusRekisteri ? ArvosanaQuery(suoritus = s.id)).mapTo[Seq[Arvosana with Identified[UUID]]]
    }

    def fetchArvosana(arvosanat: Seq[Arvosana with Identified[UUID]], aine: String): Arvosana with Identified[UUID] = {
      arvosanat.filter(a => a.aine == aine).head
    }

    def maxDate(s1: LocalDate, s2: LocalDate): LocalDate = if (s1.isAfter(s2)) s1 else s2
    def minDate(s1: LocalDate, s2: LocalDate): LocalDate = if (s1.isAfter(s2)) s2 else s1

    def createOpiskelija(henkiloOid: String, suoritukset: Seq[SuoritusLuokka]): Opiskelija = {
      var (luokkataso, oppilaitosOid, luokka) = detectOppilaitos(suoritukset)
      var alku = suoritukset.map(_.lasnaDate).reduceLeft(minDate).toDateTimeAtStartOfDay
      var loppu = suoritukset.map(_.suoritus.valmistuminen).reduceLeft(maxDate).toDateTimeAtStartOfDay

      var op = Opiskelija(
        oppilaitosOid = oppilaitosOid,
        luokkataso = luokkataso,
        luokka = luokka,
        henkiloOid = henkiloOid,
        alkuPaiva = alku,
        loppuPaiva = Some(loppu),
        source = "koski"
      )
      op
    }

    def saveOpiskelija(henkiloOid: String, suoritukset: Seq[SuoritusLuokka]) = {
      opiskelijaRekisteri ! createOpiskelija(henkiloOid, suoritukset)
    }

    def getOppilaitosAndLuokka(luokkataso: String, suoritusLuokka: Seq[SuoritusLuokka], oid: String): (String, String, String) = {
      var sluokka = suoritusLuokka.filter(_.suoritus.komo == oid).head
      oid match {
        // hae luokka 9C tai vast
        case Oids.perusopetusKomoOid => (luokkataso, sluokka.suoritus.myontaja, suoritusLuokka.filter(_.luokka.startsWith("9")).head.luokka)
        case _ => (luokkataso, sluokka.suoritus.myontaja, sluokka.luokka)
      }
    }

    //noinspection ScalaStyle
    def detectOppilaitos(suoritukset: Seq[SuoritusLuokka]): (String, String, String) = suoritukset match {
      case s if (s.exists(_.suoritus.komo == Oids.lukioKomoOid)) => getOppilaitosAndLuokka("L", s, Oids.lukioKomoOid)
      case s if (s.exists(_.suoritus.komo == Oids.lukioonvalmistavaKomoOid)) => getOppilaitosAndLuokka("ML", s, Oids.lukioonvalmistavaKomoOid)
      case s if (s.exists(_.suoritus.komo == Oids.ammatillinenKomoOid)) => getOppilaitosAndLuokka("AK", s, Oids.ammatillinenKomoOid)
      case s if (s.exists(_.suoritus.komo == Oids.ammatilliseenvalmistavaKomoOid)) => getOppilaitosAndLuokka("M", s, Oids.ammatilliseenvalmistavaKomoOid)
      case s if (s.exists(_.suoritus.komo == Oids.ammattistarttiKomoOid)) => getOppilaitosAndLuokka("A", s, Oids.ammattistarttiKomoOid)
      case s if (s.exists(_.suoritus.komo == Oids.valmentavaKomoOid)) => getOppilaitosAndLuokka("V", s, Oids.valmentavaKomoOid)
      case s if (s.exists(_.suoritus.komo == Oids.valmaKomoOid)) => getOppilaitosAndLuokka("VALMA", s, Oids.valmaKomoOid)
      case s if (s.exists(_.suoritus.komo == Oids.telmaKomoOid)) => getOppilaitosAndLuokka("TELMA", s, Oids.telmaKomoOid)
      case s if (s.exists(_.suoritus.komo == Oids.lisaopetusKomoOid)) => getOppilaitosAndLuokka("10", s, Oids.lisaopetusKomoOid)
      case s if (s.exists(_.suoritus.komo == Oids.perusopetusKomoOid)) => getOppilaitosAndLuokka("9", s, Oids.perusopetusKomoOid)
      case _ => ("", "", "")
    }

    def suoritusExists(suor: VirallinenSuoritus, suoritukset: Seq[Suoritus]): Boolean = suoritukset.exists {
      case s: VirallinenSuoritus => s.core == suor.core
      case _ => false
    }

    def toArvosana(arvosana: Arvosana)(suoritus: UUID)(source: String): Arvosana = Arvosana(suoritus, arvosana.arvio, arvosana.aine, arvosana.lisatieto, arvosana.valinnainen, None, source, Map(), arvosana.jarjestys)

    henkilo.henkilö.oid.foreach(henkiloOid => {
      var vahvistetut = Seq[SuoritusLuokka]()
      fetchExistingSuoritukset(henkiloOid).foreach(suoritukset => {
        createSuorituksetJaArvosanatFromKoski(henkilo).foreach {
          case (suor: VirallinenSuoritus, arvosanat: Seq[Arvosana], luokka: String, lasnaDate: LocalDate) =>
            vahvistetut = vahvistetut :+ SuoritusLuokka(suor, luokka, lasnaDate)
            if (!suoritusExists(suor, suoritukset) && suor.komo != "luokka") {
              for (
                suoritus: Suoritus with Identified[UUID] <- saveSuoritus(suor)
              ) arvosanat.foreach(arvosana =>
                arvosanaRekisteri ! InsertResource[UUID, Arvosana](arvosanaForSuoritus(arvosana, suoritus), personOidsWithAliases)
              )
            } else if (suor.komo != "luokka") {
              for(
                suoritus: VirallinenSuoritus with Identified[UUID] <- fetchSuoritus(henkiloOid, suor.myontaja, suor.komo)
              ){
                var ss = updateSuoritus(suoritus, suor)
                arvosanat.foreach(a => {
                  (arvosanaRekisteri ? toArvosana(a)(suoritus.id)("koski"))
                })
              }
            }
          case (_, _, _, _) =>
          // VapaamuotoinenSuoritus will not be saved
        }
        if (vahvistetut.nonEmpty) {
          saveOpiskelija(henkiloOid, vahvistetut)
        }
      })
    })
  }

  def apply(suoritusRekisteri: ActorRef, arvosanaRekisteri: ActorRef, opiskelijaRekisteri: ActorRef)(implicit ec: ExecutionContext): KoskiTrigger = {
    KoskiTrigger {
      (koskiHenkilo: KoskiHenkiloContainer, personOidsWithAliases: PersonOidsWithAliases) => {
        muodostaKoskiSuorituksetJaArvosanat(koskiHenkilo, suoritusRekisteri, arvosanaRekisteri, opiskelijaRekisteri, personOidsWithAliases.intersect(koskiHenkilo.henkilö.oid.toSet))
      }
    }
  }

  def createArvosana(personOid: String, arvo: Arvio, aine: String, lisatieto: Option[String], valinnainen: Boolean, jarjestys: Option[Int] = None): Arvosana = {
    Arvosana(suoritus = null, arvio = arvo, aine, lisatieto, valinnainen, myonnetty = None, source = personOid, Map(), jarjestys = jarjestys)
  }

  def createSuorituksetJaArvosanatFromKoski(henkilo: KoskiHenkiloContainer): Seq[(Suoritus, Seq[Arvosana], String, LocalDate)] = {
    getSuoritusArvosanatFromOpiskeluoikeus(henkilo.henkilö.oid.getOrElse(""), henkilo.opiskeluoikeudet)
  }

  def parseLocalDate(s: String): LocalDate =
    if (s.length() > 10) {
      DateTimeFormat.forPattern("yyyy-MM-ddZ").parseLocalDate(s)
    } else {
      DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate(s)
    }

  def getSuoritusArvosanatFromOpiskeluoikeus(personOid: String, opiskeluoikeudet: Seq[KoskiOpiskeluoikeus]): Seq[(Suoritus, Seq[Arvosana], String, LocalDate)] = {
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
      case "perusopetuksenvuosiluokka" => "luokka"
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

  def osasuoritusToArvosana(personOid: String, orgOid: String, osasuoritukset: Seq[KoskiOsasuoritus]): Seq[Arvosana] = {
    var res = for {
      suoritus <- osasuoritukset;
      arviointi <- suoritus.arviointi
      if isPK(suoritus) && isPKValue(arviointi.arvosana.koodiarvo)
    } yield {
      val tunniste = suoritus.koulutusmoduuli.tunniste.getOrElse(KoskiKoodi("", ""))
      val lisatieto: Option[String] = (tunniste.koodiarvo, suoritus.koulutusmoduuli.kieli) match {
        case (a: String, b: Option[KoskiKieli]) if kielet.contains(a) => Option(b.get.koodiarvo)
        case (a: String, b: Option[KoskiKieli]) if a == "AI" => Option(aidinkieli(b.get.koodiarvo))
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

  def createSuoritusArvosanat(personOid: String, suoritukset: Seq[KoskiSuoritus], tilat: Seq[KoskiTila]): Seq[(Suoritus, Seq[Arvosana], String, LocalDate)] = {
    var result = Seq[(Suoritus, Seq[Arvosana], String, LocalDate)]()
    for ( suoritus <- suoritukset ) {
        val (vuosi, valmistumisPaiva, organisaatioOid) = getValmistuminen(suoritus.vahvistus, tilat.last.alku, suoritus.toimipiste)

        var suorituskieli = suoritus.suorituskieli.getOrElse(KoskiKieli("FI", "kieli"))

        var lastTila = tilat match {
          case t if(t.exists(_.tila.koodiarvo == "valmistunut")) => "VALMIS"
          case t if(t.exists(_.tila.koodiarvo == "eronnut")) => "KESKEYTYNYT"
          case t if(t.exists(_.tila.koodiarvo == "erotettu")) => "KESKEYTYNYT"
          case t if(t.exists(_.tila.koodiarvo == "katsotaaneronneeksi")) => "KESKEYTYNYT"
          case t if(t.exists(_.tila.koodiarvo == "mitatoity")) => "KESKEYTYNYT"
          case t if(t.exists(_.tila.koodiarvo == "peruutettu")) => "KESKEYTYNYT"
          // includes these "loma" | "valiaikaisestikeskeytynyt" | "lasna" => "KESKEN"
          case _ => "KESKEN"
        }

        var lasnaDate = tilat.filter(_.tila.koodiarvo == "lasna").headOption.getOrElse(None) match {
          case kt:KoskiTila => parseLocalDate(kt.alku)
          case _ => valmistumisPaiva
        }

        var oid = suoritus.tyyppi match {
          case Some(k) =>
            matchOpetusOid(k.koodiarvo)
          case _ => "999999"
        }

        var arvosanat: Seq[Arvosana] = oid match {
          case Oids.perusopetusKomoOid => osasuoritusToArvosana(personOid, oid, suoritus.osasuoritukset)
          case "luokka" => Seq()
          case Oids.valmaKomoOid => Seq()
          case Oids.telmaKomoOid => Seq()
          case Oids.lukioonvalmistavaKomoOid => osasuoritusToArvosana(personOid, oid, suoritus.osasuoritukset)
          case Oids.lisaopetusKomoOid => osasuoritusToArvosana(personOid, oid, suoritus.osasuoritukset)
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
            root_org_id), arvosanat, suoritus.luokka.getOrElse(""), lasnaDate)
        }
      }
      result
  }
}

case class SuoritusLuokka(suoritus: VirallinenSuoritus, luokka: String, lasnaDate: LocalDate)

case class MultipleSuoritusException(henkiloOid: String,
                                     myontaja: String,
                                     komo: String)
  extends Exception(s"Multiple suoritus found for henkilo $henkiloOid by myontaja $myontaja with komo $komo.")