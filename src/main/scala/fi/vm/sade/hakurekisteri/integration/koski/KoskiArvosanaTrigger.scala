package fi.vm.sade.hakurekisteri.integration.koski

import java.util.{Calendar, UUID}

import akka.actor.ActorRef
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri._
import fi.vm.sade.hakurekisteri.arvosana.{Arvio, Arvio410, Arvosana, ArvosanaQuery}
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.opiskelija.{IdentifiedOpiskelija, Opiskelija, OpiskelijaQuery}
import fi.vm.sade.hakurekisteri.storage.{DeleteResource, Identified, InsertResource, LogMessage}
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.Yksilollistetty
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, LocalDate}
import org.json4s.DefaultFormats
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object KoskiArvosanaTrigger {

  private val logger = LoggerFactory.getLogger(getClass)

  import scala.language.implicitConversions

  implicit val formats = DefaultFormats

  val AIKUISTENPERUS_LUOKKAASTE = "AIK"
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
                                          logBypassed: Boolean = false,
                                          removeFalseYsit: Boolean = false)
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

    def fetchExistingLuokkatiedot(henkiloOid: String): Future[Seq[Opiskelija]] = {
      val q = OpiskelijaQuery(henkilo = Some(henkiloOid))
      (opiskelijaRekisteri ? q).mapTo[Seq[Opiskelija]].recoverWith {
        case t: AskTimeoutException => fetchExistingLuokkatiedot(henkiloOid)
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

    def findMatchingLuokkatietoAndSuoritus(sureSuoritukset: Seq[Suoritus], sureLuokkatiedot: Seq[Opiskelija]): (Option[Suoritus], Option[Opiskelija]) = {
      //logger.info(s"Etsitään suoritus-luokkatieto-pari")
      val tieto = sureLuokkatiedot.find(lt => sureSuoritukset.exists(_.asInstanceOf[VirallinenSuoritus].myontaja.equals(lt.oppilaitosOid)))
      var suoritus = Option.empty[Suoritus]
      if (tieto.isDefined) {
        suoritus = sureSuoritukset.find(s => s.asInstanceOf[VirallinenSuoritus].myontaja.equals(tieto.get.oppilaitosOid))
      } else {
        suoritus = Option.empty
      }
      (suoritus, tieto)
    }

    //Teoria: Suorituksen voi poistaa, jos samalla oppilaitoksella löytyy suresta luokkatieto, jossa lähteenä koski ja luokka tyhjä.
    //Ongelma: myös suoritusten oppilaitokset saattavat olla väärin
    def detectAndFixFalseYsiness(suorituksetSuressa: Seq[Suoritus], koskiSuoritus: VirallinenSuoritus, kaikkiKoskiSuoritukset: Seq[(Suoritus, Seq[Arvosana], String, LocalDate, Option[String])]): Unit = {
      if (!kaikkiKoskiSuoritukset.exists(_._5.getOrElse("").startsWith("9"))) {
        logger.info(s"Detect valeysit : Henkilöllä "+koskiSuoritus.henkilo+" on peruskoulusuoritus, joka ei sisällä 9. luokan suoritusta. Päätellään tästä, että kyseessä on mahdollinen valeysi.")
        fetchExistingLuokkatiedot(koskiSuoritus.henkilo).onComplete(luokkatiedot => {
          logger.info(s"Detect valeysit : Luokkatieto: " + luokkatiedot)
          logger.info(s"Detect valeysit : Suoritukset: " + suorituksetSuressa.toString())
          if (suorituksetSuressa.exists(_.asInstanceOf[VirallinenSuoritus].komo.equals(Oids.perusopetusKomoOid))) {
            //logger.info(s"Detect valeysit : Sureen on aiemmin tallennettu perusopetussuoritus! ")
            val (poistettavaSuoritus, poistettavaLuokkatieto) = findMatchingLuokkatietoAndSuoritus(suorituksetSuressa, luokkatiedot.get)
            if (poistettavaLuokkatieto.isDefined && poistettavaSuoritus.isDefined && poistettavaLuokkatieto.get.source.equals("koski") && !poistettavaLuokkatieto.get.luokka.startsWith("9")) {
              logger.info(s"Detect valeysit : (HenkilöOid: " + koskiSuoritus.henkilo+ " ) Tässä kohtaa poistettaisiin suoritusresurssi id:llä " +
                poistettavaSuoritus.get.asInstanceOf[Identified[UUID]].id + "sekä luokkatietoresurssi id:llä " + poistettavaLuokkatieto.get.asInstanceOf[Identified[UUID]].id)
              //suoritusRekisteri ? DeleteResource(poistettavaSuoritus.get.asInstanceOf[Identified[UUID]].id, "koski-integraatio-fix")
              //opiskelijaRekisteri ? DeleteResource(poistettavaLuokkatieto.get.asInstanceOf[Identified[UUID]].id, "koski-integraatio-fix")
            } else {
              //logger.info(s"Ehdot täyttyivät muuten, mutta sureen tallennettua sopivaa suoritus-luokkatieto-paria ei löytynyt.")
            }
          }
        })

      } else {
        //logger.info(s"Detect valeysit : Henkilöllä "+koskiSuoritus.henkilo+" kaikki kunnossa.")
      }
    }

    henkilo.henkilö.oid.foreach(henkiloOid => {
      val allSuoritukset: Seq[(Suoritus, Seq[Arvosana], String, LocalDate, Option[String])] = createSuorituksetJaArvosanatFromKoski(henkilo)
      fetchExistingSuoritukset(henkiloOid).foreach(suoritukset => {
        var henkilonSuoritukset = allSuoritukset.filter(_._1.asInstanceOf[VirallinenSuoritus].henkilo.equals(henkiloOid))
        henkilonSuoritukset.foreach {
          case (suor: VirallinenSuoritus, arvosanat: Seq[Arvosana], luokka: String, lasnaDate: LocalDate, luokkaAste: Option[String]) =>

            if(removeFalseYsit && suor.komo.equals(Oids.perusopetusKomoOid)) {
              detectAndFixFalseYsiness(suoritukset, suor, henkilonSuoritukset)
            }
            var useArvosanat = arvosanat
            val useSuoritus = suor
            if(suor.komo.equals(Oids.perusopetusKomoOid) && arvosanat.isEmpty){
              useArvosanat = henkilonSuoritukset
                .filter(_._1.asInstanceOf[VirallinenSuoritus].henkilo.equals(suor.henkilo))
                .filter(_._1.asInstanceOf[VirallinenSuoritus].myontaja.equals(suor.myontaja))
                .filter(_._1.asInstanceOf[VirallinenSuoritus].komo.equals("luokka"))
                .filter(_._5.getOrElse("").startsWith("9"))
                .map(_._2).flatten
            }
            var useLuokka = "" //Käytännössä vapaa tekstikenttä. Luokkatiedon "luokka".
            var useLuokkaAste = luokkaAste
            if (henkilonSuoritukset.exists(_._5.getOrElse("").startsWith("9")) && useSuoritus.komo.equals(Oids.perusopetusKomoOid)) {
              useLuokka = henkilonSuoritukset.find(_._5.getOrElse("").startsWith("9")).head._3
              useLuokkaAste = Some("9")
            } else {
              useLuokka = luokka
            }
            if (luokkaAste.getOrElse("").equals(AIKUISTENPERUS_LUOKKAASTE)) {
              useLuokkaAste = Some("9")
              useLuokka = AIKUISTENPERUS_LUOKKAASTE+" "+luokka
            }
            val peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus = useSuoritus.komo.equals(Oids.perusopetusKomoOid) && (henkilonSuoritukset.exists(_._5.getOrElse("").startsWith("9"))
                                                                                                                          || luokkaAste.getOrElse("").equals(AIKUISTENPERUS_LUOKKAASTE))

            if (!useSuoritus.komo.equals("luokka") && (peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus || !useSuoritus.komo.equals(Oids.perusopetusKomoOid))) {
              val opiskelija = createOpiskelija(henkiloOid, SuoritusLuokka(useSuoritus, useLuokka, lasnaDate, useLuokkaAste)) //LUOKKATIETO
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
          case (_, _, _, _, _) =>
          // VapaamuotoinenSuoritus will not be saved
        }
      })
    })
  }

  def apply(suoritusRekisteri: ActorRef, arvosanaRekisteri: ActorRef, opiskelijaRekisteri: ActorRef)(implicit ec: ExecutionContext): KoskiTrigger = {
    KoskiTrigger {
      (koskiHenkilo: KoskiHenkiloContainer, personOidsWithAliases: PersonOidsWithAliases, removeFalseYsit: Boolean) => {
        muodostaKoskiSuorituksetJaArvosanat(koskiHenkilo, suoritusRekisteri, arvosanaRekisteri, opiskelijaRekisteri,
                                            personOidsWithAliases.intersect(koskiHenkilo.henkilö.oid.toSet), removeFalseYsit = removeFalseYsit)
      }
    }
  }

  def maxDate(s1: LocalDate, s2: LocalDate): LocalDate = if (s1.isAfter(s2)) s1 else s2
  def minDate(s1: LocalDate, s2: LocalDate): LocalDate = if (s1.isAfter(s2)) s2 else s1

  def createOpiskelija(henkiloOid: String, suoritusLuokka: SuoritusLuokka): Opiskelija = {
    var def_alku = suoritusLuokka.lasnaDate.toDateTimeAtStartOfDay
    var loppu = suoritusLuokka.suoritus.valmistuminen.toDateTimeAtStartOfDay

    var (luokkataso, oppilaitosOid, luokka, alku) = detectOppilaitos(suoritusLuokka, def_alku)

    if (!loppu.isAfter(alku)) {
      loppu = parseNextFourthOfJune().toDateTimeAtStartOfDay
      if (!loppu.isAfter(alku)) {
        alku = new DateTime(0L) //Sanity
      }
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

  def getOppilaitosAndLuokka(luokkataso: String, luokkaSuoritus: SuoritusLuokka, komoOid: String, alku: DateTime): (String, String, String, DateTime) = {
    komoOid match {
      // hae luokka 9C tai vast
      case Oids.perusopetusKomoOid => {
        (luokkataso, luokkaSuoritus.suoritus.myontaja, luokkaSuoritus.luokka, alku)
      }
      case Oids.lisaopetusKomoOid => {
        var luokka = luokkaSuoritus.luokka
        if(luokkaSuoritus.luokka.isEmpty){
          luokka = "10"
        }
        (luokkataso, luokkaSuoritus.suoritus.myontaja, luokka, alku)
      }
      case _ => (luokkataso, luokkaSuoritus.suoritus.myontaja, luokkaSuoritus.luokka, alku)
    }
  }

  //noinspection ScalaStyle
  def detectOppilaitos(suoritus: SuoritusLuokka, alku: DateTime): (String, String, String, DateTime) = suoritus match {
    case s if s.suoritus.komo == Oids.lukioKomoOid => getOppilaitosAndLuokka("L", s, Oids.lukioKomoOid, alku)
    case s if s.suoritus.komo == Oids.lukioonvalmistavaKomoOid => getOppilaitosAndLuokka("ML", s, Oids.lukioonvalmistavaKomoOid, alku)
    case s if s.suoritus.komo == Oids.ammatillinenKomoOid => getOppilaitosAndLuokka("AK", s, Oids.ammatillinenKomoOid, alku)
    case s if s.suoritus.komo == Oids.ammatilliseenvalmistavaKomoOid => getOppilaitosAndLuokka("M", s, Oids.ammatilliseenvalmistavaKomoOid, alku)
    case s if s.suoritus.komo == Oids.ammattistarttiKomoOid => getOppilaitosAndLuokka("A", s, Oids.ammattistarttiKomoOid, alku)
    case s if s.suoritus.komo == Oids.valmentavaKomoOid => getOppilaitosAndLuokka("V", s, Oids.valmentavaKomoOid, alku)
    case s if s.suoritus.komo == Oids.valmaKomoOid => getOppilaitosAndLuokka("VALMA", s, Oids.valmaKomoOid, alku)
    case s if s.suoritus.komo == Oids.telmaKomoOid => getOppilaitosAndLuokka("TELMA", s, Oids.telmaKomoOid, alku)
    case s if s.suoritus.komo == Oids.lisaopetusKomoOid => getOppilaitosAndLuokka("10", s, Oids.lisaopetusKomoOid, alku)
    case s if s.suoritus.komo == Oids.perusopetusKomoOid && (s.luokkataso.getOrElse("").equals("9") || s.luokkataso.getOrElse("").equals("AIK")) => getOppilaitosAndLuokka("9", s, Oids.perusopetusKomoOid, alku)
    case _ => ("", "", "", alku)
  }

  def createArvosana(personOid: String, arvo: Arvio, aine: String, lisatieto: Option[String], valinnainen: Boolean, jarjestys: Option[Int] = None): Arvosana = {
    Arvosana(suoritus = null, arvio = arvo, aine, lisatieto, valinnainen, myonnetty = None, source = personOid, Map(), jarjestys = jarjestys)
  }

  def createSuorituksetJaArvosanatFromKoski(henkilo: KoskiHenkiloContainer): Seq[(Suoritus, Seq[Arvosana], String, LocalDate, Option[String])] = {
    getSuoritusArvosanatFromOpiskeluoikeus(henkilo.henkilö.oid.getOrElse(""), henkilo.opiskeluoikeudet)
  }

  def parseLocalDate(s: String): LocalDate =
    if (s.length() > 10) {
      DateTimeFormat.forPattern("yyyy-MM-ddZ").parseLocalDate(s)
    } else {
      DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate(s)
    }

  def getSuoritusArvosanatFromOpiskeluoikeus(personOid: String, opiskeluoikeudet: Seq[KoskiOpiskeluoikeus]): Seq[(Suoritus, Seq[Arvosana], String, LocalDate, Option[String])] = {
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

  def matchOpetusOidAndLuokkataso(koulutusmoduuliTunnisteKoodiarvo: String, viimeisinTila: String, suoritus: KoskiSuoritus): (String, Option[String]) = {
    koulutusmoduuliTunnisteKoodiarvo match {
      case "perusopetuksenoppimaara" => (Oids.perusopetusKomoOid, suoritus.koulutusmoduuli.tunniste.flatMap(k => Some(k.koodiarvo)))
      case "aikuistenperusopetuksenoppimaara" => (Oids.perusopetusKomoOid, Some(AIKUISTENPERUS_LUOKKAASTE))
      case "aikuistenperusopetuksenoppimaaranalkuvaihe" => (DUMMYOID, None) //aikuisten perusopetuksen alkuvaihe ei kiinnostava suren kannalta
      case "perusopetuksenvuosiluokka" => ("luokka", suoritus.koulutusmoduuli.tunniste.flatMap(k => Some(k.koodiarvo)))
      case "valma" => (Oids.valmaKomoOid, None)
      case "telma" => (Oids.telmaKomoOid, None)
      case "luva" => (Oids.lukioonvalmistavaKomoOid, None)
      case "perusopetuksenlisaopetus" => (Oids.lisaopetusKomoOid, None)
      case "ammatillinentutkinto" if !viimeisinTila.equals("KESKEYTYNYT") =>  (Oids.ammatillinenKomoOid, None) //KESKEYTYNEITÄ AMMATILLISIA SUORITUKSIA EI HALUTA SUREEN
      case _ => (DUMMYOID, None)
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

  def createSuoritusArvosanat(personOid: String, suoritukset: Seq[KoskiSuoritus], tilat: Seq[KoskiTila], opiskeluoikeus: KoskiOpiskeluoikeus): Seq[(Suoritus, Seq[Arvosana], String, LocalDate, Option[String])] = {
    var result = Seq[(Suoritus, Seq[Arvosana], String, LocalDate, Option[String])]()
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

        /*var luokkataso: Option[String] = None
        if (suoritus.tyyppi.isDefined && suoritus.tyyppi.get.koodiarvo.equals("aikuistenperusopetuksenoppimaara")) {
          luokkataso = Some(AIKUISTENPERUS_LUOKKAASTE)
        } else if (suoritus.koulutusmoduuli.tunniste.isDefined) {
          luokkataso = Some(suoritus.koulutusmoduuli.tunniste.get.koodiarvo) //Luokkasuorituksen koodiarvo, 9 tarkoittaa 9.luokkaa jne. Olennainen vain peruskoulusuorituksille.
        }*/

        var (komoOid, luokkataso) = suoritus.tyyppi match {
          case Some(k) =>
            matchOpetusOidAndLuokkataso(k.koodiarvo, lastTila, suoritus)
          case _ => (DUMMYOID, None)
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
          case Oids.lukioonvalmistavaKomoOid => suoritus.ryhmä.getOrElse("LUVA")
          case Oids.ammatillinenKomoOid => suoritus.ryhmä.getOrElse("AMM")
          case _ => suoritus.luokka.getOrElse("")
        }
        if (luokka == "" && suoritus.tyyppi.isDefined && suoritus.tyyppi.get.koodiarvo == "aikuistenperusopetuksenoppimaara") {
          luokka = "9"
        }

        val useValmistumisPaiva = (komoOid, luokkataso.getOrElse("").startsWith("9"), lastTila) match {
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
            root_org_id), arvosanat, luokka, lasnaDate, luokkataso)
        }
      }
      result
  }
}

case class SuoritusLuokka(suoritus: VirallinenSuoritus, luokka: String, lasnaDate: LocalDate, luokkataso: Option[String] = None)

case class MultipleSuoritusException(henkiloOid: String,
                                     myontaja: String,
                                     komo: String)
  extends Exception(s"Multiple suoritus found for henkilo $henkiloOid by myontaja $myontaja with komo $komo.")