package fi.vm.sade.hakurekisteri.integration.koski

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.{Date, TimeZone}

import akka.actor.{ActorSystem, Scheduler}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.hakemus.IHakemusService
import fi.vm.sade.hakurekisteri.integration.henkilo.{IOppijaNumeroRekisteri, PersonOidsWithAliases}
import org.joda.time.{DateTime, DateTimeZone}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{FiniteDuration, _}
import scala.math.BigDecimal
import scala.util.{Failure, Success, Try}

class KoskiService(virkailijaRestClient: VirkailijaRestClient,
                   oppijaNumeroRekisteri: IOppijaNumeroRekisteri,
                   hakemusService: IHakemusService,
                   koskiDataHandler: KoskiDataHandler,
                   config: Config,
                   pageSize: Int = 200)(implicit val system: ActorSystem)  extends IKoskiService {

  private val HelsinkiTimeZone = TimeZone.getTimeZone("Europe/Helsinki")
  private val endDateSuomiTime = DateTime.parse("2018-06-05T18:00:00").withZoneRetainFields(DateTimeZone.forTimeZone(HelsinkiTimeZone))
  private val logger = Logging.getLogger(system, this)

  val aktiiviset2AsteYhteisHakuOidit = new AtomicReference[Set[String]](Set.empty)
  def setAktiiviset2AsteYhteisHaut(hakuOids: Set[String]): Unit = aktiiviset2AsteYhteisHakuOidit.set(hakuOids)

  val aktiivisetKKYhteisHakuOidit = new AtomicReference[Set[String]](Set.empty)
  def setAktiivisetKKYhteisHaut(hakuOids: Set[String]): Unit = aktiivisetKKYhteisHakuOidit.set(hakuOids)

  val fetchPersonAliases: (Seq[KoskiHenkiloContainer]) => Future[(Seq[KoskiHenkiloContainer], PersonOidsWithAliases)] = { hs: Seq[KoskiHenkiloContainer] =>
    logger.debug(s"Haetaan aliakset henkilöille=$hs")
    val personOids: Seq[String] = hs.flatMap(_.henkilö.oid)
    oppijaNumeroRekisteri.enrichWithAliases(personOids.toSet).map((hs, _))
  }

  case class SearchParams(muuttunutJälkeen: String, muuttunutEnnen: String = "2100-01-01T12:00")
  case class SearchParamsWithCursor(timestamp: Option[String], cursor: Option[String], pageSize: Int = 5000)

  def fetchChanged(page: Int = 0, params: SearchParams): Future[Seq[KoskiHenkiloContainer]] = {
    virkailijaRestClient.readObjectWithBasicAuth[List[KoskiHenkiloContainer]]("koski.oppija", params)(acceptedResponseCode = 200, maxRetries = 2)
  }

  def fetchChangedOppijas(params: SearchParamsWithCursor): Future[MuuttuneetOppijatResponse] = {
    logger.info(s"Haetaan muuttuneet henkilöoidit Koskesta, timestamp: " + params.timestamp.toString + ", cursor: " + params.cursor.toString)
    virkailijaRestClient.readObjectWithBasicAuth[MuuttuneetOppijatResponse]("koski.sure.muuttuneet-oppijat", params)(acceptedResponseCode = 200, maxRetries = 2)
  }

  def clampTimeToEnd(date: Date): Date = {
    val dt = new DateTime(date)
    if (dt.isBefore(endDateSuomiTime)) {
      date
    } else {
      endDateSuomiTime.toDate
    }
  }

  def refreshChangedOppijasFromKoski(cursor: Option[String] = None, timeToWaitUntilNextBatch: FiniteDuration = 1.minutes)(implicit scheduler: Scheduler): Unit = {
    val endDateSuomiTime = DateTime.parse("2019-06-05T18:00:00").withZoneRetainFields(DateTimeZone.forTimeZone(HelsinkiTimeZone))
    if(endDateSuomiTime.isBeforeNow) {
      logger.info("refreshChangedOppijasFromKoski : Cutoff date of {} reached, stopping.", endDateSuomiTime.toString)
    } else {
      scheduler.scheduleOnce(timeToWaitUntilNextBatch)({
        //Some("2018-06-01T00:00:00+02:00") kovakoodattu oletusaikaleima, tällä saadaan kaikki kesällä tapahtuneen koski-sure-päivitysten sulkemisen
        //jälkeen tulleet muutokset haettua. Tulee vähän ylimääräistäkin tietoa, mutta parempi erehtyä siihen suuntaan.
        val timestamp: Option[String] =
          if (!cursor.isDefined)
            //Some(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date(Platform.currentTime - TimeUnit.DAYS.toMillis(360))))
            Some("2018-06-01T00:00:00+02:00") //Hyvä arvo tuotantoon
            //Some("2018-01-01T00:00:00+02:00") Hyvä arvo QA:lla
          else None
        val params = SearchParamsWithCursor(timestamp, cursor)
        logger.info("refreshChangedOppijasFromKoski active, making call with params: {}", params)
        fetchChangedOppijas(params).onComplete {
          case Success(response: MuuttuneetOppijatResponse) =>
            logger.info("refreshChangedOppijasFromKoski : got {} muuttunees oppijas from Koski.", response.result.size)
            val koskiParams = KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
            handleHenkiloUpdate(response.result, koskiParams).onComplete {
              case Success(s) =>
                logger.info("refreshChangedOppijasFromKoski : batch handling success. Oppijas handled: {}", response.result.size)
                if (response.mayHaveMore)
                  refreshChangedOppijasFromKoski(Some(response.nextCursor), 1.minutes) //Haetaan nopeammin jos kaikkia tietoja samalla cursorilla ei vielä saatu
                else
                  refreshChangedOppijasFromKoski(Some(response.nextCursor), 5.minutes)
              case Failure(e) =>
                logger.error("refreshChangedOppijasFromKoski : Jokin meni vikaan muuttuneiden oppijoiden tietojen haussa: {}", e)
                refreshChangedOppijasFromKoski(cursor, 5.minutes)
            }
          case Failure(e) => logger.error("refreshChangedOppijasFromKoski : Jokin meni vikaan muuttuneiden oppijoiden selvittämisessä : {}", e)
            refreshChangedOppijasFromKoski(cursor, 5.minutes)
        }
      })
    }
  }

  /*
    *OK-227 : haun automatisointi.
    * Hakee joka yö:
    * - Aktiivisten 2. asteen hakujen lukiosuoritukset Koskesta
    * - Aktiivisten korkeakouluhakujen ammatilliset suoritukset Koskesta
    */
  override def updateAktiivisetHaut(): () => Unit = { () =>
    var haut: Set[String] = aktiiviset2AsteYhteisHakuOidit.get()
    logger.info(("Saatiin tarjonnasta toisen asteen aktiivisia hakuja " + haut.size + " kpl, aloitetaan lukiosuoritusten päivitys."))
    haut.foreach(haku => {
      logger.info(s"Käynnistetään Koskesta aktiivisten toisen asteen hakujen lukiosuoritusten ajastettu päivitys haulle ${haku}")
      Await.result(updateHenkilotForHaku(haku, KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)), 5.hours)
    })
    logger.info(("Aktiivisten toisen asteen yhteishakujen lukiosuoritusten päivitys valmis."))
    haut = aktiivisetKKYhteisHakuOidit.get()
    logger.info(("Saatiin tarjonnasta aktiivisia korkeakoulujen hakuja " + haut.size + " kpl, aloitetaan ammatillisten suoritusten päivitys."))
    haut.foreach(haku => {
      logger.info(s"Käynnistetään Koskesta aktiivisten korkeakouluhakujen ammatillisten suoritusten ajastettu päivitys haulle ${haku}")
      Await.result(updateHenkilotForHaku(haku, KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = true)), 5.hours)
    })
    logger.info(("Aktiivisten korkeakoulu-yhteishakujen ammatillisten suoritusten päivitys valmis."))
  }

  private var startTimestamp: Long = 0L
  val timeoutAfter: Long = TimeUnit.HOURS.toMillis(5)
  private var oneJobAtATime = Future.successful({})
  override def updateHenkilotForHaku(hakuOid: String, params: KoskiSuoritusHakuParams): Future[Unit] = {
    def handleUpdate(personOidsSet: Set[String]): Future[Unit] = {
      val personOids: Seq[String] = personOidsSet.toSeq
      logger.info(s"Saatiin hakemuspalvelusta ${personOids.length} oppijanumeroa haulle $hakuOid")
      handleHenkiloUpdate(personOids, params)
    }
    val now = System.currentTimeMillis()
    synchronized {
      if(oneJobAtATime.isCompleted) {
        logger.info(s"Käynnistetään Koskesta päivittäminen haulle ${hakuOid}. Params: ${params}")
        startTimestamp = System.currentTimeMillis()
        //OK-227 : We'll have to wait that the onJobAtATime is REALLY done:
        oneJobAtATime = Await.ready(hakemusService.personOidsForHaku(hakuOid, None).flatMap(handleUpdate), 5.hours)
        logger.info(s"Päivitys Koskesta haulle ${hakuOid} valmistui.")
        Future.successful({})
      } else {
        val err = s"${TimeUnit.MINUTES.convert(now - startTimestamp,TimeUnit.MILLISECONDS)} minuuttia vanha Koskesta päivittäminen on vielä käynnissä!"
        logger.error(err)
        Future.failed(new RuntimeException(err))
      }
    }
  }

  def handleHenkiloUpdate(personOids: Seq[String], params: KoskiSuoritusHakuParams): Future[Unit] = {
    logger.info("HandleHenkiloUpdate: {} oppijanumeros", personOids.size)
    val maxOppijatBatchSize: Int = config.integrations.koskiMaxOppijatBatchSize
    val groupedOids: Seq[Seq[String]] = personOids.grouped(maxOppijatBatchSize).toSeq
    val totalGroups: Int = groupedOids.length
    logger.info(s"HandleHenkiloUpdate: yhteensä $totalGroups kappaletta $maxOppijatBatchSize kokoisia ryhmiä.")

    def handleBatch(batches: Seq[(Seq[String], Int)]): Future[Unit] = {
      if(batches.isEmpty) {
        Future.successful({})
      } else {
        val (subSeq, index) = batches.head
        logger.info(s"HandleHenkiloUpdate: Päivitetään Koskesta $maxOppijatBatchSize henkilöä sureen. Erä $index / $totalGroups")
        updateHenkilot(subSeq.toSet, params).flatMap(s => handleBatch(batches.tail))
      }
    }

    val f = handleBatch(groupedOids.zipWithIndex)
    f.onComplete {
      case Success(_) => logger.info("HandleHenkiloUpdate: Koskipäivitys valmistui!")
      case Failure(e) => logger.error(s"HandleHenkiloUpdate: Koskipäivitys epäonnistui", e)
    }
    f
  }

  override def updateHenkilot(oppijaOids: Set[String], params: KoskiSuoritusHakuParams): Future[Unit] = {
    val oppijat: Future[Seq[KoskiHenkiloContainer]] = virkailijaRestClient
      .postObjectWithCodes[Set[String],Seq[KoskiHenkiloContainer]]("koski.sure", Seq(200), maxRetries = 2, resource = oppijaOids, basicAuth = true)
      .recoverWith {
        case e: Exception =>
          logger.error("Kutsu koskeen oppijanumeroille {} epäonnistui: {} ", oppijaOids, e)
          Future.failed(e)
      }

    oppijat.flatMap(fetchPersonAliases).flatMap(res => {
      val (henkilot, personOidsWithAliases) = res
      logger.info(s"Saatiin Koskesta ${henkilot.size} henkilöä, aliakset haettu!")
      saveKoskiHenkilotAsSuorituksetAndArvosanat(henkilot, personOidsWithAliases, params)
    })
  }

  //Poistaa KoskiHenkiloContainerin sisältä sellaiset opiskeluoikeudet, joilla ei ole oppilaitosta jolla on määritelty oid.
  //Vaaditaan lisäksi, että käsiteltävillä opiskeluoikeuksilla on ainakin yksi tilatieto.
  private def removeOpiskeluoikeudesWithoutDefinedOppilaitosAndOppilaitosOids(data: Seq[KoskiHenkiloContainer]): Seq[KoskiHenkiloContainer] = {
    data.flatMap(container => {
      val oikeudet = container.opiskeluoikeudet.filter(_.isStateContainingOpiskeluoikeus)
      if(oikeudet.nonEmpty) Seq(container.copy(opiskeluoikeudet = oikeudet)) else Seq()
    })
  }

  private def saveKoskiHenkilotAsSuorituksetAndArvosanat(henkilot: Seq[KoskiHenkiloContainer], personOidsWithAliases: PersonOidsWithAliases, params: KoskiSuoritusHakuParams): Future[Unit] = {
    val filteredHenkilot = removeOpiskeluoikeudesWithoutDefinedOppilaitosAndOppilaitosOids(henkilot)
    if(filteredHenkilot.nonEmpty) {
      Future.sequence(filteredHenkilot.map(henkilo =>
        koskiDataHandler.processHenkilonTiedotKoskesta(henkilo, personOidsWithAliases.intersect(henkilo.henkilö.oid.toSet), params).recoverWith {
          case e: Exception =>
            logger.error("Koskisuoritusten tallennus henkilölle {} epäonnistui: {} ",henkilo.henkilö.oid , e)
            Future.failed(e)
        })).flatMap(_ => Future.successful({})).recoverWith{
        case e: Exception =>
          logger.error("Kaikkien henkilöiden koskisuorituksia ei saatu tallennettua. {} " , e)
          Future.failed(e)
      }
    } else {
      logger.info("saveKoskiHenkilotAsSuorituksetAndArvosanat: henkilölistaus tyhjä. Ennen filtteröintiä {}, jälkeen {}.", henkilot.size, filteredHenkilot.size)
      Future.successful({})
    }
  }
}

