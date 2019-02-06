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
case class VirallinenSuoritusArvosanat(suoritus: VirallinenSuoritus, arvosanat: Seq[Arvosana], luokka: String, lasnadate: LocalDate, luokkataso: Option[String])

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

  import scala.language.implicitConversions

  implicit val formats: DefaultFormats.type = DefaultFormats

  //OK-227 : Changed root_org_id to koski to mark incoming suoritus to come from Koski.
  val root_org_id = "koski"

  private val suoritusArvosanaParser = new KoskiSuoritusArvosanaParser

  def opiskeluoikeusSisaltaaYsisuorituksen(oo: KoskiOpiskeluoikeus): Boolean = {
    oo.suoritukset.exists(s => s.koulutusmoduuli.tunniste.isDefined && s.koulutusmoduuli.tunniste.get.koodiarvo.equals("9"))
  }

  def ensureAinoastaanViimeisinOpiskeluoikeusJokaisestaTyypista(oikeudet: Seq[KoskiOpiskeluoikeus]): Seq[KoskiOpiskeluoikeus] = {
    var viimeisimmatOpiskeluoikeudet: Seq[KoskiOpiskeluoikeus] = Seq()

    //tyypit eli perusopetus, perusopetuksen lisäopetus, lukiokoulutus, ammatillinen jne.
    var tyypit: Seq[String] = oikeudet.map(oikeus => {if (oikeus.tyyppi.isDefined) oikeus.tyyppi.get.koodiarvo else ""})
    //logger.info("oikeudet: " + oikeudet)
    val oikeudetFiltered = oikeudet.filter(oo => !oo.tyyppi.get.koodiarvo.equals("perusopetus") || opiskeluoikeusSisaltaaYsisuorituksen(oo))

    tyypit.distinct.foreach(tyyppi => {
      val tataTyyppia = oikeudetFiltered.filter(oo => oo.tyyppi.isDefined && oo.tyyppi.get.koodiarvo.equals(tyyppi))
      //logger.info("Filtteröidään. Tyyppi {}, vaihtoehdot {}. {} ", tyyppi, tataTyyppia.map(oo => oo.oppilaitos.get.oid + ", alku " + oo.tila.opiskeluoikeusjaksot.sortBy(_.alku).reverse.head.alku), "foo")
      val viimeisinTataTyyppia = tataTyyppia.filter(oo => oo.tila.opiskeluoikeusjaksot.exists(j => j.tila.koodiarvo.equals("lasna")) && !oo.tila.opiskeluoikeusjaksot.exists(j => j.tila.koodiarvo.equals("eronnut"))).
        sortBy(_.tila.opiskeluoikeusjaksot.sortBy(_.alku).reverse.head.alku).reverse.headOption
      if (viimeisinTataTyyppia.isDefined) {
        viimeisimmatOpiskeluoikeudet = viimeisimmatOpiskeluoikeudet :+ viimeisinTataTyyppia.get
      }
    })

    //logger.info("Returning oikeudes with oppilaitokses: " + viimeisimmatOpiskeluoikeudet.map(o => o.oppilaitos.get.oid))
    //logger.info("rets " + viimeisimmatOpiskeluoikeudet)
    viimeisimmatOpiskeluoikeudet
  }

  def processHenkilonKoskiSuoritukset(koskihenkilöcontainer: KoskiHenkiloContainer,
                                      personOidsWithAliases: PersonOidsWithAliases,
                                      params: KoskiSuoritusHakuParams): Future[Any] = {
    implicit val timeout: Timeout = 2.minutes

    // OK-227 : Get latest perusopetuksen läsnäoleva oppilaitos
    lazy val viimeisinOpiskeluoikeus: Option[KoskiOpiskeluoikeus] = resolveViimeisinPerusopetuksenOpiskeluOikeus(koskihenkilöcontainer)

    lazy val viimeisimmatOikeudet: Seq[KoskiOpiskeluoikeus] = ensureAinoastaanViimeisinOpiskeluoikeusJokaisestaTyypista(koskihenkilöcontainer.opiskeluoikeudet)

    /**
      * OK-227 : Jos opiskelija on vaihtanut koulua kesken kauden, säilytetään vain se suoritus jolla on on uusin läsnäolotieto.
      * Aiempi suoritus samalta kaudelta poistetaan suoritusrekisteristä.
      * Vaaditaan lisäksi, että valittavalla opiskeluoikeudella on oikeasti olemassa joku ysiluokan suoritus (tilalla ei väliä, ehkä?)
      */
    def resolveViimeisinPerusopetuksenOpiskeluOikeus(koskiHenkilöContainer: KoskiHenkiloContainer): Option[KoskiOpiskeluoikeus] = {
      //koskiHenkilöContainer.opiskeluoikeudet.
      //  filter(oo => oo.tyyppi.exists(_.koodiarvo == "perusopetus") && oo.suoritukset.exists(_.))

      koskihenkilöcontainer.opiskeluoikeudet.
        filter(oo => oo.tyyppi.exists(_.koodiarvo == "perusopetus") && oo.tila.opiskeluoikeusjaksot.exists(j => j.tila.koodiarvo.equals("lasna"))).
        sortBy(_.tila.opiskeluoikeusjaksot.sortBy(_.alku).reverse.head.alku).reverse.headOption
    }

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
   (opiskelijaRekisteri ? OpiskelijaQuery(henkilo = Some(henkilöOid), oppilaitosOid = Some(oppilaitosOid), source = Some(root_org_id))).mapTo[Seq[Opiskelija with Identified[UUID]]].recoverWith {
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

      val fetchedVirallisetSuoritukset: Seq[VirallinenSuoritus with Identified[UUID]] = fetchedSuoritukset.filter(s => s.source.equals(root_org_id)).flatMap {
        case s: VirallinenSuoritus with Identified[UUID @unchecked] => Some(s)
        case _ => None
      }

      val toBeDeletedSuoritukset: Seq[VirallinenSuoritus with Identified[UUID]] = fetchedVirallisetSuoritukset.filterNot(s1 => koskiVirallisetSuoritukset.exists(s2 => s1.myontaja.equals(s2.myontaja) && s1.komo.equals(s2.komo)))
      toBeDeletedSuoritukset.foreach(suoritus => {
        logger.debug("Found suoritus for henkilö " + henkilöOid + " from Suoritusrekisteri which is not found in Koski anymore " + suoritus.id + ". Deleting it")
        deleteArvosanatAndSuorituksetAndOpiskelija(suoritus, henkilöOid)
      })
    }

    def arvosanaForSuoritus(arvosana: Arvosana, s: Suoritus with Identified[UUID]): Arvosana = {
      arvosana.copy(suoritus = s.id)
    }

    def toArvosana(arvosana: Arvosana)(suoritus: UUID)(source: String): Arvosana =
      Arvosana(suoritus, arvosana.arvio, arvosana.aine, arvosana.lisatieto, arvosana.valinnainen, arvosana.myonnetty, source, Map(), arvosana.jarjestys)

    def saveSuoritusAndArvosanat(henkilöOid: String, existingSuoritukset: Seq[Suoritus], useSuoritus: VirallinenSuoritus, arvosanat: Seq[Arvosana], luokka: String, lasnaDate: LocalDate, luokkaTaso: Option[String]): Future[Any] = {
      val opiskelija = createOpiskelija(henkilöOid, SuoritusLuokka(useSuoritus, luokka, lasnaDate, luokkaTaso))

      val suoritusSave: Future[Any] =
        if (suoritusExists(useSuoritus, existingSuoritukset)) {
          logger.debug("Päivitetään olemassaolevaa suoritusta.")
          val suoritus: VirallinenSuoritus with Identified[UUID] = existingSuoritukset.flatMap {
            case s: VirallinenSuoritus with Identified[UUID @unchecked] => Some(s)
            case _ => None
          }
            .find(s => s.henkiloOid == henkilöOid && s.myontaja == useSuoritus.myontaja && s.komo == useSuoritus.komo).get
          logger.debug("Käsitellään olemassaoleva suoritus " + suoritus)
          val newArvosanat = arvosanat.map(toArvosana(_)(suoritus.id)(root_org_id))

          def saveArvosana(a: Arvosana): Future[Any] = {
            arvosanaRekisteri ? a
          }

          updateSuoritus(suoritus, useSuoritus)
            .flatMap(_ => fetchArvosanat(suoritus))
            .flatMap(existingArvosanat => Future.sequence(existingArvosanat
              .filter(_.source.contentEquals(root_org_id))
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

    def overrideExistingSuorituksetWithNewSuorituksetFromKoski(henkilöOid: String, viimeisimmatSuoritukset: Seq[SuoritusArvosanat], viimeisinOpiskeluoikeus: Option[KoskiOpiskeluoikeus], viimeisimmatOpiskeluoikeudet: Seq[KoskiOpiskeluoikeus]): Future[Unit] = {
      fetchExistingSuoritukset(henkilöOid).flatMap(fetchedSuoritukset => {
        //OY-227 : Clean up perusopetus duplicates if there is some
        /*val viimeisinOpiskeluOikeusOid: String = viimeisinOpiskeluoikeus match {
          case Some(o) => o.oppilaitos.get.oid.get
          case None => ""
        }
        val viimeisimmatOppilaitokset: Seq[String] = viimeisimmatOpiskeluoikeudet.map(oo => if (oo.oppilaitos.isDefined) oo.oppilaitos.get.oid.getOrElse("") else "")
        logger.info("Viimeisimmat oppilaitokset: " + viimeisimmatOppilaitokset)
        val viimeisimmatSuoritukset: Seq[SuoritusArvosanat] = henkilonSuoritukset.filter(sa => viimeisimmatOppilaitokset contains sa.suoritus.asInstanceOf[VirallinenSuoritus].myontaja)

        val viimeisimmatSuoritukset: Seq[SuoritusArvosanat] = viimeisinOpiskeluOikeusOid match {
          case "" => henkilonSuoritukset
          case _ => henkilonSuoritukset.filterNot(s => (!s.suoritus.asInstanceOf[VirallinenSuoritus].myontaja.equals(viimeisinOpiskeluOikeusOid)
            && s.suoritus.asInstanceOf[VirallinenSuoritus].komo.equals(Oids.perusopetusKomoOid)
            ))
        }*/
        //val viimeisimmatSuoritukset = henkilonSuoritukset

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
          case _ => overrideExistingSuorituksetWithNewSuorituksetFromKoski(henkilöOid, henkilonSuoritukset, viimeisinOpiskeluoikeus, viimeisimmatOikeudet)
        }
      }
      case None => Future.successful({})
    }
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


  def maxDate(s1: LocalDate, s2: LocalDate): LocalDate = if (s1.isAfter(s2)) s1 else s2
  def minDate(s1: LocalDate, s2: LocalDate): LocalDate = if (s1.isAfter(s2)) s2 else s1

  def createOpiskelija(henkiloOid: String, suoritusLuokka: SuoritusLuokka): Opiskelija = {

    logger.debug(s"suoritusLuokka=$suoritusLuokka")
    var alku = suoritusLuokka.lasnaDate.toDateTimeAtStartOfDay
    var loppu = suoritusLuokka.suoritus.valmistuminen.toDateTimeAtStartOfDay
    var (luokkataso, oppilaitosOid, luokka) = detectOppilaitos(suoritusLuokka)

    if (!loppu.isAfter(alku)) {
      logger.debug(s"!loppu.isAfter(alku) = $loppu isAfter $alku = false")
      loppu = suoritusArvosanaParser.parseNextThirdOfJune().toDateTimeAtStartOfDay
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
      source = root_org_id
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
    // TODO: Luokkataso?
    case s if s.suoritus.komo == Oids.ammatillinentutkintoKomoOid => getOppilaitosAndLuokka("", s, Oids.ammatillinentutkintoKomoOid)
    case _ => ("", "", "")
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
