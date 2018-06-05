package fi.vm.sade.hakurekisteri.integration.koski

import java.util.{Calendar, UUID}

import akka.actor.ActorRef
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri._
import fi.vm.sade.hakurekisteri.arvosana._
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.storage.{DeleteResource, Identified, InsertResource}
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.Yksilollistetty
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, LocalDate, LocalDateTime}
import org.json4s.DefaultFormats
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.math.BigDecimal
import scala.util.{Failure, Success}

object KoskiArvosanaTrigger {

  private val logger = LoggerFactory.getLogger(getClass)

  import scala.language.implicitConversions

  implicit val formats: DefaultFormats.type = DefaultFormats

  private val AIKUISTENPERUS_LUOKKAASTE = "AIK"
  private val DUMMYOID = "999999" //Dummy oid value for to-be-ignored komos
  private val root_org_id = "1.2.246.562.10.00000000001"
  private val valinnaisetkielet = Set("A1", "B1")
  private val a2b2Kielet = Set("A2", "B2")
  private val valinnaiset = Set("KO") ++ valinnaisetkielet

  private val kielet = Set("A1", "A12", "A2", "A22", "B1", "B2", "B22", "B23", "B3", "B32", "B33")
  private val oppiaineet = Set( "HI", "MU", "BI", "KT", "FI", "KO", "KE", "YH", "TE", "KS", "FY", "GE", "LI", "KU", "MA")
  private val eivalinnaiset = kielet ++ oppiaineet ++ Set("AI")
  private val peruskoulunaineet = kielet ++ oppiaineet ++ Set("AI")
  private val lukioaineet = peruskoulunaineet ++ Set("PS") //lukio has psychology as a mandatory subject
  private val lukioaineetRegex = lukioaineet.map(_.r)

  private val kieletRegex = kielet.map(str => str.r)
  private val oppiaineetRegex = oppiaineet.map(str => s"$str\\d?".r)
  private val peruskouluaineetRegex = kieletRegex ++ oppiaineetRegex ++ Set("AI".r)

  private val peruskoulunArvosanat = Set[String]("4", "5", "6", "7", "8", "9", "10", "S")
  // koski to sure mapping oppiaineaidinkielijakirjallisuus -> aidinkielijakirjallisuus
  val aidinkieli = Map("AI1" -> "FI", "AI2" -> "SV", "AI3" -> "SE", "AI4" -> "RI", "AI5" -> "VK", "AI6" -> "XX", "AI7" -> "FI_2", "AI8" -> "SE_2", "AI9" -> "FI_SE", "AI10" -> "XX", "AI11" -> "FI_VK", "AI12" -> "SV_VK", "AIAI" -> "XX")