case class MuuttuneetOppijatResponse(result: Seq[String], mayHaveMore: Boolean, nextCursor: String)

case class KoskiHenkiloContainer(
                                  henkilö: KoskiHenkilo,
                                  opiskeluoikeudet: Seq[KoskiOpiskeluoikeus]
                        )

case class KoskiHenkilo(
                         oid: Option[String],
                         hetu: Option[String],
                         syntymäaika: Option[String],
                         etunimet: Option[String],
                         kutsumanimi: Option[String],
                         sukunimi: Option[String]) {
}
case class KoskiOpiskeluoikeus(
                 oid: Option[String], //LUVA data does not have an OID
                 oppilaitos: Option[KoskiOrganisaatio],
                 tila: KoskiOpiskeluoikeusjakso,
                 päättymispäivä: Option[String],
                 lisätiedot: Option[KoskiLisatiedot],
                 suoritukset: Seq[KoskiSuoritus],
                 tyyppi: Option[KoskiKoodi],
                 aikaleima: Option[String]) {

  def isStateContainingOpiskeluoikeus =
    oppilaitos.isDefined && oppilaitos.get.oid.isDefined && tila.opiskeluoikeusjaksot.nonEmpty
}

case class KoskiOpiskeluoikeusjakso(opiskeluoikeusjaksot: Seq[KoskiTila])

