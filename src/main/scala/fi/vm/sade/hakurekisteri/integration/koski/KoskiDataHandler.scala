package fi.vm.sade.hakurekisteri.integration.koski

import java.util.{Calendar, UUID}

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
import org.joda.time.{DateTime, LocalDate, LocalDateTime}
import org.json4s.DefaultFormats
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class SuoritusArvosanat(suoritus: Suoritus, arvosanat: Seq[Arvosana], luokka: String, lasnadate: LocalDate, luokkataso: Option[String]) {
  private val AIKUISTENPERUS_LUOKKAASTE = "AIK"

  def peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus(henkilonSuoritukset: Seq[SuoritusArvosanat]): Boolean = {
    suoritus match {
      case v: VirallinenSuoritus =>
        v.komo.equals(Oids.perusopetusKomoOid) &&
          (henkilonSuoritukset.exists(_.luokkataso.getOrElse("").startsWith("9")) || luokkataso.getOrElse("").equals(AIKUISTENPERUS_LUOKKAASTE))
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

  //OK-227 : Changed root_org_id to koski to mark incoming suoritus to come from Koski.

  private val suoritusArvosanaParser = new KoskiSuoritusArvosanaParser
  private val opiskelijaParser = new KoskiOpiskelijaParser

  def opiskeluoikeusSisaltaaYsisuorituksen(oo: KoskiOpiskeluoikeus): Boolean = {
    oo.suoritukset.exists(s => s.koulutusmoduuli.tunniste.isDefined && s.koulutusmoduuli.tunniste.get.koodiarvo.equals("9"))
  }

  def ensureAinoastaanViimeisinOpiskeluoikeusJokaisestaTyypista(oikeudet: Seq[KoskiOpiskeluoikeus]): Seq[KoskiOpiskeluoikeus] = {
    var viimeisimmatOpiskeluoikeudet: Seq[KoskiOpiskeluoikeus] = Seq()

    //Opiskeluoikeuden tyypit eli perusopetus, perusopetuksen lisäopetus (10), lukiokoulutus, ammatillinen jne.
    var tyypit: Seq[String] = oikeudet.map(oikeus => {if (oikeus.tyyppi.isDefined) oikeus.tyyppi.get.koodiarvo else ""})

    //Poistetaan viimeisimmän opiskeluoikeuden päättelystä sellaiset peruskoulusuoritukset joilla ei ole ysiluokan suoritusta
    val oikeudetFiltered = oikeudet.filter(oo => !oo.tyyppi.get.koodiarvo.equals("perusopetus") || opiskeluoikeusSisaltaaYsisuorituksen(oo))

    tyypit.distinct.foreach(tyyppi => {
      val tataTyyppia = oikeudetFiltered.filter(oo => oo.tyyppi.isDefined && oo.tyyppi.get.koodiarvo.equals(tyyppi))
      val viimeisinTataTyyppia = tataTyyppia.filter(oo => oo.tila.opiskeluoikeusjaksot.exists(j => j.tila.koodiarvo.equals("lasna")) && !oo.tila.opiskeluoikeusjaksot.exists(j => j.tila.koodiarvo.equals("eronnut"))).
        sortBy(_.tila.opiskeluoikeusjaksot.sortBy(_.alku).reverse.head.alku).reverse.headOption
      if (viimeisinTataTyyppia.isDefined) {
        viimeisimmatOpiskeluoikeudet = viimeisimmatOpiskeluoikeudet :+ viimeisinTataTyyppia.get
      }
    })

    viimeisimmatOpiskeluoikeudet
  }

  def updateSuoritus(suoritus: VirallinenSuoritus with Identified[UUID], suor: VirallinenSuoritus): Future[VirallinenSuoritus with Identified[UUID]] =
    (suoritusRekisteri ? suoritus.copy(tila = suor.tila, valmistuminen = suor.valmistuminen, yksilollistaminen = suor.yksilollistaminen, suoritusKieli = suor.suoritusKieli)).mapTo[VirallinenSuoritus with Identified[UUID]].recoverWith{
      case t: AskTimeoutException => updateSuoritus(suoritus, suor)
    }

  def deleteArvosanatAndSuorituksetAndOpiskelija(suoritus: VirallinenSuoritus with Identified[UUID], henkilöOid: String): Unit = {
    val arvosanat = fetchArvosanat(suoritus).mapTo[Seq[Arvosana with Identified[UUID]]].map(_.foreach(a => deleteArvosana(a)))
    deleteSuoritus(suoritus).onComplete {
      case Success(_) =>
        fetchOpiskelijat(henkilöOid, suoritus.myontaja).onComplete {
          case Success(opiskelija) => {
            opiskelija.size match {
              case 1 => deleteOpiskelija(opiskelija.head)
              case _ => logger.debug("Multiple opiskelijas found for henkilöoid: " + henkilöOid + ", skip deletion.")
            }
          }
          case Failure(t) =>
            logger.error("Virhe opiskelijan " + henkilöOid + " poistossa.", t)
        }
      case Failure(t) =>
        logger.error("Virhe henkilön " + henkilöOid + " suorituksen poistossa.", t)
    }
  }

  def fetchOpiskelijat(henkilöOid: String, oppilaitosOid: String): Future[Seq[Opiskelija with Identified[UUID]]] = {
    (opiskelijaRekisteri ? OpiskelijaQuery(henkilo = Some(henkilöOid), oppilaitosOid = Some(oppilaitosOid), source = Some(KoskiUtil.root_org_id))).mapTo[Seq[Opiskelija with Identified[UUID]]].recoverWith {
      case t: AskTimeoutException =>
        logger.error(s"Got timeout exception when fetching opiskelija: $henkilöOid , retrying", t)
        fetchOpiskelijat(henkilöOid, oppilaitosOid)
    }
  }

  def deleteOpiskelija(o: Opiskelija with Identified[UUID]): Future[Any] = {
    logger.debug("Poistetaan opiskelija " + o + "UUID:lla " + o.id)
    opiskelijaRekisteri ? DeleteResource(o.id, "koski-opiskelijat")
  }

  def deleteSuoritus(s: Suoritus with Identified[UUID]): Future[Any] = {
    logger.debug("Poistetaan suoritus " + s + "UUID:lla" + s.id)
    suoritusRekisteri ? DeleteResource(s.id, "koski-suoritukset")
  }

  def fetchArvosanat(s: VirallinenSuoritus with Identified[UUID]): Future[Seq[Arvosana with Identified[UUID]]] = {
    logger.debug("Haetaan arvosanat suoritukselle: " + s + ", id: " + s.id)
    (arvosanaRekisteri ? ArvosanaQuery(suoritus = s.id)).mapTo[Seq[Arvosana with Identified[UUID]]]
  }

  def deleteArvosana(s: Arvosana with Identified[UUID]): Future[Any] = {
    logger.debug("Poistetaan arvosana " + s + "UUID:lla" + s.id)
    arvosanaRekisteri ? DeleteResource(s.id, "koski-arvosanat")
  }

  def saveOpiskelija(opiskelija: Opiskelija): Future[Any] = {
    opiskelijaRekisteri ? opiskelija
  }

  def suoritusExists(suor: VirallinenSuoritus, suoritukset: Seq[Suoritus]): Boolean = suoritukset.exists {
    case s: VirallinenSuoritus => s.core == suor.core
    case _ => false
  }

  def checkAndDeleteIfSuoritusDoesNotExistAnymoreInKoski(fetchedSuoritukset: Seq[Suoritus], henkilonSuoritukset: Seq[SuoritusArvosanat], henkilöOid: String): Unit = {
    // Only virallinen suoritus
    val koskiVirallisetSuoritukset: Seq[VirallinenSuoritus] = henkilonSuoritukset.map(h => h.suoritus).flatMap {
      case s: VirallinenSuoritus => Some(s)
      case _ => None
    }

    val fetchedVirallisetSuoritukset: Seq[VirallinenSuoritus with Identified[UUID]] = fetchedSuoritukset.filter(s => s.source.equals(KoskiUtil.root_org_id)).flatMap {
      case s: VirallinenSuoritus with Identified[UUID @unchecked] => Some(s)
      case _ => None
    }

    val toBeDeletedSuoritukset: Seq[VirallinenSuoritus with Identified[UUID]] = fetchedVirallisetSuoritukset.filterNot(s1 => koskiVirallisetSuoritukset.exists(s2 => s1.myontaja.equals(s2.myontaja) && s1.komo.equals(s2.komo)))
    toBeDeletedSuoritukset.foreach(suoritus => {
      logger.info("Found suoritus for henkilö " + henkilöOid + " from Suoritusrekisteri which is not found in Koski anymore " + suoritus.id + ". Deleting it")
      deleteArvosanatAndSuorituksetAndOpiskelija(suoritus, henkilöOid)
    })
  }

  def arvosanaForSuoritus(arvosana: Arvosana, s: Suoritus with Identified[UUID]): Arvosana = {
    arvosana.copy(suoritus = s.id)
  }

  def toArvosana(arvosana: Arvosana)(suoritus: UUID)(source: String): Arvosana =
    Arvosana(suoritus, arvosana.arvio, arvosana.aine, arvosana.lisatieto, arvosana.valinnainen, arvosana.myonnetty, source, Map(), arvosana.jarjestys)


  def processHenkilonTiedotKoskesta(koskihenkilöcontainer: KoskiHenkiloContainer,
                                    personOidsWithAliases: PersonOidsWithAliases,
                                    params: KoskiSuoritusHakuParams): Future[Any] = {

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

    def saveSuoritusAndArvosanat(henkilöOid: String, existingSuoritukset: Seq[Suoritus], useSuoritus: VirallinenSuoritus, arvosanat: Seq[Arvosana], luokka: String, lasnaDate: LocalDate, luokkaTaso: Option[String]): Future[Any] = {
      val opiskelija = opiskelijaParser.createOpiskelija(henkilöOid, SuoritusLuokka(useSuoritus, luokka, lasnaDate, luokkaTaso))

      val suoritusSave: Future[Any] =
        if (suoritusExists(useSuoritus, existingSuoritukset)) {
          logger.debug("Päivitetään olemassaolevaa suoritusta.")
          val suoritus: VirallinenSuoritus with Identified[UUID] = existingSuoritukset.flatMap {
            case s: VirallinenSuoritus with Identified[UUID @unchecked] => Some(s)
            case _ => None
          }
            .find(s => s.henkiloOid == henkilöOid && s.myontaja == useSuoritus.myontaja && s.komo == useSuoritus.komo).get
          logger.debug("Käsitellään olemassaoleva suoritus " + suoritus)
          val newArvosanat = arvosanat.map(toArvosana(_)(suoritus.id)(KoskiUtil.root_org_id))

          def saveArvosana(a: Arvosana): Future[Any] = {
            arvosanaRekisteri ? a
          }

          updateSuoritus(suoritus, useSuoritus)
            .flatMap(_ => fetchArvosanat(suoritus))
            .flatMap(existingArvosanat => Future.sequence(existingArvosanat
              .filter(_.source.contentEquals(KoskiUtil.root_org_id))
              .map(arvosana => deleteArvosana(arvosana))))
            .flatMap(_ => Future.sequence(newArvosanat.map(saveArvosana)))
            .flatMap(_ => saveOpiskelija(opiskelija))
        } else {
          def arvosanaToInsertResource(arvosana: Arvosana, suoritus: Suoritus with Identified[UUID]) = {
            InsertResource[UUID, Arvosana](arvosanaForSuoritus(arvosana, suoritus), personOidsWithAliases)
          }

          saveSuoritus(useSuoritus).flatMap(suoritus =>
            Future.sequence(arvosanat.map(a => arvosanaRekisteri ? arvosanaToInsertResource(a, suoritus)))
          ).flatMap(_ => saveOpiskelija(opiskelija))
        }

      suoritusSave
    }

    def overrideExistingSuorituksetWithNewSuorituksetFromKoski(henkilöOid: String, viimeisimmatSuoritukset: Seq[SuoritusArvosanat]): Future[Unit] = {
      fetchExistingSuoritukset(henkilöOid).flatMap(fetchedSuoritukset => {

        //OY-227 : Check and delete if there is suoritus which is not included on new suoritukset.
        checkAndDeleteIfSuoritusDoesNotExistAnymoreInKoski(fetchedSuoritukset, viimeisimmatSuoritukset, henkilöOid)
        if (!params.saveLukio) {
          viimeisimmatSuoritukset.filterNot(s => s.suoritus.asInstanceOf[VirallinenSuoritus].komo.equals(Oids.lukioKomoOid))
        }
        if (!params.saveAmmatillinen) {
          viimeisimmatSuoritukset.filterNot(s => s.suoritus.asInstanceOf[VirallinenSuoritus].komo.equals(Oids.erikoisammattitutkintoKomoOid)
            || s.suoritus.asInstanceOf[VirallinenSuoritus].komo.equals(Oids.ammatillinentutkintoKomoOid)
            || s.suoritus.asInstanceOf[VirallinenSuoritus].komo.equals(Oids.ammatillinenKomoOid))
        }

        //NOTE, processes the Future that encloses the list, does not actually iterate through the list
        Future.sequence(viimeisimmatSuoritukset.map {
          case s@SuoritusArvosanat(useSuoritus: VirallinenSuoritus, arvosanat: Seq[Arvosana], luokka: String, lasnaDate: LocalDate, luokkaTaso: Option[String]) =>
            //Suren suoritus = Kosken opiskeluoikeus + päättötodistussuoritus
            //Suren luokkatieto = Koskessa peruskoulun 9. luokan suoritus
            //todo tarkista, onko tämä vielä tarpeen, tai voisiko tätä ainakin muokata? Nyt tänne asti ei pitäisi tulla ei-ysejä peruskoululaisia.
            if (!useSuoritus.komo.equals(Oids.perusopetusLuokkaKomoOid) &&
              (s.peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus(viimeisimmatSuoritukset) || !useSuoritus.komo.equals(Oids.perusopetusKomoOid))) {
              saveSuoritusAndArvosanat(henkilöOid, fetchedSuoritukset, useSuoritus, arvosanat, luokka, lasnaDate, luokkaTaso)

            } else {
              Future.successful({})
            }
          case _ => Future.successful({})
        }).flatMap(_ => Future.successful({logger.info("Koski-suoritusten tallennus henkilölle " + henkilöOid + " valmis.")}))
      })
    }

    koskihenkilöcontainer.henkilö.oid match {
      case Some(henkilöOid) => {
        val henkilonSuoritukset: Seq[SuoritusArvosanat] = createSuorituksetJaArvosanatFromKoski(koskihenkilöcontainer).flatten
          .filter(s => henkilöOid.equals(s.suoritus.henkiloOid))

        henkilonSuoritukset match {
          case Nil => Future.successful({})
          case _ => overrideExistingSuorituksetWithNewSuorituksetFromKoski(henkilöOid, henkilonSuoritukset)
        }
      }
      case None => Future.successful({})
    }
  }

  def createSuorituksetJaArvosanatFromKoski(henkilo: KoskiHenkiloContainer): Seq[Seq[SuoritusArvosanat]] = {
    val viimeisimmat = ensureAinoastaanViimeisinOpiskeluoikeusJokaisestaTyypista(henkilo.opiskeluoikeudet)
    if (henkilo.opiskeluoikeudet.size > viimeisimmat.size) {
      logger.info("Filtteröitiin henkilöltä " + henkilo.henkilö.oid + " pois yksi tai useampia opiskeluoikeuksia. Ennen filtteröintiä: " + henkilo.opiskeluoikeudet.size + ", jälkeen: " + viimeisimmat.size)
    }
    suoritusArvosanaParser.getSuoritusArvosanatFromOpiskeluoikeudes(henkilo.henkilö.oid.getOrElse(""), viimeisimmat)
  }


}

case class SuoritusLuokka(suoritus: VirallinenSuoritus, luokka: String, lasnaDate: LocalDate, luokkataso: Option[String] = None)

case class MultipleSuoritusException(henkiloOid: String,
                                     myontaja: String,
                                     komo: String)
  extends Exception(s"Multiple suoritus found for henkilo $henkiloOid by myontaja $myontaja with komo $komo.")