  def muodostaKoskiSuorituksetJaArvosanat(koskihenkilöcontainer: KoskiHenkiloContainer,
                                          suoritusRekisteri: ActorRef,
                                          arvosanaRekisteri: ActorRef,
                                          opiskelijaRekisteri: ActorRef,
                                          personOidsWithAliases: PersonOidsWithAliases,
                                          logBypassed: Boolean = false,
                                          createLukio: Boolean = false)
                                         (implicit ec: ExecutionContext): Unit = {
    implicit val timeout: Timeout = 2.minutes

    def saveSuoritus(suor: Suoritus): Future[Suoritus with Identified[UUID]] = {
      logger.debug("saveSuoritus={}", suor)
      (suoritusRekisteri ? InsertResource[UUID, Suoritus](suor, personOidsWithAliases)).mapTo[Suoritus with Identified[UUID]].recoverWith {
        case t: AskTimeoutException =>
          logger.error(s"Got timeout exception when saving suoritus $suor , retrying", t)
          saveSuoritus(suor)
      }
    }

    def fetchExistingSuoritukset(henkiloOid: String): Future[Seq[Suoritus]] = {
      val q = SuoritusQuery(henkilo = Some(henkiloOid))
      val f: Future[Any] = suoritusRekisteri ? SuoritusQueryWithPersonAliases(q, personOidsWithAliases)
      f.mapTo[Seq[Suoritus]].recoverWith {
        case t: AskTimeoutException =>
          logger.error(s"Got timeout exception when fetching existing suoritukset for henkilo $henkiloOid , retrying", t)
          fetchExistingSuoritukset(henkiloOid)
      }
    }

    def updateSuoritus(suoritus: VirallinenSuoritus with Identified[UUID], suor: VirallinenSuoritus): Future[VirallinenSuoritus with Identified[UUID]] =
    (suoritusRekisteri ? suoritus.copy(tila = suor.tila, valmistuminen = suor.valmistuminen, yksilollistaminen = suor.yksilollistaminen, suoritusKieli = suor.suoritusKieli)).mapTo[VirallinenSuoritus with Identified[UUID]].recoverWith{
      case t: AskTimeoutException => updateSuoritus(suoritus, suor)
    }

    def fetchArvosanat(s: VirallinenSuoritus with Identified[UUID]): Future[Seq[Arvosana with Identified[UUID]]] = {
      logger.debug("Haetaan arvosanat suoritukselle: " + s + ", id: " + s.id)
      (arvosanaRekisteri ? ArvosanaQuery(suoritus = s.id)).mapTo[Seq[Arvosana with Identified[UUID]]]
    }

    def deleteArvosana(s: Arvosana with Identified[UUID]): Future[Any] = {
      logger.debug("Poistetaan arvosana " + s + "UUID:lla" + s.id)
      arvosanaRekisteri ? DeleteResource(s.id, "koski-arvosanat")
    }

    def saveOpiskelija(opiskelija: Opiskelija): Unit = {
      opiskelijaRekisteri ! opiskelija
    }

    def suoritusExists(suor: VirallinenSuoritus, suoritukset: Seq[Suoritus]): Boolean = suoritukset.exists {
      case s: VirallinenSuoritus => s.core == suor.core
      case _ => false
    }

    def toArvosana(arvosana: Arvosana)(suoritus: UUID)(source: String): Arvosana =
      Arvosana(suoritus, arvosana.arvio, arvosana.aine, arvosana.lisatieto, arvosana.valinnainen, arvosana.myonnetty, source, Map(), arvosana.jarjestys)

    koskihenkilöcontainer.henkilö.oid.foreach(henkiloOid => {
      //prosessoidaan opiskeluoikeuskohtaisesti, muutoin useArvosana ja useLuokka prosessoinnit saattaa
      //ottaa arvoja väärästä opiskeluoikeudesta (BUG-1711)
      val allSuorituksetGroups: Seq[Seq[SuoritusArvosanat]] = createSuorituksetJaArvosanatFromKoski(koskihenkilöcontainer, createLukio)
      allSuorituksetGroups.foreach(allSuoritukset =>
        fetchExistingSuoritukset(henkiloOid).onComplete(fetchedSuoritukset => { //NOTE, processes the Future that encloses the list, does not actually iterate through the list
          val henkilonSuoritukset = allSuoritukset.filter(s => {
            s.suoritus.henkiloOid.equals(henkiloOid)
          })
          henkilonSuoritukset.foreach {
            case SuoritusArvosanat(useSuoritus: VirallinenSuoritus, arvosanat: Seq[Arvosana], luokka: String, lasnaDate: LocalDate, luokkaTaso: Option[String]) =>
              logger.debug(s"Suoritus $useSuoritus")
              val peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus = useSuoritus.komo.equals(Oids.perusopetusKomoOid) && (henkilonSuoritukset.exists(_.luokkataso.getOrElse("").startsWith("9"))
                                                                                                                            || luokkaTaso.getOrElse("").equals(AIKUISTENPERUS_LUOKKAASTE))
              //Suren suoritus = Kosken opiskeluoikeus + päättötodistussuoritus
              //Suren luokkatieto = Koskessa peruskoulun 9. luokan suoritus
              if (!useSuoritus.komo.equals("luokka") && (peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus || !useSuoritus.komo.equals(Oids.perusopetusKomoOid))) {

                val opiskelija = createOpiskelija(henkiloOid, SuoritusLuokka(useSuoritus, luokka, lasnaDate, luokkaTaso))
                val existingSuoritukset = fetchedSuoritukset.getOrElse(Seq())
                if(existingSuoritukset.isEmpty) {
                  logger.debug("Aiemmin sureen tallennettuja suorituksia ei ole. Suoritukset: " + fetchedSuoritukset + ", " + fetchedSuoritukset.get)
                }
                val hasExistingSuoritus = suoritusExists(useSuoritus, existingSuoritukset)
                if (hasExistingSuoritus) {
                  logger.debug("Päivitetään olemassaolevaa suoritusta.")
                  val suoritus = existingSuoritukset.map(_.asInstanceOf[VirallinenSuoritus with Identified[UUID]])
                    .find(s => s.henkiloOid == henkiloOid && s.myontaja == useSuoritus.myontaja && s.komo == useSuoritus.komo).get
                  logger.debug("Käsitellään olemassaoleva suoritus " + suoritus)

                  val suoritusSave = updateSuoritus(suoritus, useSuoritus)
                  suoritusSave.onSuccess { case savedSuoritus =>
                    logger.debug(s"Suorituksen $useSuoritus tallennus on palannut")
                    fetchArvosanat(suoritus).onComplete({
                      case Success(existingArvosanas) => {
                        logger.debug("fetchArvosanat success, result: " + existingArvosanas)
                        val pendingDeletes: Future[Seq[Any]] = Future.sequence(existingArvosanas
                            .filter(_.source.contentEquals("koski"))
                            .map(arvosana => deleteArvosana(arvosana)))
                        pendingDeletes.onComplete({
                          case Success(s) =>
                            arvosanat.foreach(newarvosana => {
                              val a: Arvosana = toArvosana(newarvosana)(suoritus.id)("koski")
                              logger.debug(s"inserting arvosana $a")
                              arvosanaRekisteri ! a
                            })
                          case Failure(t) =>
                            logger.error("Jokin meni pieleen vanhojen arvosanojen poistossa", t)
                            arvosanat.foreach(newarvosana => {
                              val a: Arvosana = toArvosana(newarvosana)(suoritus.id)("koski")
                              logger.debug(s"inserting arvosana $a")
                              arvosanaRekisteri ! a
                            })
                        })
                      }
                      case Failure(t) => logger.error("Jokin meni pieleen vanhojen arvosanojen haussa, joten niitä ei voitu poistaa: " + t.getMessage)
                    })
                  }
                  suoritusSave.onFailure { case t =>
                      logger.error(s"Suorituksen $useSuoritus (vanha suoritus: $suoritus) tallentaminen epäonnistui", t)
                  }

                  saveOpiskelija(opiskelija)
                } else {
                  for (
                    suoritus: Suoritus with Identified[UUID] <- saveSuoritus(useSuoritus)
                  ) arvosanat.foreach(arvosana =>
                    arvosanaRekisteri ! InsertResource[UUID, Arvosana](arvosanaForSuoritus(arvosana, suoritus), personOidsWithAliases)
                  )
                  saveOpiskelija(opiskelija)
                }
              }
            case _ =>
            // VapaamuotoinenSuoritus will not be saved
          }
        })
      )
      logger.info("Henkilön {} koski-tiedot tallennettu", henkiloOid)
    })
  }

  //does some stuff
  def preProcessVirallinenSuoritus(virallinenSuoritusArvosanat: VirallinenSuoritusArvosanat): VirallinenSuoritusArvosanat = {
    val useSuoritus: VirallinenSuoritus = virallinenSuoritusArvosanat.suoritus
    val arvosanat: Seq[Arvosana] = virallinenSuoritusArvosanat.arvosanat
    val luokka: String = virallinenSuoritusArvosanat.luokka
    val lasnaDate: LocalDate = virallinenSuoritusArvosanat.lasnadate
    val luokkaAste: Option[String] = virallinenSuoritusArvosanat.luokkataso

    VirallinenSuoritusArvosanat(useSuoritus, arvosanat, luokka, lasnaDate, luokkaAste)
  }

  def apply(suoritusRekisteri: ActorRef, arvosanaRekisteri: ActorRef, opiskelijaRekisteri: ActorRef)(implicit ec: ExecutionContext): KoskiTrigger = {
    KoskiTrigger {
      (koskiHenkilo: KoskiHenkiloContainer, personOidsWithAliases: PersonOidsWithAliases, createLukio: Boolean) => {
        muodostaKoskiSuorituksetJaArvosanat(koskiHenkilo, suoritusRekisteri, arvosanaRekisteri, opiskelijaRekisteri,
                                            personOidsWithAliases.intersect(koskiHenkilo.henkilö.oid.toSet), createLukio = createLukio)
      }
    }
  }

  def maxDate(s1: LocalDate, s2: LocalDate): LocalDate = if (s1.isAfter(s2)) s1 else s2
  def minDate(s1: LocalDate, s2: LocalDate): LocalDate = if (s1.isAfter(s2)) s2 else s1

