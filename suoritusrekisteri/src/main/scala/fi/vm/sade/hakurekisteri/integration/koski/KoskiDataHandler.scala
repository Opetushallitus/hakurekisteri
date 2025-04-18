package fi.vm.sade.hakurekisteri.integration.koski

import akka.actor.ActorRef
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri._
import fi.vm.sade.hakurekisteri.arvosana._
import fi.vm.sade.hakurekisteri.integration.henkilo.{Henkilo, PersonOidsWithAliases}
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaQuery}
import fi.vm.sade.hakurekisteri.storage.{DeleteResource, Identified, InsertResource}
import fi.vm.sade.hakurekisteri.suoritus._
import org.joda.time.{DateTime, LocalDate, Period}
import org.json4s.DefaultFormats
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

case class SuoritusArvosanat(
  suoritus: VirallinenSuoritus,
  arvosanat: Seq[Arvosana],
  luokka: String,
  lasnadate: LocalDate,
  luokkataso: Option[String]
) {
  def peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus(
    henkilonSuoritukset: Seq[SuoritusArvosanat]
  ): Boolean = {
    suoritus.komo.equals(Oids.perusopetusKomoOid) &&
    (henkilonSuoritukset.exists(_.luokkataso.getOrElse("").startsWith("9")) ||
      luokkataso.getOrElse("").equals(KoskiUtil.AIKUISTENPERUS_LUOKKAASTE))
  }

}

