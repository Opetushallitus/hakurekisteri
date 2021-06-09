package fi.vm.sade.hakurekisteri.integration.koski

import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.arvosana.{Arvio, Arvio410, ArvioHyvaksytty, Arvosana}
import fi.vm.sade.hakurekisteri.integration.koski.KoskiUtil.parseLocalDate
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus, yksilollistaminen}
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.Yksilollistetty
import org.joda.time.{LocalDate, LocalDateTime}
import org.json4s.DefaultFormats
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer
import scala.math.BigDecimal

class KoskiSuoritusArvosanaParser {

  def createArvosana(
    personOid: String,
    arvo: Arvio,
    aine: String,
    lisatieto: Option[String],
    valinnainen: Boolean,
    jarjestys: Option[Int] = None,
    koskiArviointiPäiväJosSuorituksenValmistumisenJälkeen: Option[LocalDate]
  ): Arvosana = {
    Arvosana(
      suoritus = null,
      arvio = arvo,
      aine,
      lisatieto,
      valinnainen,
      myonnetty = koskiArviointiPäiväJosSuorituksenValmistumisenJälkeen,
      source = KoskiUtil.koski_integration_source,
      Map(),
      jarjestys = jarjestys
    )
  }

  private val logger = LoggerFactory.getLogger(getClass)

  import scala.language.implicitConversions

  implicit val formats: DefaultFormats.type = DefaultFormats

  def getSuoritusArvosanatFromOpiskeluoikeudes(
    personOid: String,
    opiskeluoikeudet: Seq[KoskiOpiskeluoikeus]
  ): Seq[Seq[SuoritusArvosanat]] = {
    val result: Seq[Seq[SuoritusArvosanat]] = for (opiskeluoikeus <- opiskeluoikeudet) yield {
      createSuoritusArvosanat(
        personOid,
        opiskeluoikeus.suoritukset,
        opiskeluoikeus.tila.opiskeluoikeusjaksot,
        opiskeluoikeus
      )
    }
    result
  }

  private def parseYear(dateStr: String): Int = {
    val dateFormat = "yyyy-MM-dd"
    val dtf = java.time.format.DateTimeFormatter.ofPattern(dateFormat)
    val d = java.time.LocalDate.parse(dateStr, dtf)
    d.getYear
  }

  private def isKoskiOsaSuoritusPakollinen(
    suoritus: KoskiOsasuoritus,
    isLukio: Boolean,
    komoOid: String
  ): Boolean = {
    var isSuoritusPakollinen: Boolean = false
    if (isLukio) {
      isSuoritusPakollinen = true
    } else if (suoritus.koulutusmoduuli.pakollinen.isDefined) {
      isSuoritusPakollinen = suoritus.koulutusmoduuli.pakollinen.get
    }

    if (
      (komoOid
        .contentEquals(Oids.perusopetusKomoOid) || komoOid.contentEquals(Oids.lisaopetusKomoOid)) &&
      (suoritus.koulutusmoduuli.tunniste
        .getOrElse(KoskiKoodi("", ""))
        .koodiarvo
        .contentEquals("B2") || suoritus.koulutusmoduuli.tunniste
        .getOrElse(KoskiKoodi("", ""))
        .koodiarvo
        .contentEquals("A2"))
    ) {
      isSuoritusPakollinen = true
    }
    isSuoritusPakollinen
  }

