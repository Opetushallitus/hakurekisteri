package fi.vm.sade.hakurekisteri.integration.koski

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.{TimeZone}

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
import scala.util.{Failure, Success}

class KoskiService(virkailijaRestClient: VirkailijaRestClient,
                   oppijaNumeroRekisteri: IOppijaNumeroRekisteri,
                   hakemusService: IHakemusService,
                   koskiDataHandler: KoskiDataHandler,
                   config: Config,
                   pageSize: Int = 200)(implicit val system: ActorSystem)  extends IKoskiService {

  private val HelsinkiTimeZone = TimeZone.getTimeZone("Europe/Helsinki")
  private val logger = Logging.getLogger(system, this)

  private var startTimestamp: Long = 0L
  private var oneJobAtATime = Future.successful({})

  val aktiiviset2AsteYhteisHakuOidit = new AtomicReference[Set[String]](Set.empty)
  def setAktiiviset2AsteYhteisHaut(hakuOids: Set[String]): Unit = aktiiviset2AsteYhteisHakuOidit.set(hakuOids)

  val aktiivisetKKYhteisHakuOidit = new AtomicReference[Set[String]](Set.empty)
  def setAktiivisetKKYhteisHaut(hakuOids: Set[String]): Unit = aktiivisetKKYhteisHakuOidit.set(hakuOids)

  private val fetchPersonAliases: Seq[KoskiHenkiloContainer] => Future[(Seq[KoskiHenkiloContainer], PersonOidsWithAliases)] = { hs: Seq[KoskiHenkiloContainer] =>
    val personOids: Seq[String] = hs.flatMap(_.henkilö.oid)
    oppijaNumeroRekisteri.enrichWithAliases(personOids.toSet).map((hs, _))
  }

  case class SearchParamsWithCursor(timestamp: Option[String], cursor: Option[String], pageSize: Int = 5000)

  private def fetchChangedOppijas(params: SearchParamsWithCursor): Future[MuuttuneetOppijatResponse] = {
    logger.info(s"Haetaan muuttuneet henkilöoidit Koskesta, timestamp: " + params.timestamp.toString + ", cursor: " + params.cursor.toString)
    virkailijaRestClient.readObjectWithBasicAuth[MuuttuneetOppijatResponse]("koski.sure.muuttuneet-oppijat", params)(acceptedResponseCode = 200, maxRetries = 2)
  }

  def refreshChangedOppijasFromKoski(cursor: Option[String] = None, timeToWaitUntilNextBatch: FiniteDuration = 1.minutes)(implicit scheduler: Scheduler): Unit = {
    val endDateSuomiTime = KoskiUtil.deadlineDate.toDateTimeAtStartOfDay(DateTimeZone.forTimeZone(HelsinkiTimeZone))
    if(endDateSuomiTime.isBeforeNow) {
      logger.info("refreshChangedOppijasFromKoski : Cutoff date of {} reached, stopping.", endDateSuomiTime.toString)
    } else {
      scheduler.scheduleOnce(timeToWaitUntilNextBatch)({
        val timestamp: Option[String] =
          if (!cursor.isDefined)
            //Some(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date(Platform.currentTime - TimeUnit.DAYS.toMillis(360))))
            Some("2019-06-01T00:00:00+02:00")
          else None
        val params = SearchParamsWithCursor(timestamp, cursor)
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
    * - Aktiivisten korkeakouluhakujen ammatilliset suoritukset Koskesta
    */
  override def updateAktiivisetKkAsteenHaut(): () => Unit = { () =>
    val haut: Set[String] = aktiivisetKKYhteisHakuOidit.get()
    logger.info(s"Saatiin tarjonnasta aktiivisia korkeakoulujen hakuja ${haut.size} kpl, aloitetaan ammatillisten suoritusten päivitys.")
    haut.foreach(haku => {
      logger.info(s"Käynnistetään Koskesta aktiivisten korkeakouluhakujen ammatillisten suoritusten ajastettu päivitys haulle ${haku}")
      Await.result(updateHenkilotForHaku(haku, KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = true)), 5.hours)
    })
    logger.info("Aktiivisten korkeakoulu-yhteishakujen ammatillisten suoritusten päivitys valmis.")
  }

  /*
    *OK-227 : haun automatisointi.
    * Hakee joka yö:
    * - Aktiivisten 2. asteen hakujen lukiosuoritukset Koskesta
    */
  override def updateAktiivisetToisenAsteenHaut(): () => Unit = { () =>
    val haut: Set[String] = aktiiviset2AsteYhteisHakuOidit.get()
    logger.info(s"Saatiin tarjonnasta toisen asteen aktiivisia hakuja ${haut.size} kpl, aloitetaan lukiosuoritusten päivitys.")
    haut.foreach(haku => {
      logger.info(s"Käynnistetään Koskesta aktiivisten toisen asteen hakujen lukiosuoritusten ajastettu päivitys haulle ${haku}")
      Await.result(updateHenkilotForHaku(haku, KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)), 5.hours)
    })
    logger.info("Aktiivisten toisen asteen yhteishakujen lukiosuoritusten päivitys valmis.")
  }

  override def updateHenkilotForHaku(hakuOid: String, params: KoskiSuoritusHakuParams): Future[Unit] = {
    def handleUpdate(personOidsSet: Set[String]): Future[Unit] = {
      val personOidsWithAliases: PersonOidsWithAliases = Await.result(oppijaNumeroRekisteri.enrichWithAliases(personOidsSet),
        Duration(1, TimeUnit.MINUTES))
      val aliasCount: Int = personOidsWithAliases.henkiloOidsWithLinkedOids.size - personOidsSet.size
      logger.info(s"Saatiin hakemuspalvelusta ${personOidsSet.size} oppijanumeroa ja ${aliasCount} aliasta haulle $hakuOid")
      handleHenkiloUpdate(personOidsWithAliases.henkiloOidsWithLinkedOids.toSeq, params)
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
    if (personOids.isEmpty) {
      logger.info("HandleHenkiloUpdate : no personOids to process.")
      Future.successful({})
    } else {
      logger.info("HandleHenkiloUpdate: {} oppijanumeros", personOids.size)
      val maxOppijatBatchSize: Int = config.integrations.koskiMaxOppijatBatchSize
      val groupedOids: Seq[Seq[String]] = personOids.grouped(maxOppijatBatchSize).toSeq
      val totalGroups: Int = groupedOids.length
      var updateHenkiloResults = (Seq[String](), Seq[String]())
      logger.info(s"HandleHenkiloUpdate: yhteensä $totalGroups kappaletta $maxOppijatBatchSize kokoisia ryhmiä.")

      def handleBatch(batches: Seq[(Seq[String], Int)], acc: (Seq[String], Seq[String])): Future[(Seq[String], Seq[String])] = {
        if (batches.isEmpty) {
          Future(acc)
        } else {
          val (subSeq, index) = batches.head
          logger.info(s"HandleHenkiloUpdate: Päivitetään Koskesta $maxOppijatBatchSize henkilöä sureen. Erä $index / $totalGroups")
          updateHenkilot(subSeq.toSet, params).flatMap(s => {
            handleBatch(batches.tail, (s._1 ++ acc._1, s._2 ++ acc._2))
          })
        }
      }

      val f: Future[(Seq[String], Seq[String])] = handleBatch(groupedOids.zipWithIndex, updateHenkiloResults)
      f.flatMap(results => {
        logger.info(s"HandleHenkiloUpdate: Koskipäivitys valmistui! Päivitettiin yhteensä ${results._1.size + results._2.size} henkilöä. " +
          s"Onnistuneita päivityksiä ${results._2.size}. " +
          s"Epäonnistuneita päivityksiä ${results._1.size}. " +
          s"Epäonnistuneet: ${results._1}.")
        Future.successful({})
      }
      ).recoverWith {
        case e: Exception =>
          logger.error(e, "HandleHenkiloUpdate: Koskipäivitys epäonnistui")
          Future.successful({})
      }
    }
  }

  override def updateHenkilotWithAliases(oppijaOids: Set[String], params: KoskiSuoritusHakuParams): Future[(Seq[String], Seq[String])] = {
    logger.info(s"Haetaan oppijanumerorekisteristä aliakset oppijanumeroille: $oppijaOids")
    val personOidsWithAliases: PersonOidsWithAliases = Await.result(oppijaNumeroRekisteri.enrichWithAliases(oppijaOids),
      Duration(1, TimeUnit.MINUTES))
    val aliasCount: Int = personOidsWithAliases.henkiloOidsWithLinkedOids.size - oppijaOids.size
    logger.info(s"Yhteensä ${personOidsWithAliases.henkiloOidsWithLinkedOids.size} oppijanumeroa joista aliaksia ${aliasCount} kpl.")
    updateHenkilot(personOidsWithAliases.henkiloOidsWithLinkedOids, params)
  }

  override def updateHenkilot(oppijaOids: Set[String], params: KoskiSuoritusHakuParams): Future[(Seq[String], Seq[String])] = {
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
      if(oikeudet.nonEmpty) {
        if (container.opiskeluoikeudet.size > oikeudet.size) {
          logger.info(s"Filtteröitiin henkilöltä ${container.henkilö.oid} ${(container.opiskeluoikeudet.size - oikeudet.size)} opiskeluoikeutta, joista puuttui oppilaitos tai opiskeluoikeuden tilatieto.")
        }
        Seq(container.copy(opiskeluoikeudet = oikeudet))
      } else {
        if (container.opiskeluoikeudet.nonEmpty) {
          logger.info(s"Filtteröitiin henkilöltä ${container.henkilö.oid} ${container.opiskeluoikeudet.size} opiskeluoikeutta, joista puuttui oppilaitos tai opiskeluoikeuden tilatieto.")
        }
        Seq()
      }
    })
  }

  private def saveKoskiHenkilotAsSuorituksetAndArvosanat(henkilot: Seq[KoskiHenkiloContainer], personOidsWithAliases: PersonOidsWithAliases, params: KoskiSuoritusHakuParams): Future[(Seq[String], Seq[String])] = {
    val filteredHenkilot: Seq[KoskiHenkiloContainer] = removeOpiskeluoikeudesWithoutDefinedOppilaitosAndOppilaitosOids(henkilot)
    if (filteredHenkilot.size < henkilot.size) {
      logger.info(s"saveKoskiHenkilotAsSuorituksetAndArvosanat: Filteröitiin ${henkilot.size - filteredHenkilot.size} henkilöä.")
    }
    val loytyyHenkiloOidi = filteredHenkilot.filter(_.henkilö.oid.isDefined)
    if (loytyyHenkiloOidi.size < filteredHenkilot.size) {
      logger.info(s"saveKoskiHenkilotAsSuorituksetAndArvosanat: Filteröitiin ${filteredHenkilot.size - loytyyHenkiloOidi.size} henkilöä joilla ei oidia.")
    }
    Future.sequence(loytyyHenkiloOidi.map(henkilo =>
      (try {
        koskiDataHandler.processHenkilonTiedotKoskesta(henkilo, personOidsWithAliases.intersect(Set(henkilo.henkilö.oid.get)), params)
      } catch {
        case e: Exception => Future.successful(Seq(Left(e)))
      }).map(results => {
        val es = results.collect { case Left(e) => e }
        es.foreach(e => logger.error(e, s"Koskitietojen tallennus henkilölle ${henkilo.henkilö.oid.get} epäonnistui"))
        if (es.isEmpty) {
          logger.info(s"Koskitietojen tallennus henkilölle ${henkilo.henkilö.oid.get} onnistui")
          Right(henkilo.henkilö.oid.get)
        } else {
          Left(henkilo.henkilö.oid.get)
        }
      })
    )).map(results => (results.collect { case Left(oid) => oid }, results.collect { case Right(oid) => oid }))
  }
}
