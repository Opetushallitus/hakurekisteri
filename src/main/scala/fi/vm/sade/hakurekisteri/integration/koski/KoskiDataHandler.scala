package fi.vm.sade.hakurekisteri.integration.koski

import java.util.UUID

import akka.actor.ActorRef
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri._
import fi.vm.sade.hakurekisteri.arvosana._
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaQuery}
import fi.vm.sade.hakurekisteri.storage.{DeleteResource, Identified, InsertResource}
import fi.vm.sade.hakurekisteri.suoritus._
import org.joda.time.format.DateTimeFormat
import org.joda.time.LocalDate
import org.json4s.DefaultFormats
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class SuoritusArvosanat(suoritus: Suoritus, arvosanat: Seq[Arvosana], luokka: String, lasnadate: LocalDate, luokkataso: Option[String]) {
  def peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus(henkilonSuoritukset: Seq[SuoritusArvosanat]): Boolean = {
    suoritus match {
      case v: VirallinenSuoritus =>
        v.komo.equals(Oids.perusopetusKomoOid) &&
          (henkilonSuoritukset.exists(_.luokkataso.getOrElse("").startsWith("9")) || luokkataso.getOrElse("").equals(KoskiUtil.AIKUISTENPERUS_LUOKKAASTE))
      case _ => false
    }
  }

}

object KoskiDataHandler {

  def parseLocalDate(s: String): LocalDate =
    if (s.length() > 10) {
      DateTimeFormat.forPattern("yyyy-MM-ddZ").parseLocalDate(s)
    } else {
      DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate(s)
    }
}