case class KoskiTila(alku: String, tila:KoskiKoodi)

case class KoskiOrganisaatio(oid: Option[String])

case class KoskiSuoritus(
                  luokka: Option[String],
                  koulutusmoduuli: KoskiKoulutusmoduuli,
                  tyyppi: Option[KoskiKoodi],
                  kieli: Option[KoskiKieli],
                  pakollinen: Option[Boolean],
                  toimipiste: Option[KoskiOrganisaatio],
                  vahvistus: Option[KoskiVahvistus],
                  suorituskieli: Option[KoskiKieli],
                  arviointi: Option[Seq[KoskiArviointi]],
                  yksilöllistettyOppimäärä: Option[Boolean],
                  osasuoritukset: Seq[KoskiOsasuoritus],
                  ryhmä: Option[String],
                  alkamispäivä: Option[String],
                  //jääLuokalle is only used for peruskoulu
                  jääLuokalle: Option[Boolean],
                  tutkintonimike: Seq[KoskiKoodi] = Nil,
                  tila: Option[KoskiKoodi] = None) {

  def opintopisteitaVahintaan(min: BigDecimal): Boolean = {
    val sum = osasuoritukset
      .filter(_.arviointi.exists(_.hyväksytty.contains(true)))
      .flatMap(_.koulutusmoduuli.laajuus)
      .map(_.arvo.getOrElse(BigDecimal(0)))
      .sum
    sum >= min
  }
}

