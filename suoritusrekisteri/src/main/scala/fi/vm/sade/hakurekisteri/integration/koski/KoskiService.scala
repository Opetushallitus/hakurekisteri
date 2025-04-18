package fi.vm.sade.hakurekisteri.integration.koski

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.TimeZone
import akka.actor.{ActorSystem, Scheduler}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.hakemus.IHakemusService
import fi.vm.sade.hakurekisteri.integration.henkilo.{
  Henkilo,
  IOppijaNumeroRekisteri,
  PersonOidsWithAliases
}
import org.joda.time.{DateTime, DateTimeZone}
import com.github.blemale.scaffeine.{Cache, Scaffeine}

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.{Failure, Success}

class KoskiService(
  virkailijaRestClient: VirkailijaRestClient,
  oppijaNumeroRekisteri: IOppijaNumeroRekisteri,
  hakemusService: IHakemusService,
  koskiDataHandler: KoskiDataHandler,
  config: Config,
  pageSize: Int = 200
)(implicit val system: ActorSystem)
    extends IKoskiService {

  private val koskiKoulusivistyskieliCache: Cache[String, Seq[String]] =
    Scaffeine()
      .recordStats()
      .expireAfterWrite(12.hour)
      .maximumSize(50000)
      .build[String, Seq[String]]()

  private val HelsinkiTimeZone = TimeZone.getTimeZone("Europe/Helsinki")
  private val logger = Logging.getLogger(system, this)

  private var startTimestamp: Long = 0L
  private var oneJobAtATime = Future.successful({})

  val aktiiviset2AsteYhteisHakuOidit = new AtomicReference[Set[String]](Set.empty)
  def setAktiiviset2AsteYhteisHaut(hakuOids: Set[String]): Unit = {
    logger.info(s"Asetetaan aktiiviset toisen asteen yhteishaut: $hakuOids")
    aktiiviset2AsteYhteisHakuOidit.set(hakuOids)
  }

  val aktiivisetKKYhteisHakuOidit = new AtomicReference[Set[String]](Set.empty)
  def setAktiivisetKKYhteisHaut(hakuOids: Set[String]): Unit = {
    logger.info(s"Asetetaan aktiiviset KK-yhteishaut haut: $hakuOids")
    aktiivisetKKYhteisHakuOidit.set(hakuOids)
  }

  val aktiivisetKKHakuOidit = new AtomicReference[Set[String]](Set.empty)
  def setAktiivisetKKHaut(hakuOids: Set[String]): Unit = {
    logger.info(s"Asetetaan aktiiviset KK-yhteishaut haut: $hakuOids")
    aktiivisetKKHakuOidit.set(hakuOids)
  }

  val aktiivistenToisenAsteenJatkuvienHakujenOidit = new AtomicReference[Set[String]](Set.empty)
  def setAktiivisetToisenAsteenJatkuvatHaut(hakuOids: Set[String]): Unit = {
    logger.info(s"Asetetaan aktiiviset toisen asteen jatkuvat haut: $hakuOids")
    aktiivistenToisenAsteenJatkuvienHakujenOidit.set(hakuOids)
  }

  private val fetchPersonAliases
    : Seq[KoskiHenkiloContainer] => Future[(Seq[KoskiHenkiloContainer], PersonOidsWithAliases)] = {
    hs: Seq[KoskiHenkiloContainer] =>
      val personOids: Seq[String] = hs.flatMap(_.henkilö.oid)
      oppijaNumeroRekisteri.enrichWithAliases(personOids.toSet).map((hs, _))
  }

  case class SearchParamsWithCursor(
    timestamp: Option[String],
    cursor: Option[String],
    pageSize: Int = 5000
  )

  override def fetchOppivelvollisuusTietos(
    oppijaOids: Seq[String]
  ): Future[Seq[OppivelvollisuusTieto]] = {
    logger.info(s"Haetaan oppivelvollisuustiedot koskesta")
    virkailijaRestClient.postObjectWithCodes[Seq[String], Seq[OppivelvollisuusTieto]](
      uriKey = "koski.sure.oppivelvollisuustieto",
      Seq(200),
      maxRetries = 2,
      resource = oppijaOids,
      basicAuth = true
    )
  }

  private def fetchChangedOppijas(
    params: SearchParamsWithCursor
  ): Future[MuuttuneetOppijatResponse] = {
    logger.info(
      s"Haetaan muuttuneet henkilöoidit Koskesta, timestamp: " + params.timestamp.toString + ", cursor: " + params.cursor.toString
    )
    virkailijaRestClient.readObjectWithBasicAuth[MuuttuneetOppijatResponse](
      "koski.sure.muuttuneet-oppijat",
      params
    )(acceptedResponseCode = 200, maxRetries = 2)
  }

  def refreshChangedOppijasFromKoski(
    cursor: Option[String] = None,
    timeToWaitUntilNextBatch: FiniteDuration = 1.minutes
  )(implicit scheduler: Scheduler): Unit = {
    val endDateSuomiTime =
      KoskiUtil.deadlineDate
        .plusDays(1)
        .toDateTimeAtStartOfDay(DateTimeZone.forTimeZone(HelsinkiTimeZone))
    if (endDateSuomiTime.isBeforeNow) {
      logger.info(
        "refreshChangedOppijasFromKoski : Cutoff date of {} reached, stopping.",
        endDateSuomiTime.toString
      )
    } else {
      scheduler.scheduleOnce(timeToWaitUntilNextBatch)({
        val timestamp: Option[String] =
          if (cursor.isEmpty) {
            logger.info(
              s"Fetching changes from Koski starting from ${KoskiUtil.koskiFetchStartTime}"
            )
            Some(KoskiUtil.koskiFetchStartTime)
          } else None
        val params = SearchParamsWithCursor(timestamp, cursor)
        fetchChangedOppijas(params).onComplete {
          case Success(response: MuuttuneetOppijatResponse) =>
            logger.info(
              "refreshChangedOppijasFromKoski : got {} muuttunees oppijas from Koski.",
              response.result.size
            )
            val koskiParams = KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
            handleHenkiloUpdate(response.result, koskiParams, "refreshChangedOppijas").onComplete {
              case Success(s) =>
                logger.info(
                  "refreshChangedOppijasFromKoski : batch handling success. Oppijas handled: {}",
                  response.result.size
                )
                if (response.mayHaveMore)
                  refreshChangedOppijasFromKoski(
                    Some(response.nextCursor),
                    15.seconds
                  ) //Haetaan nopeammin jos kaikkia tietoja samalla cursorilla ei vielä saatu
                else
                  refreshChangedOppijasFromKoski(Some(response.nextCursor), 1.minutes)
              case Failure(e) =>
                logger.error(
                  "refreshChangedOppijasFromKoski : Jokin meni vikaan muuttuneiden oppijoiden tietojen haussa: {}",
                  e
                )
                refreshChangedOppijasFromKoski(cursor, 2.minutes)
            }
          case Failure(e) =>
            logger.error(
              "refreshChangedOppijasFromKoski : Jokin meni vikaan muuttuneiden oppijoiden selvittämisessä : {}",
              e
            )
            refreshChangedOppijasFromKoski(cursor, 2.minutes)
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
    val haut: Set[String] = aktiivisetKKHakuOidit.get()
    logger.info(
      s"Saatiin tarjonnasta aktiivisia korkeakoulujen hakuja ${haut.size} kpl, aloitetaan ammatillisten suoritusten päivitys."
    )
    haut.foreach(haku => {
      logger.info(
        s"Käynnistetään Koskesta aktiivisten korkeakouluhakujen ammatillisten suoritusten ajastettu päivitys haulle ${haku}"
      )
      Await.result(
        syncHaunHakijat(
          haku,
          KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = true)
        ),
        5.hours
      )
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
    logger.info(
      s"Saatiin tarjonnasta toisen asteen aktiivisia hakuja ${haut.size} kpl, aloitetaan lukiosuoritusten päivitys."
    )
    haut.foreach(haku => {
      logger.info(
        s"Käynnistetään Koskesta aktiivisten toisen asteen hakujen lukiosuoritusten ajastettu päivitys haulle ${haku}"
      )
      Await.result(
        syncHaunHakijat(
          haku,
          KoskiSuoritusHakuParams(
            saveLukio = true,
            saveAmmatillinen = false,
            saveSeiskaKasiJaValmistava = true
          )
        ),
        5.hours
      )
    })
    logger.info("Aktiivisten toisen asteen yhteishakujen lukiosuoritusten päivitys valmis.")
  }

  override def updateAktiivisetToisenAsteenJatkuvatHaut(): () => Unit = { () =>
    val hakuOids: Set[String] = aktiivistenToisenAsteenJatkuvienHakujenOidit.get()
    logger.info(
      s"Saatiin tarjonnasta jatkuvia hakuja ${hakuOids.size} kpl: ${hakuOids}, aloitetaan päivitys."
    )
    hakuOids.foreach(hakuOid => {
      logger.info(
        s"Käynnistetään Koskesta ajastettu päivitys jatkuvalle haulle ${hakuOid}"
      )
      Await.result(
        syncHaunHakijat(
          hakuOid,
          KoskiSuoritusHakuParams(
            saveLukio = true,
            saveAmmatillinen = false,
            saveSeiskaKasiJaValmistava = true
          ),
          hakuOid => hakemusService.springPersonOidsForJatkuvaHaku(hakuOid)
        ),
        5.hours
      )
    })
    logger.info("Aktiivisten toisen asteen jatkuvien hakujen hakijoiden päivitys valmis.")
  }

  override def syncHaunHakijat(
    hakuOid: String,
    params: KoskiSuoritusHakuParams,
    personOidsForHakuFn: String => Future[Set[String]]
  ) = {
    def handleUpdate(personOidsSet: Set[String]): Future[Unit] = {
      val personOidsWithAliases: PersonOidsWithAliases = Await.result(
        oppijaNumeroRekisteri.enrichWithAliases(personOidsSet),
        Duration(1, TimeUnit.MINUTES)
      )
      val aliasCount: Int =
        personOidsWithAliases.henkiloOidsWithLinkedOids.size - personOidsSet.size
      logger.info(
        s"Saatiin hakemuspalvelusta ${personOidsSet.size} oppijanumeroa ja ${aliasCount} aliasta haulle $hakuOid"
      )
      handleHenkiloUpdate(
        personOidsWithAliases.henkiloOidsWithLinkedOids.toSeq,
        params,
        s"hakuOid: $hakuOid"
      )
    }

    val now = System.currentTimeMillis()
    synchronized {
      if (oneJobAtATime.isCompleted) {
        logger.info(s"Käynnistetään Koskesta päivittäminen haulle ${hakuOid}. Params: ${params}")
        startTimestamp = System.currentTimeMillis()
        //OK-227 : We'll have to wait that the onJobAtATime is REALLY done:
        oneJobAtATime = Await.ready(
          personOidsForHakuFn(hakuOid).flatMap(handleUpdate),
          5.hours
        )
        logger.info(s"Päivitys Koskesta haulle ${hakuOid} valmistui.")
        Future.successful({})
      } else {
        val err =
          s"${TimeUnit.MINUTES.convert(now - startTimestamp, TimeUnit.MILLISECONDS)} minuuttia vanha Koskesta päivittäminen on vielä käynnissä!"
        logger.error(err)
        Future.failed(new RuntimeException(err))
      }
    }
  }

  override def syncHaunHakijat(hakuOid: String, params: KoskiSuoritusHakuParams) = {
    syncHaunHakijat(hakuOid, params, hakuOid => hakemusService.personOidsForHaku(hakuOid, None))
  }

  def handleHenkiloUpdate(
    personOids: Seq[String],
    params: KoskiSuoritusHakuParams,
    description: String = ""
  ): Future[Unit] = {
    if (personOids.isEmpty) {
      logger.info(s"HandleHenkiloUpdate ($description) no personOids to process.")
      Future.successful({})
    } else {
      logger.info(s"HandleHenkiloUpdate ($description) {} oppijanumeros", personOids.size)
      val maxOppijatBatchSize: Int = config.integrations.koskiMaxOppijatBatchSize
      val groupedOids: Seq[Seq[String]] = personOids.grouped(maxOppijatBatchSize).toSeq
      val totalGroups: Int = groupedOids.length
      var updateHenkiloResults = (Seq[String](), Seq[String]())
      logger.info(
        s"HandleHenkiloUpdate ($description) yhteensä $totalGroups kappaletta $maxOppijatBatchSize kokoisia ryhmiä."
      )

      def handleBatch(
        batches: Seq[(Seq[String], Int)],
        acc: (Seq[String], Seq[String])
      ): Future[(Seq[String], Seq[String])] = {
        def updateHenkilotWithRetries(
          oppijaOids: Set[String],
          params: KoskiSuoritusHakuParams,
          era: Int,
          retriesLeft: Int
        ): Future[(Seq[String], Seq[String])] = {
          updateHenkilot(oppijaOids, params).recoverWith({ case e: Exception =>
            if (retriesLeft > 0) {
              logger.error(
                e,
                s"HandleHenkiloUpdate ($description) Virhe päivitettäessä henkilöiden tietoja erässä $era / $totalGroups, yritetään uudelleen. Uudelleenyrityksiä jäljellä: $retriesLeft"
              )
              Future { Thread.sleep(params.retryWaitMillis) }.flatMap(_ =>
                updateHenkilotWithRetries(oppijaOids, params, era, retriesLeft - 1)
              )
            } else {
              logger.error(
                e,
                s"HandleHenkiloUpdate ($description) Virhe päivitettäessä henkilöiden tietoja erässä $era / $totalGroups, ei enää uudelleenyrityksiä jäljellä."
              )
              throw e
            }
          })
        }

        if (batches.isEmpty) {
          Future(acc)
        } else {
          val (subSeq, index) = batches.head
          logger.info(
            s"HandleHenkiloUpdate ($description) Päivitetään Koskesta $maxOppijatBatchSize henkilön tiedot Sureen. Erä ${index + 1} / $totalGroups."
          )
          updateHenkilotWithRetries(subSeq.toSet, params, index + 1, retriesLeft = 3).flatMap(s => {
            logger.info(
              s"HandleHenkiloUpdate ($description) Erä ${index + 1} / $totalGroups käsitelty virheittä."
            )
            handleBatch(batches.tail, (s._1 ++ acc._1, s._2 ++ acc._2))
          })
        }
      }

      val f: Future[(Seq[String], Seq[String])] =
        handleBatch(groupedOids.zipWithIndex, updateHenkiloResults)
      f.flatMap(results => {
        logger.info(
          s"HandleHenkiloUpdate ($description) Koskipäivitys valmistui! Päivitettiin yhteensä ${results._1.size + results._2.size} henkilöä. " +
            s"Onnistuneita päivityksiä ${results._2.size}. " +
            s"Epäonnistuneita päivityksiä ${results._1.size}. " +
            s"Epäonnistuneet: ${results._1}."
        )
        Future.successful({})
      }).recoverWith { case e: Exception =>
        logger.error(e, s"HandleHenkiloUpdate ($description) Koskipäivitys epäonnistui")
        Future.failed(e)
      }
    }
  }

  override def updateHenkilotWithAliases(
    oppijaOids: Set[String],
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])] = {
    logger.info(s"Haetaan oppijanumerorekisteristä aliakset oppijanumeroille: $oppijaOids")
    val personOidsWithAliases: PersonOidsWithAliases = Await.result(
      oppijaNumeroRekisteri.enrichWithAliases(oppijaOids),
      Duration(1, TimeUnit.MINUTES)
    )
    val aliasCount: Int = personOidsWithAliases.henkiloOidsWithLinkedOids.size - oppijaOids.size
    logger.info(
      s"Yhteensä ${personOidsWithAliases.henkiloOidsWithLinkedOids.size} oppijanumeroa joista aliaksia ${aliasCount} kpl."
    )
    updateHenkilot(personOidsWithAliases.henkiloOidsWithLinkedOids, params)
  }

  def fetchHenkilot(
    oppijaOids: Set[String]
  ): Future[Seq[KoskiHenkiloContainer]] = {
    virkailijaRestClient
      .postObjectWithCodes[Set[String], Seq[KoskiHenkiloContainer]](
        "koski.sure",
        Seq(200),
        maxRetries = 2,
        resource = oppijaOids,
        basicAuth = true
      )
      .recoverWith { case e: Exception =>
        logger.error("Kutsu koskeen oppijanumeroille {} epäonnistui: {} ", oppijaOids, e)
        Future.failed(e)
      }
  }

  def resolveKoulusivistyskieli(henkilo: KoskiHenkiloContainer): Seq[String] = {
    val validitSuoritukset = henkilo.opiskeluoikeudet
      .filter(o => o.tila.determineSuoritusTila == "VALMIS")
      .flatMap(o => o.suoritukset.filter(s => s.isLukionOrPerusopetuksenoppimaara()))

    validitSuoritukset
      .flatMap(s => s.koulusivistyskieli.map(k => k.map(_.koodiarvo)))
      .flatten
      .distinct
  }

  private def fetchKoulusivistyskieletInBatches(
    oidBatches: Seq[Set[String]],
    acc: Seq[KoskiHenkiloContainer]
  ): Future[Map[String, Seq[String]]] = {
    if (oidBatches.isEmpty) {
      Future(acc.map(h => (h.henkilö.oid.get, resolveKoulusivistyskieli(h))).toMap)
    } else {
      fetchHenkilot(oidBatches.head).flatMap((result: Seq[KoskiHenkiloContainer]) => {
        fetchKoulusivistyskieletInBatches(oidBatches.tail, acc ++ result)
      })
    }
  }

  private def fetchKoulusivistyskieletForReal(
    oppijaOids: Seq[String]
  ): Future[Map[String, Seq[String]]] = {
    val grouped = oppijaOids.toSet.grouped(1000).toSeq
    logger.info(
      s"Haetaan oikeasti kolusivistyskieli ${oppijaOids.size} oppijalle ${grouped.size} erässä Koskesta"
    )
    fetchKoulusivistyskieletInBatches(grouped, Seq.empty)
  }

  override def fetchKoulusivistyskielet(
    oppijaOids: Seq[String]
  ): Future[Map[String, Seq[String]]] = {
    logger.info(s"Pyydetty Koskesta koulusivistyskieli ${oppijaOids.size} henkilölle.")

    val cached = koskiKoulusivistyskieliCache.getAllPresent(oppijaOids)
    val missing = oppijaOids.filterNot(cached.keys.toSet.contains(_))
    logger.info(
      s"Välimuistista saatu ${cached.keys.size} henkilön koulusivistyskieli, haetaan suoraan Koskesta tiedot ${missing.size} henkilölle."
    )

    val fetched = fetchKoulusivistyskieletForReal(missing)
    fetched.foreach { f =>
      logger.info(
        s"Koski palautti ${f.keys.size} henkilön koulusivistyskielen, tallennetaan välimuistiin."
      )
      koskiKoulusivistyskieliCache.putAll(f)
    }

    fetched.zipWith(Future.successful(cached))(_ ++ _)
  }

  override def updateHenkilot(
    oppijaOids: Set[String],
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])] = {
    val oppijat = fetchHenkilot(oppijaOids)
    oppijat
      .flatMap(fetchPersonAliases)
      .flatMap(res => {
        val (henkilot, personOidsWithAliases) = res
        logger.info(s"Saatiin Koskesta ${henkilot.size} henkilöä, aliakset haettu!")
        saveKoskiHenkilotAsSuorituksetAndArvosanat(henkilot, personOidsWithAliases, params)
      })
  }

  //Poistaa KoskiHenkiloContainerin sisältä sellaiset opiskeluoikeudet, joilla ei ole oppilaitosta jolla on määritelty oid.
  //Vaaditaan lisäksi, että käsiteltävillä opiskeluoikeuksilla on ainakin yksi tilatieto.
  private def removeOpiskeluoikeudesWithoutDefinedOppilaitosAndOppilaitosOids(
    data: Seq[KoskiHenkiloContainer]
  ): Seq[KoskiHenkiloContainer] = {
    data.flatMap(container => {
      val oikeudet = container.opiskeluoikeudet.filter(_.isStateContainingOpiskeluoikeus)
      if (oikeudet.nonEmpty) {
        if (container.opiskeluoikeudet.size > oikeudet.size) {
          logger.info(
            s"Filtteröitiin henkilöltä ${container.henkilö.oid} ${(container.opiskeluoikeudet.size - oikeudet.size)} opiskeluoikeutta, joista puuttui oppilaitos tai opiskeluoikeuden tilatieto."
          )
        }
        Seq(container.copy(opiskeluoikeudet = oikeudet))
      } else {
        if (container.opiskeluoikeudet.nonEmpty) {
          logger.info(
            s"Filtteröitiin henkilöltä ${container.henkilö.oid} ${container.opiskeluoikeudet.size} opiskeluoikeutta, joista puuttui oppilaitos tai opiskeluoikeuden tilatieto."
          )
        }
        Seq()
      }
    })
  }

  private def saveKoskiHenkilotAsSuorituksetAndArvosanat(
    henkilot: Seq[KoskiHenkiloContainer],
    personOidsWithAliases: PersonOidsWithAliases,
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])] = {
    val filteredHenkilot: Seq[KoskiHenkiloContainer] =
      removeOpiskeluoikeudesWithoutDefinedOppilaitosAndOppilaitosOids(henkilot)
    if (filteredHenkilot.size < henkilot.size) {
      logger.info(
        s"saveKoskiHenkilotAsSuorituksetAndArvosanat: Filteröitiin ${henkilot.size - filteredHenkilot.size} henkilöä."
      )
    }
    val loytyyHenkiloOidi = filteredHenkilot.filter(_.henkilö.oid.isDefined)
    if (loytyyHenkiloOidi.size < filteredHenkilot.size) {
      logger.info(
        s"saveKoskiHenkilotAsSuorituksetAndArvosanat: Filteröitiin ${filteredHenkilot.size - loytyyHenkiloOidi.size} henkilöä joilla ei oidia."
      )
    }
    val henkiloOidToHenkilo: Future[Map[String, Henkilo]] =
      if (params.saveSeiskaKasiJaValmistava)
        oppijaNumeroRekisteri.getByOids(loytyyHenkiloOidi.flatMap(_.henkilö.oid).toSet)
      else
        Future.successful(Map.empty)

    henkiloOidToHenkilo.flatMap(henkilot =>
      Future
        .sequence(
          loytyyHenkiloOidi.map(henkilo =>
            (try {
              koskiDataHandler.processHenkilonTiedotKoskesta(
                henkilo,
                personOidsWithAliases.intersect(Set(henkilo.henkilö.oid.get)),
                params,
                henkilot.get(henkilo.henkilö.oid.get)
              )
            } catch {
              case e: Exception =>
                logger.error(
                  s"Koskitietojen päivitys henkilölle ${henkilo.henkilö.oid} epäonnistui: $e"
                )
                Future.successful(Seq(Left(e)))
            }).map(results => {
              val es = results.collect { case Left(e) => e }
              es.foreach(e =>
                logger.error(
                  e,
                  s"Koskitietojen tallennus henkilölle ${henkilo.henkilö.oid.get} epäonnistui"
                )
              )
              if (es.isEmpty) {
                logger.info(
                  s"Koskitietojen tallennus henkilölle ${henkilo.henkilö.oid.get} onnistui"
                )
                Right(henkilo.henkilö.oid.get)
              } else {
                Left(henkilo.henkilö.oid.get)
              }
            })
          )
        )
        .map(results =>
          (results.collect { case Left(oid) => oid }, results.collect { case Right(oid) => oid })
        )
    )
  }
}
