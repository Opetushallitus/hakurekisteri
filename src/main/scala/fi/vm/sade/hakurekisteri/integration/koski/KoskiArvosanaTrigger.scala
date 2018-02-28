package fi.vm.sade.hakurekisteri.integration.koski

import java.util.{Calendar, UUID}

import akka.actor.ActorRef
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri._
import fi.vm.sade.hakurekisteri.arvosana.{Arvio, Arvio410, Arvosana, ArvosanaQuery}
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.storage.{Identified, InsertResource, LogMessage}
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.Yksilollistetty
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, LocalDate}
import org.json4s.DefaultFormats

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object KoskiArvosanaTrigger {

  import scala.language.implicitConversions

  implicit val formats = DefaultFormats

  val DUMMYOID = "999999" //Dummy oid value for to-be-ignored komos
  val root_org_id = "1.2.246.562.10.00000000001"
  val kielet = Seq("A1", "A12", "A2", "A22", "B1", "B2", "B22", "B23", "B3", "B32", "B33")
  val oppiaineet = Seq("HI", "MU", "BI", "PS", "KT", "KO", "FI", "KE", "YH", "TE", "KS", "FY", "GE", "LI", "KU", "MA", "YL", "OP")
  val eivalinnaiset = kielet ++ oppiaineet ++ Seq("AI")
  val peruskoulunaineet = kielet ++ oppiaineet ++ Seq("AI")

  val peruskoulunArvosanat = Set[String]("4", "5", "6", "7", "8", "9", "10", "S")
  // koski to sure mapping oppiaineaidinkielijakirjallisuus -> aidinkielijakirjallisuus
  val aidinkieli = Map("AI1" -> "FI", "AI2" -> "SV", "AI3" -> "SE", "AI4" -> "RI", "AI5" -> "VK", "AI6" -> "XX", "AI7" -> "FI_2", "AI8" -> "SE_2", "AI9" -> "FI_SE", "AI10" -> "XX", "AI11" -> "FI_VK", "AI12" -> "SV_VK", "AIAI" -> "XX")

  def muodostaKoskiSuorituksetJaArvosanat(henkilo: KoskiHenkiloContainer,
                                          suoritusRekisteri: ActorRef,
                                          arvosanaRekisteri: ActorRef,
                                          opiskelijaRekisteri: ActorRef,
                                          personOidsWithAliases: PersonOidsWithAliases,
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

    def saveOpiskelija(opiskelija: Opiskelija) = {
      opiskelijaRekisteri ! opiskelija
    }

    def suoritusExists(suor: VirallinenSuoritus, suoritukset: Seq[Suoritus]): Boolean = suoritukset.exists {
      case s: VirallinenSuoritus => s.core == suor.core
      case _ => false
    }

    def toArvosana(arvosana: Arvosana)(suoritus: UUID)(source: String): Arvosana = Arvosana(suoritus, arvosana.arvio, arvosana.aine, arvosana.lisatieto, arvosana.valinnainen, None, source, Map(), arvosana.jarjestys)

    henkilo.henkilö.oid.foreach(henkiloOid => {
      val allSuoritukset: Seq[(Suoritus, Seq[Arvosana], String, LocalDate)] = createSuorituksetJaArvosanatFromKoski(henkilo)
      fetchExistingSuoritukset(henkiloOid).foreach(suoritukset => {
        var henkilonSuoritukset = allSuoritukset.filter(_._1.asInstanceOf[VirallinenSuoritus].henkilo.equals(henkiloOid))
        henkilonSuoritukset.foreach {
          case (suor: VirallinenSuoritus, arvosanat: Seq[Arvosana], luokka: String, lasnaDate: LocalDate) =>

            var useArvosanat = arvosanat
            val useSuoritus = suor
            if(suor.komo.equals(Oids.perusopetusKomoOid) && arvosanat.isEmpty){
              useArvosanat = henkilonSuoritukset
                .filter(_._1.asInstanceOf[VirallinenSuoritus].henkilo.equals(suor.henkilo))
                .filter(_._1.asInstanceOf[VirallinenSuoritus].myontaja.equals(suor.myontaja))
                .filter(_._1.asInstanceOf[VirallinenSuoritus].komo.equals("luokka"))
                .filter(_._3.startsWith("9"))
                .map(_._2).flatten
            }
            var useLuokka = ""
            if (henkilonSuoritukset.exists(_._3.startsWith("9")) && useSuoritus.komo.equals(Oids.perusopetusKomoOid)) {
              useLuokka = henkilonSuoritukset.filter(_._3.startsWith("9")).head._3
            } else {
              useLuokka = luokka
            }
            val peruskoulututkintoJaYsisuoritus = useSuoritus.komo.equals(Oids.perusopetusKomoOid) && henkilonSuoritukset.exists(_._3.startsWith("9"))

            if (!useSuoritus.komo.equals("luokka") && (peruskoulututkintoJaYsisuoritus || !useSuoritus.komo.equals(Oids.perusopetusKomoOid))) {
              val opiskelija = createOpiskelija(henkiloOid, Seq(SuoritusLuokka(useSuoritus, useLuokka, lasnaDate))) //LUOKKATIETO
              if (!suoritusExists(useSuoritus, suoritukset)) {
                for (
                  suoritus: Suoritus with Identified[UUID] <- saveSuoritus(useSuoritus)
                ) useArvosanat.foreach(arvosana =>
                  arvosanaRekisteri ! InsertResource[UUID, Arvosana](arvosanaForSuoritus(arvosana, suoritus), personOidsWithAliases)
                )
                saveOpiskelija(opiskelija)
              } else {
                for (
                  suoritus: VirallinenSuoritus with Identified[UUID] <- fetchSuoritus(henkiloOid, useSuoritus.myontaja, useSuoritus.komo)
                ) {
                  var ss = updateSuoritus(suoritus, useSuoritus)
                  useArvosanat.foreach(a => {
                    arvosanaRekisteri ? toArvosana(a)(suoritus.id)("koski")
                  })
                }
                saveOpiskelija(opiskelija)
              }
            }
          case (_, _, _, _) =>
          // VapaamuotoinenSuoritus will not be saved
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

  def maxDate(s1: LocalDate, s2: LocalDate): LocalDate = if (s1.isAfter(s2)) s1 else s2
  def minDate(s1: LocalDate, s2: LocalDate): LocalDate = if (s1.isAfter(s2)) s2 else s1

  def createOpiskelija(henkiloOid: String, suoritukset: Seq[SuoritusLuokka]): Opiskelija = {
    var def_alku = suoritukset.map(_.lasnaDate).reduceLeft(minDate).toDateTimeAtStartOfDay
    var loppu = suoritukset.map(_.suoritus.valmistuminen).reduceLeft(maxDate).toDateTimeAtStartOfDay

    var (luokkataso, oppilaitosOid, luokka, alku) = detectOppilaitos(suoritukset, def_alku)

    if (!loppu.isAfter(alku)) {
      loppu = parseNextFourthOfJune().toDateTimeAtStartOfDay
    }

    Opiskelija(
      oppilaitosOid = oppilaitosOid,
      luokkataso = luokkataso,
      luokka = luokka,
      henkiloOid = henkiloOid,
      alkuPaiva = alku,
      loppuPaiva = Some(loppu),
      source = "koski"
    )
  }

  def getOppilaitosAndLuokka(luokkataso: String, luokkaSuoritukset: Seq[SuoritusLuokka], komoOid: String, alku: DateTime): (String, String, String, DateTime) = {
    val sluokka = luokkaSuoritukset.find(_.suoritus.komo == komoOid).get
    komoOid match {
      // hae luokka 9C tai vast
      case Oids.perusopetusKomoOid => {
        (luokkataso, sluokka.suoritus.myontaja, sluokka.luokka, alku)
      }
      case Oids.lisaopetusKomoOid => {
        var luokka = luokkaSuoritukset.filter(_.suoritus.komo.equals(Oids.lisaopetusKomoOid)).head.luokka
        if(luokkaSuoritukset.filter(_.suoritus.komo.equals(Oids.lisaopetusKomoOid)).head.luokka.isEmpty){
          luokka = "10"
        }
        (luokkataso, sluokka.suoritus.myontaja, luokka, alku)
      }
      case _ => (luokkataso, sluokka.suoritus.myontaja, sluokka.luokka, alku)
    }
  }

  //noinspection ScalaStyle
  def detectOppilaitos(suoritukset: Seq[SuoritusLuokka], alku: DateTime): (String, String, String, DateTime) = suoritukset match {
    case s if (s.exists(_.suoritus.komo == Oids.lukioKomoOid)) => getOppilaitosAndLuokka("L", s, Oids.lukioKomoOid, alku)
    case s if (s.exists(_.suoritus.komo == Oids.lukioonvalmistavaKomoOid)) => getOppilaitosAndLuokka("ML", s, Oids.lukioonvalmistavaKomoOid, alku)
    case s if (s.exists(_.suoritus.komo == Oids.ammatillinenKomoOid)) => getOppilaitosAndLuokka("AK", s, Oids.ammatillinenKomoOid, alku)
    case s if (s.exists(_.suoritus.komo == Oids.ammatilliseenvalmistavaKomoOid)) => getOppilaitosAndLuokka("M", s, Oids.ammatilliseenvalmistavaKomoOid, alku)
    case s if (s.exists(_.suoritus.komo == Oids.ammattistarttiKomoOid)) => getOppilaitosAndLuokka("A", s, Oids.ammattistarttiKomoOid, alku)
    case s if (s.exists(_.suoritus.komo == Oids.valmentavaKomoOid)) => getOppilaitosAndLuokka("V", s, Oids.valmentavaKomoOid, alku)
    case s if (s.exists(_.suoritus.komo == Oids.valmaKomoOid)) => getOppilaitosAndLuokka("VALMA", s, Oids.valmaKomoOid, alku)
    case s if (s.exists(_.suoritus.komo == Oids.telmaKomoOid)) => getOppilaitosAndLuokka("TELMA", s, Oids.telmaKomoOid, alku)
    case s if (s.exists(_.suoritus.komo == Oids.lisaopetusKomoOid)) => getOppilaitosAndLuokka("10", s, Oids.lisaopetusKomoOid, alku)
    case s if (s.exists(_.suoritus.komo == Oids.perusopetusKomoOid)) => getOppilaitosAndLuokka("9", s, Oids.perusopetusKomoOid, alku)
    case _ => ("", "", "", alku)
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
      createSuoritusArvosanat(personOid, opiskeluoikeus.suoritukset, opiskeluoikeus.tila.opiskeluoikeusjaksot, opiskeluoikeus)
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
      case "aikuistenperusopetuksenoppimaara" => Oids.perusopetusKomoOid
      case "aikuistenperusopetuksenoppimaaranalkuvaihe" => DUMMYOID //aikuisten perusopetuksen alkuvaihe ei kiinnostava suren kannalta
      case "perusopetuksenvuosiluokka" => "luokka"
      case "valma" => Oids.valmaKomoOid
      case "telma" => Oids.telmaKomoOid
      case "luva" => Oids.lukioonvalmistavaKomoOid
      case "perusopetuksenlisaopetus" => Oids.lisaopetusKomoOid
      case "ammatillinentutkinto" => Oids.ammatillinenKomoOid
      case _ => DUMMYOID
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

  def osasuoritusToArvosana(personOid: String, orgOid: String, osasuoritukset: Seq[KoskiOsasuoritus], lisatiedot: Option[KoskiLisatiedot]): (Seq[Arvosana], Yksilollistetty) = {
    var yksilöllistetyt = ListBuffer[Boolean]()
    var res:Seq[Arvosana] = Seq()
    for {
      suoritus <- osasuoritukset
      if isPK(suoritus)
    } yield {
      yksilöllistetyt += suoritus.yksilöllistettyOppimäärä.getOrElse(false)
      suoritus.arviointi.foreach(arviointi => {
        if (isPKValue(arviointi.arvosana.koodiarvo)) {
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
          res = res :+ createArvosana(personOid, Arvio410(arviointi.arvosana.koodiarvo), tunniste.koodiarvo, lisatieto, valinnainen)
        }
      })
    }
    var yksilöllistetty = yksilollistaminen.Ei
    //Yli puolet osasuorituksista yksilöllistettyjä -> kokonaan yksilöllistetty. Osittain yksilöllistetty, jos yli 1 mutta alle tai tasan puolet yksilöllistettyjä.
    if (yksilöllistetyt.count(_.equals(true)) > yksilöllistetyt.count(_.equals(false))) {
      yksilöllistetty = yksilollistaminen.Kokonaan
    } else if (yksilöllistetyt.count(_.equals(true)) > 0) {
      yksilöllistetty = yksilollistaminen.Osittain
    }
    if (yksilöllistetty == yksilollistaminen.Ei) {
      for {
        lisatieto <- lisatiedot
        tuenPaatos <- lisatieto.erityisenTuenPäätös
      } yield {
        if (tuenPaatos.opiskeleeToimintaAlueittain.getOrElse(false)) {
          yksilöllistetty = yksilollistaminen.Alueittain
        }
      }
    }
    (res, yksilöllistetty)
  }

  def opintopisteidenMaaraFromOsasuoritus(osasuoritukset: Seq[KoskiOsasuoritus]): BigDecimal = {
    var opintopisteet = BigDecimal(0)
    osasuoritukset.foreach{suoritus => {
        suoritus.koulutusmoduuli.laajuus match {
          case Some(kvj) => {
            if(kvj.yksikkö.koodiarvo == "2"){ // opintopisteet
              opintopisteet = opintopisteet + kvj.arvo.getOrElse(BigDecimal(0))
            }
          }
          case None =>
        }
      }
    }
    opintopisteet
  }

  def getValmistuminen(vahvistus: Option[KoskiVahvistus], alkuPvm: String, oppilaitos: KoskiOrganisaatio): (Int, LocalDate, String) = {
    vahvistus match {
      case Some(k: KoskiVahvistus) => (parseYear(k.päivä), parseLocalDate(k.päivä), k.myöntäjäOrganisaatio.oid)
      case _ => (parseYear(alkuPvm), parseLocalDate(alkuPvm), oppilaitos.oid)
    }
  }

  def parseNextFourthOfJune(): LocalDate = {
    var cal = java.util.Calendar.getInstance()
    cal.set(cal.get(Calendar.YEAR), 5, 4)
    var now = LocalDate.now()
    var fourthOfJune = LocalDate.fromCalendarFields(cal)
    if(now.isAfter(fourthOfJune)){
      fourthOfJune.plusYears(1)
    }
    fourthOfJune
  }

  def createSuoritusArvosanat(personOid: String, suoritukset: Seq[KoskiSuoritus], tilat: Seq[KoskiTila], opiskeluoikeus: KoskiOpiskeluoikeus): Seq[(Suoritus, Seq[Arvosana], String, LocalDate)] = {
    var result = Seq[(Suoritus, Seq[Arvosana], String, LocalDate)]()
    for ( suoritus <- suoritukset ) {

        val (vuosi, valmistumisPaiva, organisaatioOid) = getValmistuminen(suoritus.vahvistus, tilat.last.alku, opiskeluoikeus.oppilaitos)

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

        val lasnaDate = (suoritus.alkamispäivä, tilat.find(_.tila.koodiarvo == "lasna"), lastTila) match {
          case (Some(a), _, _) => parseLocalDate(a)
          case (None, Some(kt), _) => parseLocalDate(kt.alku)
          case (_,_,_) => valmistumisPaiva
        }

        var komoOid = suoritus.tyyppi match {
          case Some(k) =>
            matchOpetusOid(k.koodiarvo)
          case _ => DUMMYOID
        }

        var (arvosanat: Seq[Arvosana], yksilöllistaminen: Yksilollistetty) = komoOid match {
          case Oids.perusopetusKomoOid => osasuoritusToArvosana(personOid, komoOid, suoritus.osasuoritukset, opiskeluoikeus.lisätiedot)
          case "luokka" => osasuoritusToArvosana(personOid, komoOid, suoritus.osasuoritukset, opiskeluoikeus.lisätiedot)
          case Oids.valmaKomoOid => (Seq(), yksilollistaminen.Ei)
          case Oids.telmaKomoOid => (Seq(), yksilollistaminen.Ei)
          case Oids.lukioonvalmistavaKomoOid => osasuoritusToArvosana(personOid, komoOid, suoritus.osasuoritukset, opiskeluoikeus.lisätiedot)
          case Oids.lisaopetusKomoOid => osasuoritusToArvosana(personOid, komoOid, suoritus.osasuoritukset, opiskeluoikeus.lisätiedot)
          case _ => (Seq(), yksilollistaminen.Ei)
        }

        if(komoOid == Oids.valmaKomoOid && lastTila == "VALMIS" && opintopisteidenMaaraFromOsasuoritus(suoritus.osasuoritukset) < 30){
          lastTila = "KESKEN"
        }

        var luokka = komoOid match {
          case Oids.valmaKomoOid => suoritus.ryhmä.getOrElse("VALMA")
          case Oids.telmaKomoOid => suoritus.ryhmä.getOrElse("TELMA")
          case _ => suoritus.luokka.getOrElse("")
        }
        if (luokka == "" && suoritus.tyyppi.isDefined && suoritus.tyyppi.get.koodiarvo == "aikuistenperusopetuksenoppimaara") {
          luokka = "9"
        }

        val useValmistumisPaiva = (komoOid, luokka.startsWith("9"), lastTila) match {
          case (Oids.perusopetusKomoOid, _, "KESKEN") => parseNextFourthOfJune()
          case (Oids.lisaopetusKomoOid, _, "KESKEN") => parseNextFourthOfJune()
          case (Oids.valmaKomoOid, _, "KESKEN") => parseNextFourthOfJune()
          case (Oids.telmaKomoOid, _, "KESKEN") => parseNextFourthOfJune()
          case ("luokka", true, "KESKEN") => parseNextFourthOfJune()
          case (_,_,_) => valmistumisPaiva
        }

        if (komoOid != DUMMYOID && vuosi > 1970) {
          result = result :+ (VirallinenSuoritus(
            komoOid,
            organisaatioOid,
            lastTila,
            useValmistumisPaiva,
            personOid,
            yksilöllistaminen,
            suorituskieli.koodiarvo,
            None,
            true,
            root_org_id), arvosanat, luokka, lasnaDate)
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