case class KoskiOsasuoritus(
                 koulutusmoduuli: KoskiKoulutusmoduuli,
                 tyyppi: KoskiKoodi,
                 arviointi: Seq[KoskiArviointi],
                 pakollinen: Option[Boolean],
                 yksilöllistettyOppimäärä: Option[Boolean],
                 osasuoritukset: Option[Seq[KoskiOsasuoritus]]
             ) {

  def opintopisteidenMaara: BigDecimal = {
    val laajuus: Option[KoskiValmaLaajuus] = koulutusmoduuli.laajuus.filter(_.yksikkö.koodiarvo == "2")
    val arvo: Option[BigDecimal] = laajuus.flatMap(_.arvo)
    arvo.getOrElse(KoskiUtil.ZERO)
  }

  def isLukioSuoritus: Boolean = {

    koulutusmoduuli.tunniste.map(_.koodiarvo) match {
      case Some(koodi) =>
        KoskiUtil.lukioaineetRegex.flatMap(_.findFirstIn(koodi)).nonEmpty
      case _ => false
    }
  }

  def isPK: Boolean = {
    koulutusmoduuli.tunniste.map(_.koodiarvo) match {
      case Some(koodi) =>
        KoskiUtil.peruskouluaineetRegex.flatMap(_.findFirstIn(koodi)).nonEmpty
      case _ => false
    }
  }

}