  def createOpiskelija(henkiloOid: String, suoritusLuokka: SuoritusLuokka): Opiskelija = {

    logger.debug(s"suoritusLuokka=$suoritusLuokka")
    var alku = suoritusLuokka.lasnaDate.toDateTimeAtStartOfDay
    var loppu = suoritusLuokka.suoritus.valmistuminen.toDateTimeAtStartOfDay
    var (luokkataso, oppilaitosOid, luokka) = detectOppilaitos(suoritusLuokka)

    if (!loppu.isAfter(alku)) {
      logger.debug(s"!loppu.isAfter(alku) = $loppu isAfter $alku = false")
      loppu = parseNextFourthOfJune().toDateTimeAtStartOfDay
      if (!loppu.isAfter(alku)) {
        alku = new DateTime(0L) //Sanity
      }
    }

    logger.debug(s"alku=$alku")

    //luokkatieto käytännössä
    val op = Opiskelija(
      oppilaitosOid = oppilaitosOid,
      luokkataso = luokkataso,
      luokka = luokka,
      henkiloOid = henkiloOid,
      alkuPaiva = alku,
      loppuPaiva = Some(loppu),
      source = "koski"
    )
    logger.debug("createOpiskelija={}", op)
    op
  }

  def getOppilaitosAndLuokka(luokkataso: String, luokkaSuoritus: SuoritusLuokka, komoOid: String): (String, String, String) = {
    komoOid match {
      // hae luokka 9C tai vast
      case Oids.perusopetusKomoOid => {
        (luokkataso, luokkaSuoritus.suoritus.myontaja, luokkaSuoritus.luokka)
      }
      case Oids.lisaopetusKomoOid => {
        var luokka = luokkaSuoritus.luokka
        if(luokkaSuoritus.luokka.isEmpty){
          luokka = "10"
        }
        (luokkataso, luokkaSuoritus.suoritus.myontaja, luokka)
      }
      case _ => (luokkataso, luokkaSuoritus.suoritus.myontaja, luokkaSuoritus.luokka)
    }
  }

  //noinspection ScalaStyle
  def detectOppilaitos(suoritus: SuoritusLuokka): (String, String, String) = suoritus match {
    case s if s.suoritus.komo == Oids.lukioKomoOid => getOppilaitosAndLuokka("L", s, Oids.lukioKomoOid)
    case s if s.suoritus.komo == Oids.lukioonvalmistavaKomoOid => getOppilaitosAndLuokka("ML", s, Oids.lukioonvalmistavaKomoOid)
    case s if s.suoritus.komo == Oids.ammatillinenKomoOid => getOppilaitosAndLuokka("AK", s, Oids.ammatillinenKomoOid)
    case s if s.suoritus.komo == Oids.ammatilliseenvalmistavaKomoOid => getOppilaitosAndLuokka("M", s, Oids.ammatilliseenvalmistavaKomoOid)
    case s if s.suoritus.komo == Oids.ammattistarttiKomoOid => getOppilaitosAndLuokka("A", s, Oids.ammattistarttiKomoOid)
    case s if s.suoritus.komo == Oids.valmentavaKomoOid => getOppilaitosAndLuokka("V", s, Oids.valmentavaKomoOid)
    case s if s.suoritus.komo == Oids.valmaKomoOid => getOppilaitosAndLuokka("VALMA", s, Oids.valmaKomoOid)
    case s if s.suoritus.komo == Oids.telmaKomoOid => getOppilaitosAndLuokka("TELMA", s, Oids.telmaKomoOid)
    case s if s.suoritus.komo == Oids.lisaopetusKomoOid => getOppilaitosAndLuokka("10", s, Oids.lisaopetusKomoOid)
    case s if s.suoritus.komo == Oids.perusopetusKomoOid && (s.luokkataso.getOrElse("").equals("9") || s.luokkataso.getOrElse("").equals("AIK")) => getOppilaitosAndLuokka("9", s, Oids.perusopetusKomoOid)
    case _ => ("", "", "")
  }

  def createArvosana(personOid: String,
                     arvo: Arvio,
                     aine: String,
                     lisatieto: Option[String],
                     valinnainen: Boolean,
                     jarjestys: Option[Int] = None,
                     koskiArviointiPäiväJosSuorituksenValmistumisenJälkeen: Option[LocalDate]): Arvosana = {
    Arvosana(suoritus = null,
      arvio = arvo,
      aine, lisatieto,
      valinnainen,
      myonnetty = koskiArviointiPäiväJosSuorituksenValmistumisenJälkeen,
      source = "koski",
      Map(),
      jarjestys = jarjestys)
  }


  def createSuorituksetJaArvosanatFromKoski(henkilo: KoskiHenkiloContainer, createLukioArvosanat: Boolean = false): Seq[Seq[SuoritusArvosanat]] = {
    getSuoritusArvosanatFromOpiskeluoikeus(henkilo.henkilö.oid.getOrElse(""), henkilo.opiskeluoikeudet, createLukioArvosanat)
  }

  def parseLocalDate(s: String): LocalDate =
    if (s.length() > 10) {
      DateTimeFormat.forPattern("yyyy-MM-ddZ").parseLocalDate(s)
    } else {
      DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate(s)
    }

  def getSuoritusArvosanatFromOpiskeluoikeus(personOid: String, opiskeluoikeudet: Seq[KoskiOpiskeluoikeus], createLukioArvosanat: Boolean): Seq[Seq[SuoritusArvosanat]] = {
    val result: Seq[Seq[SuoritusArvosanat]] = for (
      opiskeluoikeus <- opiskeluoikeudet
    ) yield {
      createSuoritusArvosanat(personOid, opiskeluoikeus.suoritukset, opiskeluoikeus.tila.opiskeluoikeusjaksot, opiskeluoikeus, createLukioArvosanat)
    }
    result
  }

  def parseYear(dateStr: String): Int = {
    val dateFormat = "yyyy-MM-dd"
    val dtf = java.time.format.DateTimeFormatter.ofPattern(dateFormat)
    val d = java.time.LocalDate.parse(dateStr, dtf)
    d.getYear
  }