class KoskiDataHandler(
  suoritusRekisteri: ActorRef,
  arvosanaRekisteri: ActorRef,
  opiskelijaRekisteri: ActorRef
)(implicit ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(getClass)

  implicit val timeout: Timeout = 2.minutes

  import scala.language.implicitConversions

  implicit val formats: DefaultFormats.type = DefaultFormats

  private val suoritusArvosanaParser = new KoskiSuoritusArvosanaParser
  private val opiskelijaParser = new KoskiOpiskelijaParser

  implicit val localDateOrdering: Ordering[LocalDate] = _ compareTo _

  private def shouldSaveSuoritus(
    henkiloOid: String,
    suoritus: KoskiSuoritus,
    opiskeluoikeus: KoskiOpiskeluoikeus
  ): Boolean = {
    val komoOid = suoritus.getKomoOid(opiskeluoikeus.isAikuistenPerusopetus)
    val lasnaDate = opiskeluoikeus.tila.findEarliestLasnaDate

    if (lasnaDate.isEmpty) {
      logger.info(
        s"Filtteröitiin henkilöltä $henkiloOid suoritus, josta ei löydy läsnäolon alkupäivämäärää (komoOid: $komoOid)."
      )
      return false
    }

    if (lasnaDate.exists(KoskiUtil.isAfterDeadlineDate)) {
      logger.info(
        s"Filtteröitiin henkilöltä $henkiloOid suoritus, jonka läsnäolon alkamispäivämäärä on deadlinen jälkeen (komoOid: $komoOid)."
      )
      return false
    }

    if (
      Oids.lukioKomoOid == komoOid && !(opiskeluoikeus.tila.determineSuoritusTila == "VALMIS" && suoritus.vahvistus.isDefined)
    ) {
      logger.info(s"Filtteröitiin henkilöltä $henkiloOid ei (valmis ja vahvistettu) lukiosuoritus.")
      return false
    }

    if (
      Oids.perusopetusKomoOid == komoOid && suoritus
        .isErityinentutkinto() && !(opiskeluoikeus.tila.determineSuoritusTila == "VALMIS" && suoritus.vahvistus.isDefined)
    ) {
      logger.info(
        s"Filtteröitiin henkilöltä $henkiloOid ei (valmis ja vahvistettu) perusopetuksen suoritus joka on tyyppiä erityinen tutkinto koska se ei ole valmis."
      )
      return false
    }

    if (
      suoritus.tyyppi.exists(_.koodiarvo == "ammatillinentutkinto") && suoritus.vahvistus.isEmpty
    ) {
      logger.info(
        s"Filtteröitiin henkilöltä $henkiloOid vahvistamaton ammatillisen tutkinnon suoritus."
      )
      return false
    }

    if (
      suoritus.tyyppi.exists(_.koodiarvo == "valma")
      && !suoritus.laajuusVahintaan(30)
      && opiskeluoikeus.tila.opiskeluoikeusjaksot
        .exists(ooj => KoskiUtil.eiHalututAlleVaaditunPistemaaranTilat.contains(ooj.tila.koodiarvo))
    ) {
      logger.info(
        s"Filtteröitiin henkilöltä $henkiloOid valma-suoritus, joka sisälsi alle 30 osp ja " +
          s"kuului eronneeseen rinnastettaviin tiloihin tai oli valmis."
      )
      return false
    }

    if (
      suoritus.isOpistovuosi()
      && !suoritus.laajuusVahintaan(26.5)
      && opiskeluoikeus.tila.opiskeluoikeusjaksot
        .exists(ooj => KoskiUtil.eiHalututAlleVaaditunPistemaaranTilat.contains(ooj.tila.koodiarvo))
    ) {
      logger.info(
        s"Filtteröitiin henkilöltä $henkiloOid opistovuosi oppivelvollisille-suoritus, joka sisälsi alle 26,5 osp ja " +
          s"kuului eronneeseen rinnastettaviin tiloihin tai oli valmis."
      )
      return false
    }

    if (suoritus.isTuva()) {
      if (
        !suoritus.laajuusVahintaan(19)
        && opiskeluoikeus.tila.opiskeluoikeusjaksot
          .exists(ooj =>
            KoskiUtil.eiHalututAlleVaaditunPistemaaranTilat.contains(ooj.tila.koodiarvo)
          )
      ) {
        logger.info(
          s"Filtteröitiin henkilöltä $henkiloOid tuva-suoritus, joka sisälsi alle 19 opintoviikkoa.ja " +
            s"kuului eronneeseen rinnastettaviin tiloihin tai oli valmis."
        )
        return false
      }
      if (!suoritus.laajuusVahintaan(19) && KoskiUtil.isBeforeTuvaStartDate(lasnaDate.get)) {
        logger.info(
          s"Filtteröitiin henkilöltä $henkiloOid keskeneräinen tuva-suoritus, joka on alkanut ennen viime vuoden tammikuun ensimmäistä päivää."
        )
        return false
      }
    }

    true
  }

  private def shouldSaveOpiskeluoikeus(
    henkiloOid: String,
    opiskeluoikeus: KoskiOpiskeluoikeus
  ): Boolean = {
    if (opiskeluoikeus.suoritukset.isEmpty) {
      logger.info(
        s"Filtteröitiin henkilöltä $henkiloOid opiskeluoikeus ${opiskeluoikeus.oid}, joka ei sisällä suorituksia."
      )
      return false
    }

    if (
      opiskeluoikeus.tyyppi.exists(
        _.koodiarvo == "perusopetus"
      ) && !opiskeluoikeus.opiskeluoikeusSisaltaaYsisuorituksen
    ) {
      if (opiskeluoikeus.isKotiopetuslainen) {
        logger.info(
          s"Ei filtteröity henkilöltä $henkiloOid ysiluokatonta perusopetuksen opiskeluoikeutta ${opiskeluoikeus.oid}, " +
            s"koska oo sisälsi kotiopetusjakson."
        )
        return true
      } else if (opiskeluoikeus.opiskeluoikeusSisaltaaErityisentutkinnon) {
        logger.info(
          s"Ei filtteröity henkilöltä $henkiloOid ysiluokatonta perusopetuksen opiskeluoikeutta ${opiskeluoikeus.oid}, " +
            s"koska oo sisälsi erityisen tutkinnon."
        )
        return true
      } else if (opiskeluoikeus.opiskeluoikeusSisaltaaPerusopetuksenOppiaineenOppimaaran) {
        logger.info(
          s"Ei filtteröity henkilöltä $henkiloOid ysiluokatonta perusopetuksen opiskeluoikeutta ${opiskeluoikeus.oid}, " +
            s"koska oo sisälsi perusopetuksen oppiaineen oppimäärän."
        )
        return true
      } else {
        logger.info(
          s"Filtteröitiin henkilöltä $henkiloOid perusopetuksen opiskeluoikeus ${opiskeluoikeus.oid}, joka ei sisällä 9. luokan suoritusta."
        )
        return false
      }
    }
    true
  }

  private def viimeisinOpiskeluoikeus(
    oikeudet: Seq[KoskiOpiskeluoikeus]
  ): Option[(KoskiOpiskeluoikeus, Seq[KoskiOpiskeluoikeus])] = {
    val (eronnut, eiEronnut) = oikeudet
      .sortBy(_.tila.opiskeluoikeusjaksot.map(_.alku).max)(Ordering[String].reverse)
      .partition(
        _.tila.opiskeluoikeusjaksot.exists(jakso =>
          KoskiUtil.eronneeseenRinnastettavatKoskiTilat.contains(jakso.tila.koodiarvo)
        )
      )
    eiEronnut.headOption
      .map((_, eiEronnut.tail ++ eronnut))
      .orElse(eronnut.headOption.map((_, eronnut.tail)))
  }

  def halututOpiskeluoikeudetJaSuoritukset(
    henkiloOid: String,
    opiskeluoikeudet: Seq[KoskiOpiskeluoikeus]
  ): Seq[KoskiOpiskeluoikeus] = {
    opiskeluoikeudet
      .map(o => o.copy(suoritukset = o.suoritukset.filter(shouldSaveSuoritus(henkiloOid, _, o))))
      .filter(shouldSaveOpiskeluoikeus(henkiloOid, _))
      .groupBy(_.tyyppi.map(_.koodiarvo))
      .flatMap {
        case (None, _) =>
          Seq()
        case (Some("ammatillinenkoulutus"), os) =>
          os
        case (Some("aikuistenperusopetus"), os) =>
          val (kokonaisetOppimaarat, muut) = os.partition(_.isAikuistenPerusopetuksenOppimaara)
          val oppiaineenOppimaarat = muut.filter(_.hasPerusopetuksenOppiaineenOppimaara)
          (viimeisinOpiskeluoikeus(kokonaisetOppimaarat), oppiaineenOppimaarat) match {
            case (Some(viimeisinKokonainen), oppiaineet) =>
              if (viimeisinKokonainen._2.nonEmpty) {
                logger.info(
                  s"Filtteröitiin henkilöltä $henkiloOid aikuisten perusopetuksen opiskeluoikeuksia (${viimeisinKokonainen._2
                    .map(_.oid)}), koska löytyi tuoreempi opiskeluoikeus oidilla ${viimeisinKokonainen._1.oid}"
                )
              }
              oppiaineet :+ viimeisinKokonainen._1
            case (None, oppiaineet) =>
              oppiaineet
            case _ => Seq()
          }
        case (Some(_), os: Seq[KoskiOpiskeluoikeus]) =>
          // ei huomioida oppiaineen arvosanakorotuksia viimeisintä opiskeluoikeutta poimiessa
          val (nuortenPerusopetuksenOppiaineenOppimaara, muutOppimaarat) =
            os.partition(_.hasNuortenPerusopetuksenOppiaineenOppimaara)
          viimeisinOpiskeluoikeus(muutOppimaarat) match {
            case Some((viimeisin, muut)) =>
              (muut.filter(
                _.suoritukset
                  .exists(_.tyyppi.exists(_.koodiarvo == "perusopetuksenoppiaineenoppimaara"))
              ) :+ viimeisin) ++ nuortenPerusopetuksenOppiaineenOppimaara
            case None =>
              Seq()
          }
      }
      .toSeq
  }

  private def deleteArvosanatAndSuorituksetAndOpiskelija(
    suoritus: VirallinenSuoritus with Identified[UUID],
    henkilöOid: String
  ): Future[Unit] = {
    val arvosanojenPoisto: Future[Unit] = fetchArvosanat(suoritus)
      .mapTo[Seq[Arvosana with Identified[UUID]]]
      .map(_.foreach(a => deleteArvosana(a)))
    val suorituksenPoisto: Future[Any] = deleteSuoritus(suoritus)
    val opiskelijanPoisto: Future[Any] = fetchOpiskelijat(henkilöOid, suoritus.myontaja)
      .flatMap(opiskelijatiedot =>
        opiskelijatiedot.size match {
          case 1 =>
            deleteOpiskelija(opiskelijatiedot.head)
          case _ =>
            logger.warn(
              "Multiple opiskelijas ({}) found for henkilöoid: ({}) while removing suoritus ({}), not deleting anything.",
              opiskelijatiedot.size.toString,
              henkilöOid,
              suoritus.id.toString
            )
            Future.successful("Ok with warnings")
        }
      )
    Future.sequence(Seq(arvosanojenPoisto, suorituksenPoisto, opiskelijanPoisto)).map {
      case e: Exception =>
        logger.warn(
          "Oppijan + " + suoritus.henkiloOid + " arvosanojen, suoritusten tai opiskelijatiedon poistossa oli ongelmia: " + e
        )
        Future.failed(e)
      case _ =>
        logger.debug(
          "Oppijan + " + suoritus.henkiloOid + " arvosanat, suoritukset ja opiskelijatieto onnistuneesti poistettu!",
          suoritus.henkiloOid
        )
        Future.successful({})
    }
  }

  private def fetchOpiskelijat(
    henkilöOid: String,
    oppilaitosOid: String
  ): Future[Seq[Opiskelija with Identified[UUID]]] = {
    (opiskelijaRekisteri ? OpiskelijaQuery(
      henkilo = Some(henkilöOid),
      oppilaitosOid = Some(oppilaitosOid),
      source = Some(KoskiUtil.koski_integration_source)
    )).mapTo[Seq[Opiskelija with Identified[UUID]]].recoverWith { case t: AskTimeoutException =>
      logger.error(s"Got timeout exception when fetching opiskelija: $henkilöOid , retrying", t)
      fetchOpiskelijat(henkilöOid, oppilaitosOid)
    }
  }

  def updateSuoritus(
    suoritus: VirallinenSuoritus with Identified[UUID],
    suor: VirallinenSuoritus
  ): Future[VirallinenSuoritus with Identified[UUID]] =
    (suoritusRekisteri ? suoritus.copy(
      tila = suor.tila,
      valmistuminen = suor.valmistuminen,
      yksilollistaminen = suor.yksilollistaminen,
      suoritusKieli = suor.suoritusKieli,
      lahdeArvot = suor.lahdeArvot
    )).mapTo[VirallinenSuoritus with Identified[UUID]].recoverWith { case t: AskTimeoutException =>
      updateSuoritus(suoritus, suor)
    }

  private def deleteOpiskelija(o: Opiskelija with Identified[UUID]): Future[Any] = {
    logger.debug("Poistetaan opiskelija " + o + "UUID:lla " + o.id)
    opiskelijaRekisteri ? DeleteResource(o.id, "koski-opiskelijat")
  }

  private def saveOpiskelija(opiskelija: Option[Opiskelija]): Future[Any] = {
    if (!opiskelija.isEmpty) {
      opiskelijaRekisteri ? opiskelija.get
    } else Future.successful({})
  }

  private def saveSuoritus(
    suor: Suoritus,
    personOidsWithAliases: PersonOidsWithAliases
  ): Future[Suoritus with Identified[UUID]] = {
    (suoritusRekisteri ? InsertResource[UUID, Suoritus](suor, personOidsWithAliases))
      .mapTo[Suoritus with Identified[UUID]]
      .recoverWith { case t: AskTimeoutException =>
        logger.error(s"Got timeout exception when saving suoritus $suor , retrying", t)
        saveSuoritus(suor, personOidsWithAliases)
      }
  }

  private def deleteSuoritus(s: Suoritus with Identified[UUID]): Future[Any] = {
    logger.debug("Poistetaan suoritus " + s + "UUID:lla" + s.id)
    suoritusRekisteri ? DeleteResource(s.id, "koski-suoritukset")
  }

  private def suoritusExists(
    suor: VirallinenSuoritus,
    suoritukset: Seq[Suoritus],
    aliases: Set[String]
  ): Boolean = suoritukset.exists {
    case s: VirallinenSuoritus => {
      s.core.isEqualWithAliases(suor.core, aliases)
    }
    case _ => false
  }

  private def fetchExistingSuoritukset(
    henkiloOid: String,
    personOidsWithAliases: PersonOidsWithAliases
  ): Future[Seq[Suoritus]] = {
    val q = SuoritusQuery(henkilo = Some(henkiloOid))
    val f: Future[Any] =
      suoritusRekisteri ? SuoritusQueryWithPersonAliases(q, personOidsWithAliases)
    f.mapTo[Seq[Suoritus]].recoverWith { case t: AskTimeoutException =>
      logger.error(
        s"Got timeout exception when fetching existing suoritukset for henkilo $henkiloOid , retrying",
        t
      )
      fetchExistingSuoritukset(henkiloOid, personOidsWithAliases)
    }
  }

  private def saveArvosana(arvosana: Arvosana): Future[Any] = {
    (arvosanaRekisteri ? arvosana).recoverWith { case t: AskTimeoutException =>
      logger.error(
        s"Operation timed out when saving arvosana for suoritus ${arvosana.suoritus}, retrying",
        t
      )
      saveArvosana(arvosana)
    }
  }

  private def getAliases(personOidsWithAliases: PersonOidsWithAliases): Set[String] = {
    // assuming that there is only one personOid in the structure
    personOidsWithAliases.aliasesByPersonOids.values.head
  }

  private def saveSuoritusAndArvosanat(
    henkilöOid: String,
    existingSuoritukset: Seq[Suoritus],
    useSuoritus: VirallinenSuoritus,
    arvosanat: Seq[Arvosana],
    luokka: String,
    lasnaDate: LocalDate,
    luokkaTaso: Option[String],
    personOidsWithAliases: PersonOidsWithAliases
  ): Future[SuoritusArvosanat] = {

    val aliases = getAliases(personOidsWithAliases)

    if (suoritusExists(useSuoritus, existingSuoritukset, aliases)) {
      val suoritus: VirallinenSuoritus with Identified[UUID] = existingSuoritukset
        .flatMap {
          case s: VirallinenSuoritus with Identified[UUID @unchecked] => Some(s)
          case _                                                      => None
        }
        .find(s =>
          (aliases contains s.henkiloOid) && (aliases contains henkilöOid) && s.myontaja == useSuoritus.myontaja && s.komo == useSuoritus.komo
        )
        .get
      logger.debug(
        s"Käsitellään olemassaoleva suoritus $suoritus henkiloid=${suoritus.henkiloOid}, uusi oid (alias, ignore if different) = $henkilöOid"
      )
      val opiskelija: Option[Opiskelija] = opiskelijaParser.createOpiskelija(
        suoritus.henkiloOid,
        SuoritusLuokka(useSuoritus, luokka, lasnaDate, luokkaTaso)
      )

      val arvosanasFromKoski =
        arvosanat.map(toArvosana(_)(suoritus.id)(KoskiUtil.koski_integration_source))
      updateSuoritus(suoritus, useSuoritus)
        .flatMap(_ => fetchArvosanat(suoritus))
        .flatMap(arvosanasInSure => syncArvosanas(arvosanasInSure, arvosanasFromKoski))
        .flatMap(_ => saveOpiskelija(opiskelija))
        .map(_ => SuoritusArvosanat(useSuoritus, arvosanasFromKoski, luokka, lasnaDate, luokkaTaso))
    } else {
      logger.debug(
        s"Käsitellään uusi suoritus $useSuoritus, personOidsWithAliases=$personOidsWithAliases"
      )
      val opiskelija: Option[Opiskelija] = opiskelijaParser.createOpiskelija(
        henkilöOid,
        SuoritusLuokka(useSuoritus, luokka, lasnaDate, luokkaTaso)
      )
      saveSuoritus(useSuoritus, personOidsWithAliases)
        .flatMap(suoritus => {
          val newArvosanat =
            arvosanat.map(toArvosana(_)(suoritus.id)(KoskiUtil.koski_integration_source))
          Future.sequence(newArvosanat.map(saveArvosana))
        })
        .flatMap(_ => saveOpiskelija(opiskelija))
        .map(_ => SuoritusArvosanat(useSuoritus, arvosanat, luokka, lasnaDate, luokkaTaso))
    }
  }

  private def syncArvosanas(
    existingArvosanas: Seq[Arvosana with Identified[UUID]],
    arvosanasFromKoski: Seq[Arvosana]
  ): Future[Any] = {
    Future
      .sequence(arvosanasFromKoski.map { koskiArvosana =>
        val matchingExistingArvosana = existingArvosanas.find(sureArvosana =>
          sureArvosana.koskiCore.equals(koskiArvosana.koskiCore)
        )
        if (matchingExistingArvosana.isDefined) {
          val existingArvosana = matchingExistingArvosana.get
          if (!existingArvosana.koskiUpdateableFields.equals(koskiArvosana.koskiUpdateableFields)) {
            logger.debug(
              s"KSK-5: Päivitetään muuttunut arvosana. Vanha {}, uusi {}. Suoritus: {}",
              existingArvosana,
              koskiArvosana,
              existingArvosana.suoritus
            )
            updateArvosana(existingArvosana, koskiArvosana)
          } else {
            //Arvosana jo olemassa, ei muutoksia
            Future.successful({})
          }
        } else {
          saveArvosana(koskiArvosana)
        }
      })
      .flatMap(_ => removeArvosanasNotPresentInKoski(existingArvosanas, arvosanasFromKoski))
  }

  private def removeArvosanasNotPresentInKoski(
    arvosanasInSure: Seq[Arvosana with Identified[UUID]],
    koskiArvosanas: Seq[Arvosana]
  ) = {
    Future.sequence(
      arvosanasInSure.map(existingArvosana =>
        if (
          !koskiArvosanas
            .exists(newArvosana => existingArvosana.koskiCore.equals(newArvosana.koskiCore))
        ) {
          logger.debug(
            "KSK-5: Vanhaa arvosanaa ei löydy enää Koskesta. Poistetaan {}.",
            existingArvosana
          )
          arvosanaRekisteri ? DeleteResource(existingArvosana.id, "koski-arvosanat")
        } else {
          Future.successful({})
        }
      )
    )
  }

  private def updateArvosana(
    oldArvosana: Arvosana with Identified[UUID],
    newArvosana: Arvosana
  ): Future[Any] = {
    (arvosanaRekisteri ? oldArvosana.copy(
      arvio = newArvosana.arvio,
      lahdeArvot = newArvosana.lahdeArvot,
      source = newArvosana.source,
      myonnetty = newArvosana.myonnetty
    ))
      .mapTo[Arvosana with Identified[UUID]]
      .recoverWith { case t: AskTimeoutException =>
        logger.error(
          s"Operation timed out when updating arvosana for suoritus ${newArvosana.suoritus}, retrying",
          t
        )
        updateArvosana(oldArvosana, newArvosana)
      }
  }

  private def fetchArvosanat(
    s: VirallinenSuoritus with Identified[UUID]
  ): Future[Seq[Arvosana with Identified[UUID]]] = {
    (arvosanaRekisteri ? ArvosanaQuery(suoritus = s.id)).mapTo[Seq[Arvosana with Identified[UUID]]]
  }

  private def deleteArvosana(s: Arvosana with Identified[UUID]): Future[Any] = {
    arvosanaRekisteri ? DeleteResource(s.id, "koski-arvosanat")
  }

  private def toArvosana(arvosana: Arvosana)(suoritus: UUID)(source: String): Arvosana =
    Arvosana(
      suoritus,
      arvosana.arvio,
      arvosana.aine,
      arvosana.lisatieto,
      arvosana.valinnainen,
      arvosana.myonnetty,
      source,
      Map(),
      arvosana.jarjestys
    )

  private def checkAndDeleteIfSuoritusDoesNotExistAnymoreInKoski(
    fetchedSuoritukset: Seq[Suoritus],
    henkilonSuoritukset: Seq[SuoritusArvosanat],
    henkilöOid: String,
    aliases: Set[String]
  ): Future[Seq[Unit]] = {
    Future.sequence(
      fetchedSuoritukset
        .collect {
          case s: VirallinenSuoritus with Identified[UUID @unchecked]
              if s.source == KoskiUtil.koski_integration_source && !henkilonSuoritukset
                .exists(_.suoritus.core.isEqualWithAliases(s.core, aliases)) =>
            val lastmodified =
              s.lahdeArvot.getOrElse("last modified", System.currentTimeMillis().toString).toLong
            if (KoskiUtil.shouldSuoritusBeRemoved(lastmodified)) {
              logger.info(
                "AA Found suoritus for henkilö " + henkilöOid + " from Suoritusrekisteri which is not found in Koski anymore " + s.id + ". Deleting it"
              )
              deleteArvosanatAndSuorituksetAndOpiskelija(s, henkilöOid)
            } else {
              logger.info(
                "Found a fresh suoritus from henkilö " + henkilöOid + " with id " + s.id + ", not removing"
              )
            }
            Future.successful({})
        }
    )
  }

  private def overrideExistingSuorituksetWithNewSuorituksetFromKoski(
    henkilo: Option[Henkilo],
    koskiHenkiloContainer: KoskiHenkiloContainer,
    henkiloOid: String,
    viimeisimmatSuoritukset: Seq[SuoritusArvosanat],
    personOidsWithAliases: PersonOidsWithAliases,
    params: KoskiSuoritusHakuParams
  ): Future[Seq[Either[Exception, Option[SuoritusArvosanat]]]] = {

    fetchExistingSuoritukset(henkiloOid, personOidsWithAliases).flatMap(fetchedSuoritukset => {
      //OY-227 : Check and delete if there is suoritus which is not included on new suoritukset.
      var tallennettavatSuoritukset = viimeisimmatSuoritukset

      // Tarkistetaan onko henkilön Koskessa tulevissa suorituksissa tai jo kannassa olevissa
      // suorituksissa valmis ja vahvistettu perusopetuksen suoritus.
      //
      // Suoritusrekisterin kannassa jo olevista huomiodaan vain sellaiset jotka eivät ole tulleet
      // Koskesta, koska sellaisten tulee löytyä yhä Koskesta tulevista suorituksista ja ne poistuvat
      // jos suoritusta ei enää tulekaan Koskesta.
      val hasValmisPerusopetuksenSuoritus = tallennettavatSuoritukset.exists(s =>
        Oids.perusopetusKomoOid.contains(s.suoritus.komo)
          && s.suoritus.vahv
          && s.suoritus.tila == "VALMIS"
      ) || fetchedSuoritukset
        .map(_.asInstanceOf[VirallinenSuoritus])
        .exists(s =>
          Oids.perusopetusKomoOid.contains(s.komo)
            && s.vahvistettu
            && s.source != KoskiUtil.koski_integration_source
            && s.tila == "VALMIS"
        )

      // Ei tallenneta perusopetuksen oppiaineen oppimäärän suorituksia
      // ellei henkilöllä ole myös valmista ja vahvistettua perusopetuksen suoritusta
      if (!hasValmisPerusopetuksenSuoritus) {
        tallennettavatSuoritukset = tallennettavatSuoritukset.filterNot(s =>
          Oids.perusopetuksenOppiaineenOppimaaraOid.contains(s.suoritus.komo)
        )
      }
      // Ei tallenneta peruskoulun oppiaineen oppimäärän suorituksia joilla ei ole arvosanaa
      tallennettavatSuoritukset = tallennettavatSuoritukset.filterNot(s =>
        Oids.perusopetuksenOppiaineenOppimaaraOid.contains(s.suoritus.komo) && s.arvosanat.isEmpty
      )

      // Tallennetaan mahdollinen 7-8-valmistava vain alaikäiselle ja
      // silloin, kun valmista ja vahvistettua perusopetuksen suoritusta ei ollut.
      val tallennaSeiskaKasiValmistava =
        tallennettavatSuoritukset.isEmpty && params.saveSeiskaKasiJaValmistava && isAlaikainen(
          henkilo
        )

      val suorituksetForRemoving = tallennettavatSuoritukset

      if (!params.saveLukio) {
        tallennettavatSuoritukset =
          tallennettavatSuoritukset.filterNot(s => s.suoritus.komo.equals(Oids.lukioKomoOid))
      }
      if (!params.saveAmmatillinen) {
        tallennettavatSuoritukset = tallennettavatSuoritukset.filterNot(s =>
          Oids.ammatillisetKomoOids contains s.suoritus.komo
        )
      }

      // Kludge jotta saadaan jatkettua suoritusta ilman poikkeusta oikealla paluuarvolla
      // ja pidettyä virhe tallessa
      val seiskaKasiValmistavaResult = if (tallennaSeiskaKasiValmistava) {
        updateOppilaitosSeiskaKasiJaValmistava(koskiHenkiloContainer)
          .flatMap { case _ =>
            Future.successful(Seq.empty[Either[Exception, Option[SuoritusArvosanat]]])
          }
          .recover { case e: Exception =>
            logger.error(
              s"Koski-opiskelijan luokkatietojen päivitys 7/8/valmistava-luokan henkilölle $henkiloOid epäonnistui.",
              e
            )
            // Virhe talteen mutta ei propagoida poikkeusta
            Seq(
              Left(
                new RuntimeException(
                  s"Koski-opiskelijan luokkatietojen päivitys 7/8/valmistava-luokan henkilölle $henkiloOid epäonnistui.",
                  e
                )
              )
            )
          }
      } else {
        Future.successful(Seq.empty[Either[Exception, Option[SuoritusArvosanat]]])
      }

      seiskaKasiValmistavaResult.flatMap { seiskaKasiResult =>
        // Jos tuli aikaisempi tallennusvirhe, lopetetaan tähän
        if (seiskaKasiResult.exists(_.isLeft)) {
          Future.successful(seiskaKasiResult)
        } else {
          // Käsitellään suoritukset
          checkAndDeleteIfSuoritusDoesNotExistAnymoreInKoski(
            fetchedSuoritukset,
            suorituksetForRemoving,
            henkiloOid,
            getAliases(personOidsWithAliases)
          ).recoverWith { case e: Exception =>
            Future.successful(
              Seq(
                Left(
                  new RuntimeException(
                    s"Koski-opiskelijan poisto henkilölle $henkiloOid epäonnistui.",
                    e
                  )
                )
              )
            )
          }.flatMap { _ =>
            Future.sequence(tallennettavatSuoritukset.map {
              case s @ SuoritusArvosanat(
                    useSuoritus: VirallinenSuoritus,
                    arvosanat: Seq[Arvosana],
                    luokka: String,
                    lasnaDate: LocalDate,
                    luokkaTaso: Option[String]
                  ) =>
                try {
                  //Suren suoritus = Kosken opiskeluoikeus + päättötodistussuoritus
                  //Suren luokkatieto = Koskessa peruskoulun 9. luokan suoritus
                  if (
                    !useSuoritus.komo.equals(Oids.perusopetusLuokkaKomoOid) &&
                    (s.peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus(
                      viimeisimmatSuoritukset
                    ) || !useSuoritus.komo.equals(Oids.perusopetusKomoOid))
                  ) {
                    saveSuoritusAndArvosanat(
                      henkiloOid,
                      fetchedSuoritukset,
                      useSuoritus,
                      arvosanat,
                      luokka,
                      lasnaDate,
                      luokkaTaso,
                      personOidsWithAliases
                    ).map((x: SuoritusArvosanat) => Right(Some(x)))
                  } else {
                    Future.successful(Right(None))
                  }
                } catch {
                  case e: Exception =>
                    Future.successful(
                      Left(
                        new RuntimeException(
                          s"Koski-suoritusarvosanojen $s tallennus henkilölle $henkiloOid epäonnistui.",
                          e
                        )
                      )
                    )
                }
              case _ => Future.successful(Right(None))
            })
          }
        }
      }
    })
  }

  def createSuorituksetJaArvosanatFromKoski(
    henkilo: KoskiHenkiloContainer
  ): Seq[Seq[SuoritusArvosanat]] = {
    val henkiloOid = henkilo.henkilö.oid.get
    suoritusArvosanaParser.getSuoritusArvosanatFromOpiskeluoikeudes(
      henkiloOid,
      halututOpiskeluoikeudetJaSuoritukset(henkiloOid, henkilo.opiskeluoikeudet)
    )
  }

  //Kerätään kaikkien aineiden korkeimmat arvosanat
  private def combineArvosanasFromMultipleSuoritukses(
    suoritusArvosanat: Seq[SuoritusArvosanat]
  ): Seq[Arvosana] = {
    val kaikkiArvosanat = suoritusArvosanat.flatMap(s => s.arvosanat)
    val arvosanatByAine: Map[String, Seq[Arvosana]] = kaikkiArvosanat.groupBy(a => a.aine)
    val aineidenKorkeimmatArvosanat = arvosanatByAine.values
      .map((arvosanat: Seq[Arvosana]) =>
        arvosanat.maxBy(arvosana =>
          arvosana.arvio match {
            case a: Arvio410 => a.arvosana
            case _           => "0"
          }
        )
      )
    aineidenKorkeimmatArvosanat.toSeq
  }

  private def filterAndLogSuoritusDuplicates(
    suoritukset: Seq[SuoritusArvosanat]
  ): Seq[SuoritusArvosanat] = {

    suoritukset
      .groupBy(_.suoritus.core)
      .map((s: (VirallinenSisalto, Seq[SuoritusArvosanat])) => {
        var latest = s._2.maxBy(s => s.lasnadate)
        if (s._2.size > 1) {
          if (latest.suoritus.komo.equals(Oids.perusopetuksenOppiaineenOppimaaraOid)) {
            val yhdistetytArvosanat = combineArvosanasFromMultipleSuoritukses(s._2)
            logger.warn(
              s"Henkilön ${s._1.henkilo} Koskitiedoista syntyi useita samoja perusopetuksen oppiaineen oppimäärän suorituksia. " +
                s"Kerätään niistä arvosanat yhden suorituksen alle, tallennetaan kunkin aineen korkein löytyvä arvosana. Kaikki arvosanat ${s._2
                  .flatMap(sa => sa.arvosanat)}, yhdistetyt arvosanat: ${yhdistetytArvosanat.toList}"
            )
            latest = latest.copy(arvosanat = yhdistetytArvosanat)
          } else {
            logger.warn(
              s"Henkilön ${s._1.henkilo} Koskitiedoista syntyi useita samoja " +
                s"suorituksia komolle ${s._1.komo}. Tallennetaan vain niistä tuorein, " +
                s"lasnaDate ${latest.lasnadate}. Vanhin ${s._2.minBy(s => s.lasnadate).lasnadate}."
            )
          }
        }
        latest
      })
      .toSeq
  }

  def isAlaikainen(henkilo: Option[Henkilo]) = {
    henkilo
      .flatMap(_.syntymaaika)
      .exists(s => new Period(LocalDate.parse(s), LocalDate.now()).getYears < 18)
  }
  def updateOppilaitosSeiskaKasiJaValmistava(
    koskihenkilöcontainer: KoskiHenkiloContainer
  ) = {
    val opiskeluoikeudet = filter78ValmistavaEiYsiLasnaOpiskeluoikeudet(
      koskihenkilöcontainer
    )
    opiskeluoikeudet match {
      // perusopetuksessa olevalla ei saisi olla useampaa läsnä-tilaista opiskeluoikeutta
      case oikeudet if (oikeudet.size > 1) =>
        val henkiloOid = koskihenkilöcontainer.henkilö.oid.get
        logger.error(
          s"Perusopetuksen opiskelijalle ${henkiloOid} löytyi useampi läsnä-tilainen opiskeluoikeus."
        )
        Future.failed(
          new RuntimeException(
            s"Perusopetuksen opiskelijalle ${henkiloOid} löytyi useampi läsnä-tilainen opiskeluoikeus."
          )
        )
      case oikeudet =>
        val opiskelija: Option[Opiskelija] = oikeudet.headOption
          .map(opiskeluoikeus => {
            val henkiloOid = koskihenkilöcontainer.henkilö.oid.get
            opiskeluoikeus.tyyppi.get.koodiarvo match {
              case "perusopetukseenvalmistavaopetus" =>
                logger.info(
                  s"Tuotiin perusopetukseen valmistavan opetuksen opiskelijan ${henkiloOid} tiedot Koskesta."
                )
                Opiskelija(
                  oppilaitosOid = opiskeluoikeus.oppilaitos.get.oid.get,
                  luokkataso = "valmistava",
                  luokka = "Valmistava",
                  henkiloOid = henkiloOid,
                  alkuPaiva = opiskeluoikeus.aikaleima.map(al => DateTime.parse(al)).orNull,
                  loppuPaiva = Some(KoskiUtil.deadlineDate.toDateTimeAtStartOfDay),
                  source = KoskiUtil.koski_integration_source
                )
              case "perusopetus" =>
                logger.info(
                  s"Tuotiin perusopetuksen 7./8.-luokkalaisen opiskelijan ${henkiloOid} tiedot Koskesta."
                )
                Opiskelija(
                  oppilaitosOid = opiskeluoikeus.oppilaitos.get.oid.get,
                  luokkataso = opiskeluoikeus.getLatestSeiskaKasiSuoritus
                    .flatMap(suoritus => suoritus.getLuokkataso(false))
                    .get,
                  luokka = opiskeluoikeus.getLatestSeiskaKasiSuoritus
                    .map(suoritus => suoritus.luokka.get)
                    .get,
                  henkiloOid = henkiloOid,
                  alkuPaiva = opiskeluoikeus.getSeiskaKasiluokanAlkamispaiva
                    .map(ap => ap.toDateTimeAtStartOfDay)
                    .orNull,
                  loppuPaiva = Some(KoskiUtil.deadlineDate.toDateTimeAtStartOfDay),
                  source = KoskiUtil.koski_integration_source
                )
            }
          })
        // tallennetaan opiskelija
        saveOpiskelija(opiskelija)
    }
  }

  def filter78ValmistavaEiYsiLasnaOpiskeluoikeudet(koskihenkilöcontainer: KoskiHenkiloContainer) = {
    koskihenkilöcontainer.opiskeluoikeudet
      .filter(
        _.tila.opiskeluoikeusjaksot
          .filter(jakso => LocalDate.parse(jakso.alku).compareTo(LocalDate.now()) <= 0)
          .sortBy(jakso => LocalDate.parse(jakso.alku))(Ordering[LocalDate].reverse)
          .headOption
          .map(_.tila)
          .map(_.koodiarvo)
          .exists(_.equals("lasna"))
      )
      .filter(opiskeluoikeus =>
        opiskeluoikeus.tyyppi.exists(
          _.koodiarvo == "perusopetukseenvalmistavaopetus"
        ) || (opiskeluoikeus.tyyppi.exists(
          _.koodiarvo == "perusopetus"
        ) && opiskeluoikeus.suoritukset.exists(koskiSuoritus =>
          koskiSuoritus.getLuokkataso(false).getOrElse("").equals("7") ||
            koskiSuoritus.getLuokkataso(false).getOrElse("").equals("8")
        )) && !opiskeluoikeus.opiskeluoikeusSisaltaaYsisuorituksen
      )
  }

  def processHenkilonTiedotKoskesta(
    koskihenkilöcontainer: KoskiHenkiloContainer,
    personOidsWithAliases: PersonOidsWithAliases,
    params: KoskiSuoritusHakuParams,
    henkilo: Option[Henkilo] = None
  ): Future[Seq[Either[Exception, Option[SuoritusArvosanat]]]] = {
    val henkiloOid = koskihenkilöcontainer.henkilö.oid.get
    val suoritukset = createSuorituksetJaArvosanatFromKoski(koskihenkilöcontainer).flatten
    val muidenSuoritukset = suoritukset.filter(_.suoritus.henkilo != henkiloOid)
    if (muidenSuoritukset.nonEmpty) {
      return Future.successful(
        Seq(
          Left(
            new RuntimeException(
              s"Henkilön $henkiloOid Koskitiedoista syntyi suorituksia muille henkilöille ${muidenSuoritukset
                .map(_.suoritus.henkilo)
                .mkString(", ")}"
            )
          )
        )
      )
    }
    val suorituksetWithoutDuplicates = filterAndLogSuoritusDuplicates(suoritukset)
    overrideExistingSuorituksetWithNewSuorituksetFromKoski(
      henkilo,
      koskihenkilöcontainer,
      henkiloOid,
      suorituksetWithoutDuplicates,
      personOidsWithAliases,
      params
    )
  }
}

case class SuoritusLuokka(
  suoritus: VirallinenSuoritus,
  luokka: String,
  lasnaDate: LocalDate,
  luokkataso: Option[String] = None
)