  def osasuoritusToArvosana(
    personOid: String,
    komoOid: String,
    osasuoritukset: Seq[KoskiOsasuoritus],
    lisatiedot: Option[KoskiLisatiedot],
    oikeus: Option[KoskiOpiskeluoikeus],
    isLukio: Boolean = false,
    suorituksenValmistumispäivä: LocalDate,
    isAikuistenPerusopetus: Boolean = false
  ): (Seq[Arvosana], Yksilollistetty) = {
    var ordering = scala.collection.mutable.Map[String, Int]()
    var yksilöllistetyt = ListBuffer[Boolean]()

    //this processing is necessary because koskiopintooikeus might have either KT or ET code for "Uskonto/Elämänkatsomustieto"
    //while sure only supports the former. Thus we must convert "ET" codes into "KT"
    var modsuoritukset: Seq[KoskiOsasuoritus] = osasuoritukset.map(s => {
      if (s.koulutusmoduuli.tunniste.getOrElse(KoskiKoodi("", "")).koodiarvo.contentEquals("ET")) {
        val koulmod = s.koulutusmoduuli
        val uskontoElamankatsomusTieto = KoskiKoulutusmoduuli(
          Some(KoskiKoodi("KT", "koskioppiaineetyleissivistava")),
          koulmod.kieli,
          koulmod.koulutustyyppi,
          koulmod.laajuus,
          koulmod.pakollinen
        )
        KoskiOsasuoritus(
          uskontoElamankatsomusTieto,
          s.tyyppi,
          s.arviointi,
          s.pakollinen,
          s.yksilöllistettyOppimäärä,
          s.osasuoritukset
        )
      } else {
        s
      }
    })
    var res: Seq[Arvosana] = Seq()
    for {
      suoritus <- modsuoritukset
      if suoritus.isPK || (isLukio && suoritus.isLukioSuoritus)
    } yield {
      if (isKoskiOsaSuoritusPakollinen(suoritus, isLukio, komoOid)) {
        yksilöllistetyt += suoritus.yksilöllistettyOppimäärä.getOrElse(false)
      }

      suoritus.arviointi.foreach(arviointi => {
        if (arviointi.isPKValue) {
          val tunniste: KoskiKoodi = suoritus.koulutusmoduuli.tunniste.getOrElse(KoskiKoodi("", ""))
          val lisatieto: Option[String] =
            (tunniste.koodiarvo, suoritus.koulutusmoduuli.kieli) match {
              case (a: String, b: Option[KoskiKieli]) if tunniste.kielet => Option(b.get.koodiarvo)
              case (a: String, b: Option[KoskiKieli]) if a == "AI" =>
                Option(KoskiUtil.aidinkieli(b.get.koodiarvo))
              case _ => None
            }
          val isPakollinen = isKoskiOsaSuoritusPakollinen(suoritus, isLukio, komoOid)
          var ord: Option[Int] = None

          if (!isPakollinen) {
            var n = 0
            val id = if (suoritus.koulutusmoduuli.kieli.isDefined) {
              n = ordering.getOrElse(
                tunniste.koodiarvo.concat(suoritus.koulutusmoduuli.kieli.get.koodiarvo),
                0
              )
              tunniste.koodiarvo.concat(suoritus.koulutusmoduuli.kieli.get.koodiarvo)
            } else {
              n = ordering.getOrElse(tunniste.koodiarvo, 0)
              tunniste.koodiarvo
            }
            ord = Some(n)
            ordering(id) = n + 1
          }

          val arvio = if (arviointi.arvosana.koodiarvo == "H") {
            ArvioHyvaksytty("hylatty")
          } else {
            Arvio410(arviointi.arvosana.koodiarvo)
          }

          val laajuus =
            suoritus.koulutusmoduuli.laajuus.getOrElse(KoskiValmaLaajuus(None, KoskiKoodi("", "")))

          lazy val isKurssiLaajuus = laajuus.yksikkö.koodiarvo.contentEquals("4")
          lazy val isVVTLaajuus = laajuus.yksikkö.koodiarvo.contentEquals("3")
          lazy val isAikuistenKurssiLargeEnough =
            isAikuistenPerusopetus && isKurssiLaajuus && laajuus.arvo.getOrElse(BigDecimal(0)) >= 3
          lazy val isAikuistenKurssiVVTLargeEnough =
            isAikuistenPerusopetus && isVVTLaajuus && laajuus.arvo.getOrElse(BigDecimal(0)) >= 2
          lazy val isA2B2 = tunniste.a2b2Kielet

          val isAikuistenValinnainen = isAikuistenPerusopetus && !isPakollinen

          if (isAikuistenValinnainen) {
            if (isAikuistenKurssiLargeEnough || isAikuistenKurssiVVTLargeEnough || isA2B2) {
              val käytettäväArviointiPäivä = ArvosanaMyonnettyParser.findArviointipäivä(
                suoritus,
                personOid,
                tunniste.koodiarvo,
                suorituksenValmistumispäivä
              )
              res = res :+ createArvosana(
                personOid,
                arvio,
                tunniste.koodiarvo,
                lisatieto,
                valinnainen = !isPakollinen,
                ord,
                käytettäväArviointiPäivä
              )
            }
          } else {
            if (!isPakollinen && isVVTLaajuus && laajuus.arvo.getOrElse(BigDecimal(0)) < 2) {
              //nop, only add ones that have two or more study points (vuosiviikkotuntia is the actual unit, code 3), everything else is saved
            } else {
              val käytettäväArviointiPäivä = ArvosanaMyonnettyParser.findArviointipäivä(
                suoritus,
                personOid,
                tunniste.koodiarvo,
                suorituksenValmistumispäivä
              )
              res = res :+ createArvosana(
                personOid,
                arvio,
                tunniste.koodiarvo,
                lisatieto,
                valinnainen = !isPakollinen,
                ord,
                käytettäväArviointiPäivä
              )
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
      } yield {
        for {
          tuenPaatos <- lisatieto.erityisenTuenPäätös //legacy
        } yield {
          if (tuenPaatos.opiskeleeToimintaAlueittain.getOrElse(false)) {
            yksilöllistetty = yksilollistaminen.Alueittain
          }
        }
        for {
          tuenpaatokset <- lisatieto.erityisenTuenPäätökset //new format
        } yield {
          if (tuenpaatokset.exists(tp => tp.opiskeleeToimintaAlueittain.getOrElse(false)))
            yksilöllistetty = yksilollistaminen.Alueittain
        }
      }
    }
    (res, yksilöllistetty)
  }

  private def getValmistuminen(
    vahvistus: Option[KoskiVahvistus],
    alkuPvm: String,
    opOikeus: KoskiOpiskeluoikeus
  ): Valmistuminen = {

    if (!(opOikeus.oppilaitos.isDefined && opOikeus.oppilaitos.get.oid.isDefined)) {
      throw new RuntimeException("Opiskeluoikeudella on oltava oppilaitos!")
    }
    val oppilaitos = opOikeus.oppilaitos.get
    val oppilaitosOid = opOikeus.oppilaitos.get.oid.get
    (vahvistus, opOikeus.päättymispäivä) match {
      case (Some(k: KoskiVahvistus), _) =>
        Valmistuminen(parseYear(k.päivä), parseLocalDate(k.päivä), oppilaitosOid)
      case (None, Some(dateStr)) =>
        Valmistuminen(parseYear(dateStr), parseLocalDate(dateStr), oppilaitosOid)
      case (None, None) =>
        Valmistuminen(
          parseYear(KoskiUtil.deadlineDate.toString()),
          parseLocalDate(KoskiUtil.deadlineDate.toString()),
          oppilaitosOid
        )
      case _ => Valmistuminen(parseYear(alkuPvm), parseLocalDate(alkuPvm), oppilaitosOid)
    }
  }

  def getNumberOfAcceptedLuvaCourses(osasuoritukset: Seq[KoskiOsasuoritus]): Int = {
    var suoritukset = 0
    if (osasuoritukset.isEmpty) return suoritukset

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
    val mostrecent = suoritukset
      .filter(s => s.luokka.getOrElse("").startsWith("9"))
      .filterNot(s1 => s1.vahvistus.isEmpty)
      .filterNot(s2 => s2.vahvistus.get.päivä.isEmpty)
      .sortWith((a, b) => {
        val aDate = parseLocalDate(a.vahvistus.get.päivä)
        val bDate = parseLocalDate(b.vahvistus.get.päivä)
        aDate.compareTo(bDate) > 0
      })

    if (mostrecent.nonEmpty) {
      if (mostrecent.head.vahvistus.isDefined) {
        Some(parseLocalDate(mostrecent.head.vahvistus.get.päivä))
      } else {
        None
      }
    } else {
      None
    }
  }

  private def isFailedNinthGrade(suoritukset: Seq[KoskiSuoritus]): Boolean = {
    val ysiluokat = suoritukset.filter(_.luokka.getOrElse("").startsWith("9"))
    val failed = ysiluokat.exists(_.jääLuokalle.getOrElse(false))
    val succeeded = ysiluokat.exists(_.jääLuokalle.getOrElse(false) == false)
    failed && !succeeded
  }

  private def perusopetuksenArvosanatSisaltavatNelosia(arvosanat: Seq[Arvosana]): Boolean = {
    arvosanat.exists(a =>
      a.arvio match {
        case Arvio410(arvosana) => arvosana.contentEquals("4")
        case _                  => false
      }
    )
  }

  private def createSuoritusArvosanat(
    personOid: String,
    suoritukset: Seq[KoskiSuoritus],
    tilat: Seq[KoskiTila],
    opiskeluoikeus: KoskiOpiskeluoikeus
  ): Seq[SuoritusArvosanat] = {
    var result = Seq[SuoritusArvosanat]()
    val failedNinthGrade = isFailedNinthGrade(suoritukset)
    var lahdeArvot: Map[String, String] = Map[String, String]()
    lahdeArvot += ("last modified" -> System.currentTimeMillis().toString)
    for {
      suoritus <- suoritukset
    } yield {
      val isVahvistettu = suoritus.vahvistus.isDefined
      val isAjoissaVahvistettu =
        suoritus.vahvistus.exists(v => {
          val valmistumispaiva = parseLocalDate(v.päivä)
          !valmistumispaiva.isAfter(KoskiUtil.deadlineDate)
        })
      val valmistuminen: Valmistuminen =
        getValmistuminen(suoritus.vahvistus, tilat.last.alku, opiskeluoikeus)
      val suorituskieli = suoritus.suorituskieli.getOrElse(KoskiKieli("FI", "kieli"))
      var suoritusTila: String = opiskeluoikeus.tila.determineSuoritusTila
      val lasnaDate = (suoritus.alkamispäivä, tilat.find(_.tila.koodiarvo == "lasna")) match {
        case (Some(a), _)     => parseLocalDate(a)
        case (None, Some(kt)) => parseLocalDate(kt.alku)
        case (_, _)           => valmistuminen.valmistumisPaiva
      }
      val komoOid: String = suoritus.getKomoOid(opiskeluoikeus.isAikuistenPerusopetus)
      val luokkataso: Option[String] =
        if (opiskeluoikeus.isKotiopetuslainen) Some("kotiopetus9")
        else suoritus.getLuokkataso(opiskeluoikeus.isAikuistenPerusopetus)

      val vuosiluokkiinSitomatonOpetus: Boolean = opiskeluoikeus.lisätiedot match {
        case Some(x) => {
          lahdeArvot += ("vuosiluokkiin sitomaton opetus" -> x.vuosiluokkiinSitoutumatonOpetus
            .getOrElse(false)
            .toString)
          x.vuosiluokkiinSitoutumatonOpetus.getOrElse(false)
        }
        case None => false
      }

      val (arvosanat: Seq[Arvosana], yksilöllistaminen: Yksilollistetty) = komoOid match {
        case Oids.perusopetusKomoOid | Oids.lisaopetusKomoOid | Oids.perusopetusLuokkaKomoOid =>
          val isValmis = suoritusTila.equals("VALMIS")
          val isAikuistenPerusopetus: Boolean = opiskeluoikeus.tyyppi
            .getOrElse(KoskiKoodi("", ""))
            .koodiarvo
            .contentEquals("aikuistenperusopetus")
          var (as, yks) = osasuoritusToArvosana(
            personOid,
            komoOid,
            suoritus.osasuoritukset,
            opiskeluoikeus.lisätiedot,
            None,
            suorituksenValmistumispäivä = valmistuminen.valmistumisPaiva,
            isAikuistenPerusopetus = isAikuistenPerusopetus
          )

          if (failedNinthGrade) {
            as = Seq.empty
          }

          val containsOneFailure = perusopetuksenArvosanatSisaltavatNelosia(as)

          //Tuodaan arvosanat kaikille valmiille suorituksille, jotka on vahvistettu ennen deadlinea.
          //Jos deadlineen on alle kaksi viikkoa, tuodaan myös keskeytyneiden suoritusten arvosanat jos mukana on nelosia.
          //Vuosiluokkiin sitomattoman opetuksen arvosanat tallennetaan suorituksen tilasta riippumatta, jos deadline on ohitettu.
          //Lisäopetuksen arvosanat tallennetaan aina suorituksen tilasta tai deadline-päivämäärästä riippumatta.
          if (isVahvistettu && isValmis) {
            if (!KoskiUtil.isAfterDeadlineDate(parseLocalDate(suoritus.vahvistus.get.päivä))) {
              (as, yks)
            } else {
              (Seq(), yks)
            }
          } else if (
            (containsOneFailure && KoskiUtil.isAfterArvosanatWithNelosiaDeadlineDate())
            || (vuosiluokkiinSitomatonOpetus && KoskiUtil.isAfterDeadlineDate())
            || komoOid.equals(Oids.lisaopetusKomoOid)
          ) {
            (as, yks)
          } else {
            (Seq(), yks)
          }
        case Oids.perusopetuksenOppiaineenOppimaaraOid =>
          var s: Seq[KoskiOsasuoritus] = suoritus.osasuoritukset
          // Tallennetaan vain vahvistetut perusopetuksen oppiaineen oppimäärän suoritusten arvosanat, ei kurssiarvosanoja.
          if (
            suoritus.tyyppi.contains(
              KoskiKoodi("perusopetuksenoppiaineenoppimaara", "suorituksentyyppi")
            ) && suoritus.vahvistus.isDefined
          ) {
            s = s :+ KoskiOsasuoritus(
              suoritus.koulutusmoduuli,
              suoritus.tyyppi.getOrElse(KoskiKoodi("", "")),
              suoritus.arviointi.getOrElse(Seq()),
              suoritus.pakollinen,
              None,
              None
            )
            // Filtteröidään vain suorituksen oppiaineen arvosanat
            s = s.filter(osaSuoritus =>
              osaSuoritus.koulutusmoduuli.tunniste
                .getOrElse(KoskiKoodi("", ""))
                .koodistoUri
                .contentEquals("koskioppiaineetyleissivistava")
            )
            osasuoritusToArvosana(
              personOid,
              komoOid,
              s,
              opiskeluoikeus.lisätiedot,
              None,
              suorituksenValmistumispäivä = valmistuminen.valmistumisPaiva
            )
          } else {
            (Seq(), yksilollistaminen.Ei)
          }

        //Ei tallenneta arvosanoja VALMA, TELMA. Osasuoritusten määrä vaikuttaa kuitenkin suorituksen tilaan toisaalla.
        case Oids.valmaKomoOid | Oids.telmaKomoOid =>
          val (arv, yks) = osasuoritusToArvosana(
            personOid,
            komoOid,
            suoritus.osasuoritukset,
            opiskeluoikeus.lisätiedot,
            None,
            suorituksenValmistumispäivä = valmistuminen.valmistumisPaiva
          )
          (Seq(), yks)
        case Oids.lukioonvalmistavaKomoOid =>
          osasuoritusToArvosana(
            personOid,
            komoOid,
            suoritus.osasuoritukset,
            opiskeluoikeus.lisätiedot,
            None,
            suorituksenValmistumispäivä = valmistuminen.valmistumisPaiva
          )
        case Oids.lukioKomoOid =>
          if (suoritus.vahvistus.isDefined && suoritusTila.equals("VALMIS")) {
            osasuoritusToArvosana(
              personOid,
              komoOid,
              suoritus.osasuoritukset,
              opiskeluoikeus.lisätiedot,
              None,
              isLukio = true,
              suorituksenValmistumispäivä = valmistuminen.valmistumisPaiva
            )
          } else {
            (Seq(), yksilollistaminen.Ei)
          }
        //https://confluence.oph.ware.fi/confluence/display/AJTS/Koski-Sure+arvosanasiirrot
        //abiturienttien arvosanat haetaan hakijoille joiden lukion oppimäärän suoritus on vahvistettu KOSKI -palvelussa. Tässä vaiheessa ei haeta vielä lukion päättötodistukseen tehtyjä korotuksia.

        case _ => (Seq(), yksilollistaminen.Ei)
      }

      suoritusTila = komoOid match {
        case Oids.lisaopetusKomoOid =>
          if (KoskiUtil.isAfterDeadlineDate() && !isVahvistettu) {
            "KESKEYTYNYT"
          } else suoritusTila

        case Oids.valmaKomoOid | Oids.telmaKomoOid =>
          val tarpeeksiOpintopisteita =
            ((komoOid == Oids.valmaKomoOid && suoritus.opintopisteitaVahintaan(30))
              || (komoOid == Oids.telmaKomoOid && suoritus.opintopisteitaVahintaan(25)))
          if (tarpeeksiOpintopisteita) {
            "VALMIS"
          } else {
            if (KoskiUtil.isAfterDeadlineDate()) {
              "KESKEYTYNYT"
            } else "KESKEN"
          }
        case Oids.lukioonvalmistavaKomoOid =>
          val nSuoritukset = getNumberOfAcceptedLuvaCourses(suoritus.osasuoritukset)
          if (nSuoritukset >= 25) {
            "VALMIS"
          } else {
            if (KoskiUtil.isAfterDeadlineDate()) {
              "KESKEYTYNYT"
            } else "KESKEN"
          }

        case Oids.perusopetusKomoOid =>
          val arvosanoissaNelosia = perusopetuksenArvosanatSisaltavatNelosia(arvosanat)
          if (failedNinthGrade || suoritus.jääLuokalle.contains(true)) {
            logger.info(
              s"Perusopetuksen tilapäättely - henkilö $personOid: merkitään luokalle jääväksi merkitty perusopetuksen suoritus tilaan KESKEYTYNYT (aiempi tila $suoritusTila)"
            )
            "KESKEYTYNYT"
          } else if (
            vuosiluokkiinSitomatonOpetus && KoskiUtil
              .isAfterDeadlineDate() && (!suoritusTila.equals("VALMIS") || !isVahvistettu)
          ) {
            logger.info(
              s"Perusopetuksen tilapäättely - henkilö $personOid: vuosiluokkiinSitomatonOpetus ja tila ei ole valmis ja deadline on ohitettu. Merkitään perusopetuksen suoritus tilaan KESKEYTYNYT (aiempi tila $suoritusTila)"
            )
            "KESKEYTYNYT"
          } else if (
            !suoritusTila.equals("KESKEYTYNYT") && arvosanoissaNelosia && KoskiUtil
              .isAfterDeadlineDate()
          ) {
            //On ok, että tässä merkitään poikkeuksellisesti suoritus valmiiksi myös ilman vahvistusta. Muualla tätä ei pitäisi tapahtua.
            logger.info(
              s"Perusopetuksen tilapäättely - henkilö $personOid: arvosanoissa on nelosia, tila ei ole keskeytynyt ja deadline on ohitettu. Merkitään perusopetuksen suoritus tilaan VALMIS (aiempi tila $suoritusTila)"
            )
            "VALMIS"
          } else if (
            (suoritusTila.equals("VALMIS") || suoritusTila.equals(
              "KESKEN"
            )) && !isAjoissaVahvistettu && !arvosanoissaNelosia && KoskiUtil.isAfterDeadlineDate()
          ) {
            logger.info(
              s"Perusopetuksen tilapäättely - henkilö $personOid: perusopetuksen suorituksella ei ole ajoissa annettua vahvistusta eikä nelosia ja deadline on ohitettu. Merkitään suoritus tilaan KESKEYTYNYT (aiempi tila $suoritusTila)"
            )
            "KESKEYTYNYT"
          } else suoritusTila

        case _ => suoritusTila
      }

      var luokka = komoOid match {
        case Oids.valmaKomoOid                  => suoritus.ryhmä.getOrElse("VALMA")
        case Oids.telmaKomoOid                  => suoritus.ryhmä.getOrElse("TELMA")
        case Oids.lukioonvalmistavaKomoOid      => suoritus.ryhmä.getOrElse("LUVA")
        case Oids.ammatillinenKomoOid           => suoritus.ryhmä.getOrElse("AMM")
        case Oids.erikoisammattitutkintoKomoOid => suoritus.ryhmä.getOrElse("")
        case _                                  => suoritus.luokka.getOrElse("")
      }
      if (
        luokka == "" && suoritus.tyyppi.isDefined && suoritus.tyyppi.get.koodiarvo == "aikuistenperusopetuksenoppimaara"
      ) {
        luokka = "9"
      }

      val useValmistumisPaiva: LocalDate =
        (komoOid, luokkataso.getOrElse("").startsWith("9"), suoritusTila) match {
          case (Oids.perusopetusKomoOid, _, "KESKEN") if suoritus.vahvistus.isEmpty =>
            KoskiUtil.deadlineDate
          case (Oids.perusopetusKomoOid, _, "KESKEN") if suoritus.vahvistus.isDefined =>
            parseLocalDate(suoritus.vahvistus.get.päivä)
          case (Oids.perusopetusKomoOid, _, "KESKEYTYNYT")
              if suoritus.tyyppi
                .getOrElse(KoskiKoodi("", ""))
                .koodiarvo
                .contentEquals("perusopetuksenoppimaara") =>
            val keskeytynytDate: LocalDateTime = if (opiskeluoikeus.päättymispäivä.isDefined) {
              LocalDateTime.parse(opiskeluoikeus.päättymispäivä.get)
            } else if (opiskeluoikeus.aikaleima.isDefined) {
              logger.info(
                "Henkilön {} keskeytyneeltä peruskoulusuoritukselta, oppilaitosOid: {} puuttuu keskeytymipäivämäärä, käytetään opiskeluoikeuden aikaleimaa: {}",
                personOid,
                suoritus.toimipiste.get.oid.getOrElse("Puuttuva organisaatioOid"),
                LocalDateTime.parse(opiskeluoikeus.aikaleima.get)
              )
              LocalDateTime.parse(opiskeluoikeus.aikaleima.get)
            } else {
              logger.info(
                "Henkilön {} keskeytyneeltä peruskoulusuoritukselta, oppilaitosOid: {} puuttuu keskeytymipäivämäärä ja opiskeluoikeuden aikaleima. Käytetään siirtopäivämäärää: {}",
                personOid,
                suoritus.toimipiste.get.oid.getOrElse("Puuttuva organisaatioOid"),
                LocalDateTime.now()
              )
              LocalDateTime.now()
            }
            getEndDateFromLastNinthGrade(suoritukset).getOrElse(keskeytynytDate.toLocalDate)
          case (Oids.perusopetusKomoOid, _, "VALMIS") =>
            if (suoritus.vahvistus.isDefined) parseLocalDate(suoritus.vahvistus.get.päivä)
            else KoskiUtil.deadlineDate
          case (Oids.lisaopetusKomoOid, _, "KESKEN")           => KoskiUtil.deadlineDate
          case (Oids.valmaKomoOid, _, "KESKEN")                => KoskiUtil.deadlineDate
          case (Oids.telmaKomoOid, _, "KESKEN")                => KoskiUtil.deadlineDate
          case (Oids.perusopetusLuokkaKomoOid, true, "KESKEN") => KoskiUtil.deadlineDate
          case (_, _, _)                                       => valmistuminen.valmistumisPaiva
        }

      val suoritustyyppi = suoritus.tyyppi.map(_.koodiarvo) match {
        case Some("perusopetuksenvuosiluokka") | Some("ammatillinentutkinto") =>
          Some(suoritus.koulutusmoduuli.tunniste.get.koodiarvo)
        case _ => None
      }
      if (komoOid != Oids.DUMMYOID && valmistuminen.vuosi > 1970) {
        val suoritus = SuoritusArvosanat(
          VirallinenSuoritus(
            komo = komoOid,
            myontaja = valmistuminen.organisaatioOid,
            tila = suoritusTila,
            valmistuminen = useValmistumisPaiva,
            henkilo = personOid,
            yksilollistaminen = yksilöllistaminen,
            suoritusKieli = suorituskieli.koodiarvo,
            opiskeluoikeus = None,
            vahv = true,
            lahde = KoskiUtil.koski_integration_source,
            suoritustyyppi = suoritustyyppi,
            lahdeArvot = lahdeArvot
          ),
          arvosanat,
          luokka,
          lasnaDate,
          luokkataso
        )
        result = result :+ suoritus
      }
    }
    def asVirallinenSuoritus(s: Suoritus): Option[VirallinenSuoritus] = {
      s match {
        case v: VirallinenSuoritus => Some(v)
        case _                     => None
      }
    }
    val isPerusopetus: Boolean = result
      .map(_.suoritus)
      .flatMap(asVirallinenSuoritus)
      .exists(suoritus => {
        if (opiskeluoikeus.tyyppi.isDefined) {
          Oids.perusopetusKomoOid == suoritus.komo && opiskeluoikeus.tyyppi
            .getOrElse(KoskiKoodi("", ""))
            .koodiarvo
            .contentEquals("perusopetus")
        } else {
          Oids.perusopetusKomoOid == suoritus.komo
        }
      })

    val hasNinthGrade: Boolean = result.exists(s => {
      val luokka = s.luokkataso
      luokka.contains("9") || s.luokka.startsWith("9")
    })

    //Postprocessing
    result = postprocessPeruskouluData(result)
    result = postProcessPOOData(result) //POO as in peruskoulun oppiaineen oppimäärä
    if (opiskeluoikeus.isKotiopetuslainen) {
      result = result.filter(sa =>
        sa.suoritus.tila.equals("VALMIS")
          && sa.suoritus.valmistuminen.isBefore(KoskiUtil.deadlineDate)
      )
    }
    //todo this doens't have to be a sort of post-processing for the result list, could be done prior with koski data
    if (isPerusopetus && !(hasNinthGrade || opiskeluoikeus.isKotiopetuslainen)) {
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
    val (oppiaineenOppimaarat, muut) = arvosanat.partition(sa =>
      sa.suoritus match {
        case v: VirallinenSuoritus =>
          v.komo.contentEquals(Oids.perusopetuksenOppiaineenOppimaaraOid)
        case _ => false
      }
    )

    val newSuoritukset = oppiaineenOppimaarat
      .groupBy(_.suoritus.myontaja)
      .map(entry => {
        val suoritukset = entry._2
        var suoritusArvosanatToBeSaved = suoritukset.head

        val allArvosanat: Set[Arvosana] = suoritukset.flatMap(_.arvosanat).toSet

        suoritukset.foreach(suoritusArvosanat => {
          val vs = suoritusArvosanat.suoritus
          if (vs.valmistuminen.isAfter(suoritusArvosanatToBeSaved.suoritus.valmistuminen)) {
            suoritusArvosanatToBeSaved = suoritusArvosanat
          }
        })
        suoritusArvosanatToBeSaved.copy(arvosanat = allArvosanat.toSeq)
      })

    muut ++ newSuoritukset
  }

  /**
    *  This basically hoists luokka data from SuoritusArvosanat objects that have komo of "luokka"
    *  This is necessary because the saving that happens above in the object doesn't save luokka komo data, instead
    *  it just saves the whole perusopetus komo that contains grades and such.
    */
  private def postprocessPeruskouluData(result: Seq[SuoritusArvosanat]): Seq[SuoritusArvosanat] = {
    result.map(suoritusArvosanat => {
      val useSuoritus = suoritusArvosanat.suoritus
      val useArvosanat =
        if (
          useSuoritus.komo.equals(Oids.perusopetusKomoOid) && suoritusArvosanat.arvosanat.isEmpty
        ) {
          result
            .filter(hs =>
              hs.suoritus match {
                case a: VirallinenSuoritus =>
                  a.henkilo.equals(useSuoritus.henkilo) &&
                    a.myontaja.equals(useSuoritus.myontaja) &&
                    a.komo.equals(Oids.perusopetusLuokkaKomoOid)
                case _ => false
              }
            )
            .filter(_.luokkataso.contains("9"))
            .flatMap(s => s.arvosanat)
        } else {
          suoritusArvosanat.arvosanat
        }

      var useLuokka = "" //Käytännössä vapaa tekstikenttä. Luokkatiedon "luokka".
      var useLuokkaAste = suoritusArvosanat.luokkataso
      var useLasnaDate = suoritusArvosanat.lasnadate

      val isNinthGrade = result.exists(_.luokkataso.getOrElse("").startsWith("9"))
      val isKotiopetus = result.exists(_.luokkataso.getOrElse("").equals("kotiopetus9"))
      val isPerusopetus = useSuoritus.komo.equals(Oids.perusopetusKomoOid)

      if ((isNinthGrade || isKotiopetus) && isPerusopetus) {
        useLuokka = result
          .find(_.luokkataso.getOrElse("").startsWith("9"))
          .map(l => l.luokka)
          .getOrElse("kotiopetus")
        useLuokkaAste = Some("9")
        useLasnaDate = result
          .find(_.suoritus match {
            case a: VirallinenSuoritus =>
              a.henkilo.equals(useSuoritus.henkilo) &&
                a.myontaja.equals(useSuoritus.myontaja) &&
                a.komo.equals(Oids.perusopetusLuokkaKomoOid)
            case _ => false
          })
          .filter(_.luokkataso.contains("9"))
          .map(s => s.lasnadate)
          .getOrElse(suoritusArvosanat.lasnadate) //fall back to this suoritus lasnadate

      } else {
        useLuokka = suoritusArvosanat.luokka
      }
      if (suoritusArvosanat.luokkataso.getOrElse("").equals(KoskiUtil.AIKUISTENPERUS_LUOKKAASTE)) {
        useLuokkaAste = Some("9")
        useLuokka = KoskiUtil.AIKUISTENPERUS_LUOKKAASTE + " " + suoritusArvosanat.luokka
      }
      SuoritusArvosanat(
        VirallinenSuoritus(
          komo = useSuoritus.komo,
          myontaja = useSuoritus.myontaja,
          tila = useSuoritus.tila,
          valmistuminen = useSuoritus.valmistuminen,
          henkilo = useSuoritus.henkilo,
          yksilollistaminen = useSuoritus.yksilollistaminen,
          suoritusKieli = useSuoritus.suoritusKieli,
          opiskeluoikeus = None,
          vahv = true,
          lahde = KoskiUtil.koski_integration_source,
          suoritustyyppi = useSuoritus.suoritustyyppi,
          lahdeArvot = useSuoritus.lahdeArvot
        ),
        useArvosanat,
        useLuokka,
        useLasnaDate,
        useLuokkaAste
      )
    })
  }
}

case class Valmistuminen(vuosi: Int, valmistumisPaiva: LocalDate, organisaatioOid: String)