  def matchOpetusOidAndLuokkataso(koulutusmoduuliTunnisteKoodiarvo: String, viimeisinTila: String, suoritus: KoskiSuoritus, opiskeluoikeus: KoskiOpiskeluoikeus, createLukioArvosanat: Boolean = false): (String, Option[String]) = {
    if(opiskeluoikeus.tyyppi.getOrElse(KoskiKoodi("","")).koodiarvo.contentEquals("aikuistenperusopetus") && koulutusmoduuliTunnisteKoodiarvo == "perusopetuksenoppiaineenoppimaara") {
        (Oids.perusopetuksenOppiaineenOppimaaraOid, Some(AIKUISTENPERUS_LUOKKAASTE))
    } else {
      koulutusmoduuliTunnisteKoodiarvo match {
        case "perusopetuksenoppimaara" => (Oids.perusopetusKomoOid, suoritus.koulutusmoduuli.tunniste.flatMap(k => Some(k.koodiarvo)))
        case "perusopetuksenoppiaineenoppimaara" => (Oids.perusopetusKomoOid, None)
        case "aikuistenperusopetuksenoppimaara" => (Oids.perusopetusKomoOid, Some(AIKUISTENPERUS_LUOKKAASTE))
        case "aikuistenperusopetuksenoppimaaranalkuvaihe" => (DUMMYOID, None) //aikuisten perusopetuksen alkuvaihe ei kiinnostava suren kannalta
        case "perusopetuksenvuosiluokka" => ("luokka", suoritus.koulutusmoduuli.tunniste.flatMap(k => Some(k.koodiarvo)))
        case "valma" => (Oids.valmaKomoOid, None)
        case "telma" => (Oids.telmaKomoOid, None)
        case "luva" => (Oids.lukioonvalmistavaKomoOid, None)
        case "perusopetuksenlisaopetus" => (Oids.lisaopetusKomoOid, None)
        case "ammatillinentutkinto" => (Oids.ammatillinenKomoOid, None)
        case "lukionoppimaara" => //Käsitellään lukion oppimäärät vain, jos niiden tallentamista on erikseen kutsussa pyydetty.
          if(createLukioArvosanat)
            (Oids.lukioKomoOid, None)
          else
            (DUMMYOID, None)
        case _ => (DUMMYOID, None)
      }
    }
  }

  def arvosanaForSuoritus(arvosana: Arvosana, s: Suoritus with Identified[UUID]): Arvosana = {
    arvosana.copy(suoritus = s.id)
  }

  def isPK(osasuoritus: KoskiOsasuoritus): Boolean = {
    val koodi = osasuoritus.koulutusmoduuli.tunniste.getOrElse(KoskiKoodi("", "")).koodiarvo
    val isPK = peruskouluaineetRegex.exists(r => r.findFirstIn(koodi).isDefined)
    //peruskoulunaineet.contains()
    isPK
  }

  def isLukioSuoritus(osasuoritus: KoskiOsasuoritus): Boolean = {
    val koodi = osasuoritus.koulutusmoduuli.tunniste.getOrElse(KoskiKoodi("", "")).koodiarvo
    lukioaineetRegex.exists(r => r.findFirstIn(koodi).isDefined)
  }

  def isPKValue(arvosana: String): Boolean = {
    peruskoulunArvosanat.contains(arvosana) || arvosana == "H" //hylätty
  }