class KoskiDataHandler(suoritusRekisteri: ActorRef, arvosanaRekisteri: ActorRef, opiskelijaRekisteri: ActorRef)(implicit ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(getClass)

  implicit val timeout: Timeout = 2.minutes

  import scala.language.implicitConversions

  implicit val formats: DefaultFormats.type = DefaultFormats

  private val suoritusArvosanaParser = new KoskiSuoritusArvosanaParser
  private val opiskelijaParser = new KoskiOpiskelijaParser

  private def opiskeluoikeusSisaltaaYsisuorituksen(oo: KoskiOpiskeluoikeus): Boolean = {
    oo.suoritukset.exists(s => s.koulutusmoduuli.tunniste.isDefined && s.koulutusmoduuli.tunniste.get.koodiarvo.equals("9"))
  }

  private def getViimeisinOpiskeluoikeusjakso(oikeudet: Seq[KoskiOpiskeluoikeus]): Option[KoskiOpiskeluoikeus] = {
    val viimeisinLasnaJaEiEronnut =
      oikeudet.filter(oo => oo.tila.opiskeluoikeusjaksot.exists(_.tila.koodiarvo.equals("lasna"))
        && !oo.tila.opiskeluoikeusjaksot.exists(_.tila.koodiarvo.equals("eronnut")))
        .sortBy(_.tila.opiskeluoikeusjaksot.sortBy(_.alku).reverse.head.alku).reverse.headOption
    val viimeisinLasna =
      oikeudet.filter(_.tila.opiskeluoikeusjaksot.exists(_.tila.koodiarvo.equals("lasna")))
        .sortBy(_.tila.opiskeluoikeusjaksot.sortBy(_.alku).reverse.head.alku).reverse.headOption
    if (viimeisinLasnaJaEiEronnut.isDefined) {
      viimeisinLasnaJaEiEronnut
    } else {
      viimeisinLasna
    }
  }

  private def loytyykoHylattyja(suoritus: KoskiSuoritus): Boolean = {
    suoritus.osasuoritukset
      .filter(_.arviointi.nonEmpty)
      .exists(_.arviointi.head.hyväksytty.getOrElse(true) == false)
  }

  private def shouldSaveSuoritus(suoritus: KoskiSuoritus, opiskeluoikeus: KoskiOpiskeluoikeus): Boolean = {
    val komoOid: String = suoritus.getKomoOid(opiskeluoikeus.isAikuistenPerusopetus)
    komoOid match {
      case Oids.perusopetusKomoOid | Oids.lisaopetusKomoOid if opiskeluoikeus.tila.determineSuoritusTila.equals("KESKEN") => true
      case Oids.perusopetusKomoOid | Oids.lisaopetusKomoOid =>
        suoritus.vahvistus.isDefined || loytyykoHylattyja(suoritus)
      case Oids.lukioKomoOid if !(opiskeluoikeus.tila.determineSuoritusTila.eq("VALMIS") && suoritus.vahvistus.isDefined) => false
      case _ => true
    }
  }

  private def removeUnwantedValmas(henkiloOid: Option[String], opiskeluoikeus: KoskiOpiskeluoikeus): Boolean = {
    val keskeytynyt: KoskiTila => Boolean = koskiTila =>
      KoskiUtil.keskeytyneetTilat.contains(koskiTila.tila.koodiarvo)

    val alle30PisteenValma: KoskiSuoritus => Boolean = koskiSuoritus =>
      koskiSuoritus.tyyppi.exists(_.koodiarvo == "valma") &&
        !koskiSuoritus.opintopisteitaVahintaan(30)

    val isRemovable = opiskeluoikeus.tyyppi.get.koodiarvo.equals("ammatillinenkoulutus") &&
      opiskeluoikeus.tila.opiskeluoikeusjaksot.exists(keskeytynyt) &&
      opiskeluoikeus.suoritukset.exists(alle30PisteenValma)

    if (isRemovable) {
      logger.info("Oppijalla {} löytyi alle 30 opintopisteen valma-suoritus keskeytynyt-tilassa. Filtteröidään suoritus.",
        henkiloOid.getOrElse("(Tuntematon oppijanumero)"))
    }
    isRemovable
  }

  def ensureAinoastaanViimeisinOpiskeluoikeusJokaisestaTyypista(oikeudet: Seq[KoskiOpiskeluoikeus], henkiloOid: Option[String]): Seq[KoskiOpiskeluoikeus] = {
    var viimeisimmatOpiskeluoikeudet: Seq[KoskiOpiskeluoikeus] = Seq()
    //Poistetaan viimeisimmän opiskeluoikeuden päättelystä sellaiset peruskoulusuoritukset joilla ei ole ysiluokan suoritusta
    val oikeudetFiltered = oikeudet.filter(oo => !oo.tyyppi.get.koodiarvo.equals("perusopetus") || opiskeluoikeusSisaltaaYsisuorituksen(oo))
    //Opiskeluoikeuden tyypit eli perusopetus, perusopetuksen lisäopetus (10), lukiokoulutus, ammatillinen jne.
    val tyypit: Seq[String] = oikeudet.map(oikeus => {if (oikeus.tyyppi.isDefined) oikeus.tyyppi.get.koodiarvo else ""})
    tyypit.distinct.foreach(tyyppi => {
      val tataTyyppia: Seq[KoskiOpiskeluoikeus] = oikeudetFiltered.filter(oo => oo.tyyppi.isDefined && oo.tyyppi.get.koodiarvo.equals(tyyppi))
      //Aktiivisia ammatillisia opiskeluoikeuksia voi olla useita samaan aikaan, eikä kyseessä ole datavirhe.
      if (tyyppi.equals("ammatillinenkoulutus") && tataTyyppia.nonEmpty) {
        if (tataTyyppia.size > 1) {
          logger.info("Oppijalla {} on useampi ammatillinen opiskeluoikeus. Ei filtteröidä mitään niistä.", henkiloOid.getOrElse("(Tuntematon oppijanumero)"))
        }
        viimeisimmatOpiskeluoikeudet = viimeisimmatOpiskeluoikeudet ++ tataTyyppia
      } else {
        val viimeisin = getViimeisinOpiskeluoikeusjakso(tataTyyppia)
        if (viimeisin.isDefined) {
          viimeisimmatOpiskeluoikeudet = viimeisimmatOpiskeluoikeudet :+ viimeisin.get
        }
      }
    })
    // Poistetaan VALMA-suorituksista kaikki, joissa on alle 30 suorituspistettä ja tila on jokin seuraavista: "eronnut", "erotettu", "katsotaaneronneeksi" ,"mitatoity", "peruutettu".
    viimeisimmatOpiskeluoikeudet = viimeisimmatOpiskeluoikeudet.filterNot(oo => removeUnwantedValmas(henkiloOid, oo))
    // Filtteröidään opiskeluoikeuksista ei toivotut suoritukset
    viimeisimmatOpiskeluoikeudet.map { oo =>
      oo.copy(suoritukset = oo.suoritukset.filter(s => shouldSaveSuoritus(s, oo)))
    }
  }

  private def deleteArvosanatAndSuorituksetAndOpiskelija(suoritus: VirallinenSuoritus with Identified[UUID], henkilöOid: String) = {
    val arvosanojenPoisto = fetchArvosanat(suoritus).mapTo[Seq[Arvosana with Identified[UUID]]].map(_.foreach(a => deleteArvosana(a)))
    val suorituksenPoisto = deleteSuoritus(suoritus)
    val opiskelijanPoisto = fetchOpiskelijat(henkilöOid, suoritus.myontaja)
      .flatMap(opiskelijatiedot => opiskelijatiedot.size match {
        case 1 => logger.debug("Found one matching opiskelijatieto for suoritus, deleting it.")
          deleteOpiskelija(opiskelijatiedot.head)
        case _ => logger.warn("Multiple opiskelijas ({}) found for henkilöoid: ({}) while removing suoritus ({}), not deleting anything.", opiskelijatiedot.size.toString, henkilöOid, suoritus.id.toString)
          Future.successful("Ok with warnings")
      })
    Future.sequence(Seq(arvosanojenPoisto, suorituksenPoisto, opiskelijanPoisto)).onComplete {
      case Success(_) => logger.debug("Oppijan + " + suoritus.henkiloOid +" arvosanat, suoritukset ja opiskelijatieto onnistuneesti poistettu!", suoritus.henkiloOid)
        Future.successful({})
      case Failure(e) => logger.warn("Oppijan + " + suoritus.henkiloOid +" arvosanojen, suoritusten tai opiskelijatiedon poistossa oli ongelmia: " + e)
        Future.failed(e)
    }
  }

  private def fetchOpiskelijat(henkilöOid: String, oppilaitosOid: String): Future[Seq[Opiskelija with Identified[UUID]]] = {
    (opiskelijaRekisteri ? OpiskelijaQuery(henkilo = Some(henkilöOid), oppilaitosOid = Some(oppilaitosOid), source = Some(KoskiUtil.koski_integration_source))).mapTo[Seq[Opiskelija with Identified[UUID]]].recoverWith {
      case t: AskTimeoutException =>
        logger.error(s"Got timeout exception when fetching opiskelija: $henkilöOid , retrying", t)
        fetchOpiskelijat(henkilöOid, oppilaitosOid)
    }
  }

  def updateSuoritus(suoritus: VirallinenSuoritus with Identified[UUID], suor: VirallinenSuoritus): Future[VirallinenSuoritus with Identified[UUID]] =
    (suoritusRekisteri ? suoritus.copy(tila = suor.tila, valmistuminen = suor.valmistuminen, yksilollistaminen = suor.yksilollistaminen,
      suoritusKieli = suor.suoritusKieli)).mapTo[VirallinenSuoritus with Identified[UUID]].recoverWith{
      case t: AskTimeoutException => updateSuoritus(suoritus, suor)
    }

  private def deleteOpiskelija(o: Opiskelija with Identified[UUID]): Future[Any] = {
    logger.debug("Poistetaan opiskelija " + o + "UUID:lla " + o.id)
    opiskelijaRekisteri ? DeleteResource(o.id, "koski-opiskelijat")
  }

  private def saveOpiskelija(opiskelija: Opiskelija): Future[Any] = {
    logger.debug("saveOpiskelija={}", opiskelija)
    opiskelijaRekisteri ? opiskelija
  }

  private def saveSuoritus(suor: Suoritus, personOidsWithAliases: PersonOidsWithAliases): Future[Suoritus with Identified[UUID]] = {
    logger.debug("saveSuoritus={}", suor)
    (suoritusRekisteri ? InsertResource[UUID, Suoritus](suor, personOidsWithAliases)).mapTo[Suoritus with Identified[UUID]].recoverWith {
      case t: AskTimeoutException =>
        logger.error(s"Got timeout exception when saving suoritus $suor , retrying", t)
        saveSuoritus(suor, personOidsWithAliases)
    }
  }

  private def deleteSuoritus(s: Suoritus with Identified[UUID]): Future[Any] = {
    logger.debug("Poistetaan suoritus " + s + "UUID:lla" + s.id)
    suoritusRekisteri ? DeleteResource(s.id, "koski-suoritukset")
  }

  private def suoritusExists(suor: VirallinenSuoritus, suoritukset: Seq[Suoritus]): Boolean = suoritukset.exists {
    case s: VirallinenSuoritus => s.core == suor.core
    case _ => false
  }

  private def fetchExistingSuoritukset(henkiloOid: String, personOidsWithAliases: PersonOidsWithAliases): Future[Seq[Suoritus]] = {
    val q = SuoritusQuery(henkilo = Some(henkiloOid))
    val f: Future[Any] = suoritusRekisteri ? SuoritusQueryWithPersonAliases(q, personOidsWithAliases)
    f.mapTo[Seq[Suoritus]].recoverWith {
      case t: AskTimeoutException =>
        logger.error(s"Got timeout exception when fetching existing suoritukset for henkilo $henkiloOid , retrying", t)
        fetchExistingSuoritukset(henkiloOid, personOidsWithAliases)
    }
  }

  private def arvosanaForSuoritus(arvosana: Arvosana, s: Suoritus with Identified[UUID]): Arvosana = {
    arvosana.copy(suoritus = s.id)
  }

  private def saveArvosana(arvosana: Arvosana): Future[Any] = {
    logger.debug("saveArvosana={}", arvosana)
    arvosanaRekisteri ? arvosana
  }

  private def arvosanaToInsertResource(arvosana: Arvosana, suoritus: Suoritus with Identified[UUID], personOidsWithAliases: PersonOidsWithAliases) = {
    InsertResource[UUID, Arvosana](arvosanaForSuoritus(arvosana, suoritus), personOidsWithAliases)
  }

  private def saveSuoritusAndArvosanat(henkilöOid: String, existingSuoritukset: Seq[Suoritus], useSuoritus: VirallinenSuoritus, arvosanat: Seq[Arvosana], luokka: String, lasnaDate: LocalDate, luokkaTaso: Option[String], personOidsWithAliases: PersonOidsWithAliases): Future[Any] = {
    val opiskelija = opiskelijaParser.createOpiskelija(henkilöOid, SuoritusLuokka(useSuoritus, luokka, lasnaDate, luokkaTaso))
    if (suoritusExists(useSuoritus, existingSuoritukset)) {
      logger.debug("Päivitetään olemassaolevaa suoritusta.")
      val suoritus: VirallinenSuoritus with Identified[UUID] = existingSuoritukset.flatMap {
        case s: VirallinenSuoritus with Identified[UUID @unchecked] => Some(s)
        case _ => None
      }
        .find(s => s.henkiloOid == henkilöOid && s.myontaja == useSuoritus.myontaja && s.komo == useSuoritus.komo).get
      logger.debug("Käsitellään olemassaoleva suoritus " + suoritus)
      val newArvosanat = arvosanat.map(toArvosana(_)(suoritus.id)(KoskiUtil.koski_integration_source))

      updateSuoritus(suoritus, useSuoritus)
        .flatMap(_ => fetchArvosanat(suoritus))
        .flatMap(existingArvosanat => Future.sequence(existingArvosanat
          .filter(_.source.contentEquals(KoskiUtil.koski_integration_source))
          .map(arvosana => deleteArvosana(arvosana))))
        .flatMap(_ => Future.sequence(newArvosanat.map(saveArvosana)))
        .flatMap(_ => saveOpiskelija(opiskelija))
    } else {
      saveSuoritus(useSuoritus, personOidsWithAliases).flatMap(suoritus =>
        Future.sequence(arvosanat.map(a => arvosanaRekisteri ? arvosanaToInsertResource(a, suoritus, personOidsWithAliases)))
      ).flatMap(_ => saveOpiskelija(opiskelija))
    }
  }

  private def fetchArvosanat(s: VirallinenSuoritus with Identified[UUID]): Future[Seq[Arvosana with Identified[UUID]]] = {
    logger.debug("Haetaan arvosanat suoritukselle: " + s + ", id: " + s.id)
    (arvosanaRekisteri ? ArvosanaQuery(suoritus = s.id)).mapTo[Seq[Arvosana with Identified[UUID]]]
  }

  private def deleteArvosana(s: Arvosana with Identified[UUID]): Future[Any] = {
    logger.debug("Poistetaan arvosana " + s + "UUID:lla" + s.id)
    arvosanaRekisteri ? DeleteResource(s.id, "koski-arvosanat")
  }

  private def toArvosana(arvosana: Arvosana)(suoritus: UUID)(source: String): Arvosana =
    Arvosana(suoritus, arvosana.arvio, arvosana.aine, arvosana.lisatieto, arvosana.valinnainen, arvosana.myonnetty, source, Map(), arvosana.jarjestys)

  private def checkAndDeleteIfSuoritusDoesNotExistAnymoreInKoski(fetchedSuoritukset: Seq[Suoritus], henkilonSuoritukset: Seq[SuoritusArvosanat], henkilöOid: String): Unit = {
    // Only virallinen suoritus
    val koskiVirallisetSuoritukset: Seq[VirallinenSuoritus] = henkilonSuoritukset.map(h => h.suoritus).flatMap {
      case s: VirallinenSuoritus => Some(s)
      case _ => None
    }

    val fetchedVirallisetSuoritukset: Seq[VirallinenSuoritus with Identified[UUID]] = fetchedSuoritukset.filter(s => s.source.equals(KoskiUtil.koski_integration_source)).flatMap {
      case s: VirallinenSuoritus with Identified[UUID @unchecked] => Some(s)
      case _ => None
    }

    val toBeDeletedSuoritukset: Seq[VirallinenSuoritus with Identified[UUID]] = fetchedVirallisetSuoritukset.filterNot(s1 => koskiVirallisetSuoritukset.exists(s2 => s1.myontaja.equals(s2.myontaja) && s1.komo.equals(s2.komo)))
    toBeDeletedSuoritukset.foreach(suoritus => {
      logger.info("Found suoritus for henkilö " + henkilöOid + " from Suoritusrekisteri which is not found in Koski anymore " + suoritus.id + ". Deleting it")
      deleteArvosanatAndSuorituksetAndOpiskelija(suoritus, henkilöOid)
    })
  }

  private def overrideExistingSuorituksetWithNewSuorituksetFromKoski(henkilöOid: String, viimeisimmatSuoritukset: Seq[SuoritusArvosanat], personOidsWithAliases: PersonOidsWithAliases, params: KoskiSuoritusHakuParams): Future[Unit] = {
    fetchExistingSuoritukset(henkilöOid, personOidsWithAliases).flatMap(fetchedSuoritukset => {

      //OY-227 : Check and delete if there is suoritus which is not included on new suoritukset.
      checkAndDeleteIfSuoritusDoesNotExistAnymoreInKoski(fetchedSuoritukset, viimeisimmatSuoritukset, henkilöOid)
      var tallennettavatSuoritukset = viimeisimmatSuoritukset
      if (!params.saveLukio) {
        tallennettavatSuoritukset = tallennettavatSuoritukset.filterNot(s => s.suoritus.asInstanceOf[VirallinenSuoritus].komo.equals(Oids.lukioKomoOid))
      }
      if (!params.saveAmmatillinen) {
        tallennettavatSuoritukset = tallennettavatSuoritukset.filterNot(s => s.suoritus.asInstanceOf[VirallinenSuoritus].komo.equals(Oids.erikoisammattitutkintoKomoOid))
          .filterNot(s => s.suoritus.asInstanceOf[VirallinenSuoritus].komo.equals(Oids.ammatillinentutkintoKomoOid))
          .filterNot(s => s.suoritus.asInstanceOf[VirallinenSuoritus].komo.equals(Oids.ammatillinenKomoOid))
      }

      //NOTE, processes the Future that encloses the list, does not actually iterate through the list
      Future.sequence(tallennettavatSuoritukset.map {
        case s@SuoritusArvosanat(useSuoritus: VirallinenSuoritus, arvosanat: Seq[Arvosana], luokka: String, lasnaDate: LocalDate, luokkaTaso: Option[String]) =>
          //Suren suoritus = Kosken opiskeluoikeus + päättötodistussuoritus
          //Suren luokkatieto = Koskessa peruskoulun 9. luokan suoritus
          //todo tarkista, onko tämä vielä tarpeen, tai voisiko tätä ainakin muokata? Nyt tänne asti ei pitäisi tulla ei-ysejä peruskoululaisia.
          if (!useSuoritus.komo.equals(Oids.perusopetusLuokkaKomoOid) &&
            (s.peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus(viimeisimmatSuoritukset) || !useSuoritus.komo.equals(Oids.perusopetusKomoOid))) {
            saveSuoritusAndArvosanat(henkilöOid, fetchedSuoritukset, useSuoritus, arvosanat, luokka, lasnaDate, luokkaTaso, personOidsWithAliases)

          } else {
            Future.successful({})
          }
        case _ => Future.successful({})
      }).flatMap(_ => Future.successful({logger.info("Koski-suoritusten tallennus henkilölle " + henkilöOid + " valmis.")}))
    })
  }

  def createSuorituksetJaArvosanatFromKoski(henkilo: KoskiHenkiloContainer): Seq[Seq[SuoritusArvosanat]] = {
    val viimeisimmat = ensureAinoastaanViimeisinOpiskeluoikeusJokaisestaTyypista(henkilo.opiskeluoikeudet, henkilo.henkilö.oid)
    if (henkilo.opiskeluoikeudet.size > viimeisimmat.size) {
      logger.info("Filtteröitiin henkilöltä " + henkilo.henkilö.oid + " pois yksi tai useampia opiskeluoikeuksia. Ennen filtteröintiä: " + henkilo.opiskeluoikeudet.size + ", jälkeen: " + viimeisimmat.size)
    }
    suoritusArvosanaParser.getSuoritusArvosanatFromOpiskeluoikeudes(henkilo.henkilö.oid.getOrElse(""), viimeisimmat)
  }

  def processHenkilonTiedotKoskesta(koskihenkilöcontainer: KoskiHenkiloContainer,
                                    personOidsWithAliases: PersonOidsWithAliases,
                                    params: KoskiSuoritusHakuParams): Future[Any] = {

    koskihenkilöcontainer.henkilö.oid match {
      case Some(henkilöOid) => {
        val henkilonSuoritukset: Seq[SuoritusArvosanat] = createSuorituksetJaArvosanatFromKoski(koskihenkilöcontainer).flatten
          .filter(s => henkilöOid.equals(s.suoritus.henkiloOid))

        henkilonSuoritukset match {
          case Nil => Future.successful({})
          case _ => overrideExistingSuorituksetWithNewSuorituksetFromKoski(henkilöOid, henkilonSuoritukset, personOidsWithAliases, params)
        }
      }
      case None => Future.successful({})
    }
  }
}

case class SuoritusLuokka(suoritus: VirallinenSuoritus, luokka: String, lasnaDate: LocalDate, luokkataso: Option[String] = None)

case class MultipleSuoritusException(henkiloOid: String,
                                     myontaja: String,
                                     komo: String)
  extends Exception(s"Multiple suoritus found for henkilo $henkiloOid by myontaja $myontaja with komo $komo.")