case class KoskiArviointi(arvosana: KoskiKoodi, hyväksytty: Option[Boolean], päivä: Option[String]) {
  def isPKValue: Boolean = {
    KoskiUtil.peruskoulunArvosanat.contains(arvosana.koodiarvo) || arvosana.koodiarvo == "H"
  }
}

case class KoskiKoulutusmoduuli(tunniste: Option[KoskiKoodi],
                                kieli: Option[KoskiKieli],
                                koulutustyyppi:
                                Option[KoskiKoodi],
                                laajuus: Option[KoskiValmaLaajuus],
                                pakollinen: Option[Boolean])

case class KoskiValmaLaajuus(arvo: Option[BigDecimal], yksikkö: KoskiKoodi)

case class KoskiKoodi(koodiarvo: String, koodistoUri: String) {
  def valinnainen: Boolean = {
    KoskiUtil.valinnaiset.contains(koodiarvo)
  }
  def eivalinnainen: Boolean = {
    KoskiUtil.eivalinnaiset.contains(koodiarvo)
  }
  def a2b2Kielet: Boolean = {
    KoskiUtil.a2b2Kielet.contains(koodiarvo)
  }
  def kielet: Boolean = {
    KoskiUtil.kielet.contains(koodiarvo)
  }
}

case class KoskiVahvistus(päivä: String, myöntäjäOrganisaatio: KoskiOrganisaatio)

case class KoskiKieli(koodiarvo: String, koodistoUri: String)

case class KoskiLisatiedot(
                            erityisenTuenPäätös: Option[KoskiErityisenTuenPaatos],
                            vuosiluokkiinSitoutumatonOpetus: Option[Boolean])

case class KoskiErityisenTuenPaatos(opiskeleeToimintaAlueittain: Option[Boolean])

case class KoskiSuoritusHakuParams(saveLukio: Boolean = false, saveAmmatillinen: Boolean = false)