  def osasuoritusToArvosana(personOid: String,
                            komoOid: String,
                            osasuoritukset: Seq[KoskiOsasuoritus],
                            lisatiedot: Option[KoskiLisatiedot],
                            oikeus: Option[KoskiOpiskeluoikeus],
                            isLukio: Boolean = false,
                            suorituksenValmistumispäivä: LocalDate,
                            opiskeluoikeustyyppi: KoskiKoodi = KoskiKoodi("","")): (Seq[Arvosana], Yksilollistetty) = {
    var ordering = scala.collection.mutable.Map[String, Int]()
    var yksilöllistetyt = ListBuffer[Boolean]()

    //this processing is necessary because koskiopintooikeus might have either KT or ET code for "Uskonto/Elämänkatsomustieto"
    //while sure only supports the former. Thus we must convert "ET" codes into "KT"
    val modsuoritukset = osasuoritukset.map(s => {
      if(s.koulutusmoduuli.tunniste.getOrElse(KoskiKoodi("","")).koodiarvo.contentEquals("ET")) {
        val koulmod = s.koulutusmoduuli
        val uskontoElamankatsomusTieto = KoskiKoulutusmoduuli(Some(KoskiKoodi("KT", "koskioppiaineetyleissivistava")),
          koulmod.kieli, koulmod.koulutustyyppi, koulmod.laajuus, koulmod.pakollinen)
        KoskiOsasuoritus(uskontoElamankatsomusTieto, s.tyyppi, s.arviointi, s.pakollinen, s.yksilöllistettyOppimäärä, s.osasuoritukset)
      } else {
        s
      }

    })
    val isAikuistenPerusopetus: Boolean = opiskeluoikeustyyppi.koodiarvo.contentEquals("aikuistenperusopetus")
    var res:Seq[Arvosana] = Seq()
    for {
      suoritus <- modsuoritukset
      if isPK(suoritus) || (isLukio && isLukioSuoritus(suoritus))
    } yield {
      yksilöllistetyt += suoritus.yksilöllistettyOppimäärä.getOrElse(false)
      suoritus.arviointi.foreach(arviointi => {
        if (isPKValue(arviointi.arvosana.koodiarvo)) {
          val tunniste: KoskiKoodi = suoritus.koulutusmoduuli.tunniste.getOrElse(KoskiKoodi("", ""))
          val lisatieto: Option[String] = (tunniste.koodiarvo, suoritus.koulutusmoduuli.kieli) match {
            case (a: String, b: Option[KoskiKieli]) if kielet.contains(a) => Option(b.get.koodiarvo)
            case (a: String, b: Option[KoskiKieli]) if a == "AI" => Option(aidinkieli(b.get.koodiarvo))
            case _ => None
          }


          var isPakollinenmoduuli = false
          var isPakollinen = false
          if(isLukio) {
            isPakollinen = true
            isPakollinenmoduuli = true
          }
          else if(suoritus.koulutusmoduuli.pakollinen.isDefined) {
            isPakollinenmoduuli = suoritus.koulutusmoduuli.pakollinen.get
            isPakollinen = suoritus.koulutusmoduuli.pakollinen.get
          }
          else {
            isPakollinenmoduuli = suoritus.koulutusmoduuli.pakollinen.getOrElse(true)
            var isPakollinen = eivalinnaiset.contains(tunniste.koodiarvo)

            if(!isPakollinenmoduuli && valinnaiset.contains(tunniste.koodiarvo)) {
              isPakollinen = false
            }
          }

          if( (komoOid.contentEquals(Oids.perusopetusKomoOid) || komoOid.contentEquals(Oids.lisaopetusKomoOid)) &&
            (tunniste.koodiarvo.contentEquals("B2") || tunniste.koodiarvo.contentEquals("A2"))) {
            isPakollinen = true
            isPakollinenmoduuli = true
          }
          var ord: Option[Int] = None

          if (!isPakollinen) {
            val n = ordering.getOrElse(tunniste.koodiarvo, 0)
            ord = Some(n)
            val id = if(suoritus.koulutusmoduuli.kieli.isDefined) {
              tunniste.koodiarvo.concat(suoritus.koulutusmoduuli.kieli.get.koodiarvo)
            } else {
              tunniste.koodiarvo
            }
            ordering(id) = n + 1
          }

          val arvio = if(arviointi.arvosana.koodiarvo == "H") {
            ArvioHyvaksytty("hylatty")
          } else {
            Arvio410(arviointi.arvosana.koodiarvo)
          }

          val laajuus = suoritus.koulutusmoduuli.laajuus.getOrElse(KoskiValmaLaajuus(None, KoskiKoodi("","")))


          lazy val isKurssiLaajuus = laajuus.yksikkö.koodiarvo.contentEquals("4")
          lazy val isVVTLaajuus = laajuus.yksikkö.koodiarvo.contentEquals("3")
          lazy val isAikuistenKurssiLargeEnough = isAikuistenPerusopetus && isKurssiLaajuus && laajuus.arvo.getOrElse(BigDecimal(0)) >= 3
          lazy val isAikuistenKurssiVVTLargeEnough = isAikuistenPerusopetus && isVVTLaajuus && laajuus.arvo.getOrElse(BigDecimal(0)) >= 2
          lazy val isA2B2 = a2b2Kielet.contains(tunniste.koodiarvo)

          val isAikuistenValinnainen = isAikuistenPerusopetus && !isPakollinen

          if(isAikuistenValinnainen) {
            if(isAikuistenKurssiLargeEnough || isAikuistenKurssiVVTLargeEnough || isA2B2) {
              val käytettäväArviointiPäivä = ArvosanaMyonnettyParser.findArviointipäivä(suoritus, personOid, tunniste.koodiarvo, suorituksenValmistumispäivä)
              res = res :+ createArvosana(personOid, arvio, tunniste.koodiarvo, lisatieto, valinnainen = !isPakollinen, ord, käytettäväArviointiPäivä)
            }
          } else {
            //check for A2B2 langs because they aren't saved as elective courses, they are converted to mandatory on SURE side of things. The laajuus
            //check needs to be done on them too, not just elective grades.
            if( (!isPakollinen || a2b2Kielet.contains(tunniste.koodiarvo)) && isVVTLaajuus && laajuus.arvo.getOrElse(BigDecimal(0)) < 2) {
              //nop, only add ones that have two or more study points (vuosiviikkotuntia is the actual unit, code 3), everything else is saved
            } else {
              val käytettäväArviointiPäivä = ArvosanaMyonnettyParser.findArviointipäivä(suoritus, personOid, tunniste.koodiarvo, suorituksenValmistumispäivä)
              res = res :+ createArvosana(personOid, arvio, tunniste.koodiarvo, lisatieto, valinnainen = !isPakollinen, ord, käytettäväArviointiPäivä)
            }
          }
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

  def getValmaOsaamispisteet(suoritus: KoskiSuoritus): BigDecimal = {
    val sum = suoritus.osasuoritukset
      .filter(_.arviointi.exists(_.hyväksytty.contains(true)))
      .flatMap(_.koulutusmoduuli.laajuus)
      .map(_.arvo.getOrElse(BigDecimal(0)))
      .sum
    sum
  }

  def getValmistuminen(vahvistus: Option[KoskiVahvistus], alkuPvm: String, opOikeus: KoskiOpiskeluoikeus): (Int, LocalDate, String) = {
    if(!(opOikeus.oppilaitos.isDefined && opOikeus.oppilaitos.get.oid.isDefined)) {
      throw new RuntimeException("Opiskeluoikeudella on oltava oppilaitos!")
    }
    val oppilaitos = opOikeus.oppilaitos.get
    (vahvistus, opOikeus.päättymispäivä) match {
      case (Some(k: KoskiVahvistus),_) => (parseYear(k.päivä), parseLocalDate(k.päivä), k.myöntäjäOrganisaatio.oid.getOrElse(DUMMYOID))
      case (None, Some(dateStr)) => (parseYear(dateStr), parseLocalDate(dateStr), oppilaitos.oid.getOrElse(DUMMYOID))
      case _ => (parseYear(alkuPvm), parseLocalDate(alkuPvm), oppilaitos.oid.getOrElse(DUMMYOID))
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

  def getNumberOfAcceptedLuvaCourses(osasuoritukset: Seq[KoskiOsasuoritus]): Int = {
    var suoritukset = 0
    if(osasuoritukset.isEmpty) return suoritukset

    val hyvaksytty: Seq[KoskiOsasuoritus] = osasuoritukset
      .filter(s => s.tyyppi.koodiarvo == "luvakurssi" || s.tyyppi.koodiarvo == "luvalukionoppiaine")
      .filter(s => s.arviointi.exists(_.hyväksytty.contains(true)))

    suoritukset = hyvaksytty.size
    for (os <- osasuoritukset) {
      suoritukset = suoritukset + getNumberOfAcceptedLuvaCourses(os.osasuoritukset.getOrElse(Seq()))
    }
    suoritukset
  }

  def getEndDateFromLastNinthGrade(suoritukset: Seq[KoskiSuoritus]): Option[LocalDate] = {
    val mostrecent = suoritukset.filter(s => s.luokka.getOrElse("").startsWith("9"))
        .sortWith((a,b) => {
          val aDate = parseLocalDate(a.vahvistus.getOrElse(KoskiVahvistus("1970-01-01",KoskiOrganisaatio(Some("")))).päivä)
          val bDate = parseLocalDate(b.vahvistus.getOrElse(KoskiVahvistus("1970-01-01",KoskiOrganisaatio(Some("")))).päivä)
          aDate.compareTo(bDate) > 0})

    if(mostrecent.nonEmpty) {
      if(mostrecent.head.vahvistus.isDefined) {
        Some(parseLocalDate(mostrecent.head.vahvistus.get.päivä))
      } else {
        None
      }
    } else {
      None
    }
  }

  case class SuoritusArvosanat(suoritus: Suoritus, arvosanat: Seq[Arvosana], luokka: String, lasnadate: LocalDate, luokkataso: Option[String])
  case class VirallinenSuoritusArvosanat(suoritus: VirallinenSuoritus, arvosanat: Seq[Arvosana], luokka: String, lasnadate: LocalDate, luokkataso: Option[String])

  private def isFailedNinthGrade(suoritukset: Seq[KoskiSuoritus]) : Boolean = {
    val ysiluokat = suoritukset.filter(_.luokka.getOrElse("").startsWith("9"))
    val failed = ysiluokat.exists(_.jääLuokalle.getOrElse(false))
    val succeeded = ysiluokat.exists(_.jääLuokalle.getOrElse(false) == false)
    failed && !succeeded
  }


  private def shouldProcessData(suoritus: KoskiSuoritus, tilat: Seq[KoskiTila], opiskeluoikeus: KoskiOpiskeluoikeus, createLukioArvosanat: Boolean): Boolean = {
    val suoritusTila = tilat match {
      case t if t.exists(_.tila.koodiarvo == "valmistunut") => "VALMIS"
      case t if t.exists(_.tila.koodiarvo == "eronnut") => "KESKEYTYNYT"
      case t if t.exists(_.tila.koodiarvo == "erotettu") => "KESKEYTYNYT"
      case t if t.exists(_.tila.koodiarvo == "katsotaaneronneeksi") => "KESKEYTYNYT"
      case t if t.exists(_.tila.koodiarvo == "mitatoity") => "KESKEYTYNYT"
      case t if t.exists(_.tila.koodiarvo == "peruutettu") => "KESKEYTYNYT"
      // includes these "loma" | "valiaikaisestikeskeytynyt" | "lasna" => "KESKEN"
      case _ => "KESKEN"
    }
    val (komoOid, luokkataso) = suoritus.tyyppi match {
      case Some(k) =>
        matchOpetusOidAndLuokkataso(k.koodiarvo, suoritusTila, suoritus, opiskeluoikeus, createLukioArvosanat)
      case _ => (DUMMYOID, None)
    }

    komoOid match {
      case Oids.perusopetusKomoOid | Oids.lisaopetusKomoOid =>
        //check oppiaine failures
        lazy val hasFailures = suoritus.osasuoritukset
          .filter(_.arviointi.nonEmpty)
          .exists(_.arviointi.head.hyväksytty.getOrElse(true) == false)
        suoritus.vahvistus.isDefined || hasFailures

      case _ => true
    }
  }

  def createSuoritusArvosanat(personOid: String, suoritukset: Seq[KoskiSuoritus], tilat: Seq[KoskiTila], opiskeluoikeus: KoskiOpiskeluoikeus, createLukioArvosanat: Boolean): Seq[SuoritusArvosanat] = {
    var result = Seq[SuoritusArvosanat]()
    val failedNinthGrade = isFailedNinthGrade(suoritukset)
    //val isperuskoulu = containsOnlyPeruskouluData(suoritukset)

    for {
      suoritus <- suoritukset if shouldProcessData(suoritus, tilat, opiskeluoikeus, createLukioArvosanat)
    } yield {
      val isVahvistettu = suoritus.vahvistus.isDefined
      val (vuosi, valmistumisPaiva, organisaatioOid) = getValmistuminen(suoritus.vahvistus, tilat.last.alku, opiskeluoikeus)
      var suorituskieli = suoritus.suorituskieli.getOrElse(KoskiKieli("FI", "kieli"))

      var suoritusTila = tilat match {
        case t if t.exists(_.tila.koodiarvo == "valmistunut") => "VALMIS"
        case t if t.exists(_.tila.koodiarvo == "eronnut") => "KESKEYTYNYT"
        case t if t.exists(_.tila.koodiarvo == "erotettu") => "KESKEYTYNYT"
        case t if t.exists(_.tila.koodiarvo == "katsotaaneronneeksi") => "KESKEYTYNYT"
        case t if t.exists(_.tila.koodiarvo == "mitatoity") => "KESKEYTYNYT"
        case t if t.exists(_.tila.koodiarvo == "peruutettu") => "KESKEYTYNYT"
        // includes these "loma" | "valiaikaisestikeskeytynyt" | "lasna" => "KESKEN"
        case _ => "KESKEN"
      }

      val lasnaDate = (suoritus.alkamispäivä, tilat.find(_.tila.koodiarvo == "lasna")) match {
        case (Some(a), _) => parseLocalDate(a)
        case (None, Some(kt)) => parseLocalDate(kt.alku)
        case (_,_) => valmistumisPaiva
      }

      val (komoOid, luokkataso) = suoritus.tyyppi match {
        case Some(k) =>
          matchOpetusOidAndLuokkataso(k.koodiarvo, suoritusTila, suoritus, opiskeluoikeus, createLukioArvosanat)
        case _ => (DUMMYOID, None)
      }

      val (arvosanat: Seq[Arvosana], yksilöllistaminen: Yksilollistetty) = komoOid match {
        case Oids.perusopetusKomoOid =>
          val opiskeluoikeustyyppi = opiskeluoikeus.tyyppi.getOrElse(KoskiKoodi("",""))
          var (as, yks) = osasuoritusToArvosana(personOid, komoOid, suoritus.osasuoritukset, opiskeluoikeus.lisätiedot,
            None, suorituksenValmistumispäivä = valmistumisPaiva, opiskeluoikeustyyppi = opiskeluoikeustyyppi)
          if(failedNinthGrade) {
            as = Seq.empty
          }

          val containsOneFailure: Boolean = as.exists(a => a.arvio match {
            case Arvio410(arvosana) => arvosana.contentEquals("4")
            case _ => false
          })

          if(isVahvistettu) {
            val vahvistusDate = parseLocalDate(suoritus.vahvistus.get.päivä)
            val d = parseLocalDate("2018-06-04")
            if (vahvistusDate.isAfter(d)) {
              (Seq(), yks)
            } else {
              (as, yks)
            }
          } else if (containsOneFailure) {
            (as, yks)
          } else {
            (Seq(), yks)
          }
        case "luokka" => osasuoritusToArvosana(personOid, komoOid, suoritus.osasuoritukset, opiskeluoikeus.lisätiedot, None, suorituksenValmistumispäivä = valmistumisPaiva)
        case Oids.valmaKomoOid => osasuoritusToArvosana(personOid, komoOid, suoritus.osasuoritukset, opiskeluoikeus.lisätiedot, None, suorituksenValmistumispäivä = valmistumisPaiva)
        case Oids.perusopetuksenOppiaineenOppimaaraOid =>
          var s: Seq[KoskiOsasuoritus] = suoritus.osasuoritukset
          if(suoritus.tyyppi.contains(KoskiKoodi("perusopetuksenoppiaineenoppimaara", "suorituksentyyppi"))) {
            s = s :+ KoskiOsasuoritus(suoritus.koulutusmoduuli, suoritus.tyyppi.getOrElse(KoskiKoodi("","")), suoritus.arviointi.getOrElse(Seq()), suoritus.pakollinen, None, None)
          }
          osasuoritusToArvosana(personOid, komoOid, s, opiskeluoikeus.lisätiedot, None, suorituksenValmistumispäivä = valmistumisPaiva)

        case Oids.telmaKomoOid => osasuoritusToArvosana(personOid, komoOid, suoritus.osasuoritukset, opiskeluoikeus.lisätiedot, None, suorituksenValmistumispäivä = valmistumisPaiva)
        case Oids.lukioonvalmistavaKomoOid => osasuoritusToArvosana(personOid, komoOid, suoritus.osasuoritukset, opiskeluoikeus.lisätiedot, None, suorituksenValmistumispäivä = valmistumisPaiva)
        case Oids.lisaopetusKomoOid => osasuoritusToArvosana(personOid, komoOid, suoritus.osasuoritukset, opiskeluoikeus.lisätiedot, None, suorituksenValmistumispäivä = valmistumisPaiva)
        case Oids.lukioKomoOid =>
          if (suoritus.vahvistus.isDefined && suoritusTila.equals("VALMIS")) {
            logger.debug("Luodaan lukiokoulutuksen arvosanat. PersonOid: {}, komoOid: {}, osasuoritukset: {}, lisätiedot: {}", personOid, komoOid, suoritus.osasuoritukset, opiskeluoikeus.lisätiedot)
            osasuoritusToArvosana(personOid, komoOid, suoritus.osasuoritukset, opiskeluoikeus.lisätiedot, None, isLukio = true, suorituksenValmistumispäivä = valmistumisPaiva)
          } else {
            (Seq(), yksilollistaminen.Ei)
          }
        //https://confluence.oph.ware.fi/confluence/display/AJTS/Koski-Sure+arvosanasiirrot
        //abiturienttien arvosanat haetaan hakijoille joiden lukion oppimäärän suoritus on vahvistettu KOSKI -palvelussa. Tässä vaiheessa ei haeta vielä lukion päättötodistukseen tehtyjä korotuksia.

        case _ => (Seq(), yksilollistaminen.Ei)
      }

      if(komoOid == Oids.valmaKomoOid && suoritusTila == "VALMIS" && opintopisteidenMaaraFromOsasuoritus(suoritus.osasuoritukset) < 30) {
        suoritusTila = "KESKEN"
      }

      //TODO process here or before the upper parts reference suoritustila??
      //see https://confluence.oph.ware.fi/confluence/display/AJTS/Koski-Sure+arvosanasiirrot
      val vuosiluokkiinSitoutumatonOpetus: Boolean = opiskeluoikeus.lisätiedot match {
        case Some(x) => x.vuosiluokkiinSitoutumatonOpetus.getOrElse(false)
        case None => false
      }

      suoritusTila = komoOid match {
        case Oids.lisaopetusKomoOid =>
          suoritusTila
          if (isVahvistettu) {
            "VALMIS"
          } else suoritusTila

        case Oids.valmaKomoOid | Oids.telmaKomoOid =>
          val pisteet = getValmaOsaamispisteet(suoritus)
          if(pisteet < 30){
            "KESKEYTYNYT"
          } else {
            "VALMIS"
          }

        case Oids.lukioonvalmistavaKomoOid =>
          val nSuoritukset = getNumberOfAcceptedLuvaCourses(suoritus.osasuoritukset)
          if(nSuoritukset >= 25 || isVahvistettu) {
            "VALMIS"
          } else "KESKEN"

        case Oids.perusopetusKomoOid =>
          if(failedNinthGrade || suoritus.jääLuokalle.contains(true) || (vuosiluokkiinSitoutumatonOpetus && !isVahvistettu)) {
            "KESKEYTYNYT"
          } else suoritusTila

        case s if s.startsWith("luokka") =>
          if(suoritus.jääLuokalle.contains(true) || (vuosiluokkiinSitoutumatonOpetus && !isVahvistettu))  {
            "KESKEYTYNYT"
          } else suoritusTila

        case _ => suoritusTila
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
        /*if (suoritusTila == "KESKEYTYNYT")
          failedNinthGrade = true*/
      }

      val useValmistumisPaiva: LocalDate = (komoOid, luokkataso.getOrElse("").startsWith("9"), suoritusTila) match {
        case (Oids.perusopetusKomoOid, _, "KESKEN") if suoritus.vahvistus.isEmpty => parseNextFourthOfJune()
        case (Oids.perusopetusKomoOid, _, "KESKEN") if suoritus.vahvistus.isDefined => parseLocalDate(suoritus.vahvistus.get.päivä)
        case (Oids.perusopetusKomoOid, _, "KESKEYTYNYT") if suoritus.tyyppi.getOrElse(KoskiKoodi("","")).koodiarvo.contentEquals("perusopetuksenoppimaara") =>
          val savetime: LocalDateTime = if(opiskeluoikeus.aikaleima.isDefined) {
            LocalDateTime.parse(opiskeluoikeus.aikaleima.get)
          } else {
            LocalDateTime.now()
          }
          getEndDateFromLastNinthGrade(suoritukset).getOrElse(savetime.toLocalDate)
        case (Oids.perusopetusKomoOid, _, "VALMIS") =>
          if (suoritus.vahvistus.isDefined) parseLocalDate(suoritus.vahvistus.get.päivä)
          else parseNextFourthOfJune()
        case (Oids.lisaopetusKomoOid, _, "KESKEN") => parseNextFourthOfJune()
        case (Oids.valmaKomoOid, _, "KESKEN") => parseNextFourthOfJune()
        case (Oids.telmaKomoOid, _, "KESKEN") => parseNextFourthOfJune()
        case ("luokka", true, "KESKEN") => parseNextFourthOfJune()
        case (_,_,_) => valmistumisPaiva
      }

      if (komoOid != DUMMYOID && vuosi > 1970) {
        val suoritus = SuoritusArvosanat(VirallinenSuoritus(
            komo = komoOid,
            myontaja = organisaatioOid,
            tila = suoritusTila,
            valmistuminen = useValmistumisPaiva,
            henkilo = personOid,
            yksilollistaminen = yksilöllistaminen,
            suoritusKieli = suorituskieli.koodiarvo,
            opiskeluoikeus = None,
            vahv = true,
            lahde = root_org_id), arvosanat, luokka, lasnaDate, luokkataso)
        logger.debug("createSuoritusArvosanat={}", suoritus)
        if (createLukioArvosanat && komoOid == Oids.lukioKomoOid ) {
          logger.debug("created lukio arvosanas: {} for suoritus {} with lasnaDate {} and luokkataso {}",arvosanat, suoritus, lasnaDate, luokkataso)
        }
        result = result :+ suoritus
      }
    }

    val isPerusopetus: Boolean = result.exists(s => {
      val suoritus = s.suoritus.asInstanceOf[VirallinenSuoritus]
      if(opiskeluoikeus.tyyppi.isDefined) {
        Oids.perusopetusKomoOid == suoritus.komo && opiskeluoikeus.tyyppi.getOrElse(KoskiKoodi("","")).koodiarvo.contentEquals("perusopetus")
      } else {
        Oids.perusopetusKomoOid == suoritus.komo
      }
    })

    val hasNinthGrade: Boolean = result.exists(s => {
      //val suoritus = s._1.asInstanceOf[VirallinenSuoritus]
      val luokka = s.luokkataso
      luokka.contains("9") || s.luokka.startsWith("9")
    })

    //Postprocessing
    result = postprocessPeruskouluData(result)
    result = postProcessPOOData(result) //POO as in peruskoulun oppiaineen oppimäärä

    //todo this doens't have to be a sort of post-processing for the result list, could be done prior with koski data
    if(isPerusopetus && !hasNinthGrade) {
      Seq()
    } else {
      result
    }
  }

  /**
    * We need to save only one suoritus that contains all POO data, furthermore that POO data has to have the last valid
    * valmistumis date possible. Otherwise saving goes all wonky. Assumes that SuoritusArvosana suoritus is a VirallinenSuoritus.
    */
  private def postProcessPOOData(arvosanat: Seq[SuoritusArvosanat]): Seq[SuoritusArvosanat] = {
    val (oppiaineenOppimaarat, muut) = arvosanat.partition(sa => sa.suoritus match {
      case v: VirallinenSuoritus => v.komo.contentEquals(Oids.perusopetuksenOppiaineenOppimaaraOid)
      case _ => false
    })

    val newSuoritukset = oppiaineenOppimaarat
      .groupBy(_.suoritus.asInstanceOf[VirallinenSuoritus].myontaja)
      .map(entry => {
        val suoritukset = entry._2
        var suoritusArvosanatToBeSaved = suoritukset.head

        val allArvosanat: Set[Arvosana] = suoritukset.flatMap(_.arvosanat).toSet

        suoritukset.foreach(suoritusArvosanat => {
          val vs = suoritusArvosanat.suoritus.asInstanceOf[VirallinenSuoritus]
          if (vs.valmistuminen.isAfter(suoritusArvosanatToBeSaved.suoritus.asInstanceOf[VirallinenSuoritus].valmistuminen)) {
            suoritusArvosanatToBeSaved = suoritusArvosanat
          }
        })
        suoritusArvosanatToBeSaved.copy(arvosanat = allArvosanat.toSeq)
      })

    muut ++ newSuoritukset
  }

  /**
  This basically hoists luokka data from SuoritusArvosanat objects that have komo of "luokka"
  This is necessary because the saving that happens above in the object doesn't save luokka komo data, instead
  it just saves the whole perusopetus komo that contains grades and such.
    */
  private def postprocessPeruskouluData(result: Seq[SuoritusArvosanat]): Seq[SuoritusArvosanat] = {
    result.map(suoritusArvosanat => {
      var useSuoritus = suoritusArvosanat.suoritus.asInstanceOf[VirallinenSuoritus]
      val useArvosanat = if(useSuoritus.komo.equals(Oids.perusopetusKomoOid) && suoritusArvosanat.arvosanat.isEmpty){
        logger.debug("if(useSuoritus.komo.equals(Oids.perusopetusKomoOid) && arvosanat.isEmpty) == true")
        result
          .filter(hs => hs.suoritus match {
            case a: VirallinenSuoritus =>
              a.henkilo.equals(useSuoritus.henkilo) &&
                a.myontaja.equals(useSuoritus.myontaja) &&
                //  a.tila != "KESKEYTYNYT" &&
                a.komo.equals("luokka")
            case _ => false
          })
          .filter(_.luokkataso.contains("9"))
          .flatMap(s => s.arvosanat)
      } else {
        suoritusArvosanat.arvosanat
      }
      logger.debug(s"useArvosanat: $useArvosanat")

      var useLuokka = "" //Käytännössä vapaa tekstikenttä. Luokkatiedon "luokka".
      var useLuokkaAste = suoritusArvosanat.luokkataso
      var useLasnaDate = suoritusArvosanat.lasnadate

      val isNinthGrade = result.exists(_.luokkataso.getOrElse("").startsWith("9"))
      val isPerusopetus = useSuoritus.komo.equals(Oids.perusopetusKomoOid)

      if ( isNinthGrade && isPerusopetus ) {
        useLuokka = result.find(_.luokkataso.getOrElse("").startsWith("9")).head.luokka
        useLuokkaAste = Some("9")
        useLasnaDate = result
          .find(_.suoritus match {
            case a: VirallinenSuoritus =>
              a.henkilo.equals(useSuoritus.henkilo) &&
                a.myontaja.equals(useSuoritus.myontaja) &&
                a.komo.equals("luokka")
            case _ => false
          })
          .filter(_.luokkataso.contains("9"))
          .map(s => s.lasnadate).getOrElse(suoritusArvosanat.lasnadate) //fall back to this suoritus lasnadate

      } else {
        useLuokka = suoritusArvosanat.luokka
      }
      if (suoritusArvosanat.luokkataso.getOrElse("").equals(AIKUISTENPERUS_LUOKKAASTE)) {
        useLuokkaAste = Some("9")
        useLuokka = AIKUISTENPERUS_LUOKKAASTE+" "+suoritusArvosanat.luokka
      }
      SuoritusArvosanat(VirallinenSuoritus(
        komo = useSuoritus.komo,
        myontaja = useSuoritus.myontaja,
        tila = useSuoritus.tila,
        valmistuminen = useSuoritus.valmistuminen,
        henkilo = useSuoritus.henkilo,
        yksilollistaminen = useSuoritus.yksilollistaminen,
        suoritusKieli = useSuoritus.suoritusKieli,
        opiskeluoikeus = None,
        vahv = true,
        lahde = root_org_id), useArvosanat, useLuokka, useLasnaDate, useLuokkaAste)
    })
  }
}



case class SuoritusLuokka(suoritus: VirallinenSuoritus, luokka: String, lasnaDate: LocalDate, luokkataso: Option[String] = None)

case class MultipleSuoritusException(henkiloOid: String,
                                     myontaja: String,
                                     komo: String)
  extends Exception(s"Multiple suoritus found for henkilo $henkiloOid by myontaja $myontaja with komo $komo.")
