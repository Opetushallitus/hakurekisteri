package fi.vm.sade.hakurekisteri.integration.koski

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import java.util.TimeZone
import akka.actor.{ActorSystem, Scheduler}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.integration.hakemus.IHakemusService
import fi.vm.sade.hakurekisteri.integration.henkilo.{
  Henkilo,
  IOppijaNumeroRekisteri,
  PersonOidsWithAliases
}
import org.joda.time.{DateTime, DateTimeZone}
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.asynchttpclient.{DefaultAsyncHttpClientConfig, Realm}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.JsonMethods.parse
import org.slf4j.{Logger, LoggerFactory}

import java.sql.Date
import java.text.SimpleDateFormat
import scala.annotation.tailrec
import scala.concurrent.ExecutionContextExecutorService
//import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.{Failure, Success}

case class KoskiMassaluovutusQueryParams(
  `type`: String,
  format: String,
  oppijaOids: Option[Set[String]], //Käytännössä joko oppijaOids tai muuttuneetJälkeen on määritelty
  muuttuneetJälkeen: Option[String]
)
case class KoskiMassaluovutusQueryResponse(
  queryId: String,
  requestedBy: String,
  query: KoskiMassaluovutusQueryParams,
  createdAt: Option[String],
  startedAt: Option[String],
  finishedAt: Option[String],
  files: Seq[String],
  resultsUrl: Option[String],
  progress: Option[Map[String, String]],
  sourceDataUpdatedAt: Option[String],
  status: String
) {
  def isFinished() = status.equals("complete") || isFailed()
  def isFailed() = status.equals("failed")

}

case class KoskiMassaluovutusResultLite(oppijaOid: String, opiskeluoikeus: KoskiOpiskeluoikeus)

class KoskiService(
  virkailijaRestClient: VirkailijaRestClient,
  oppijaNumeroRekisteri: IOppijaNumeroRekisteri,
  hakemusService: IHakemusService,
  koskiDataHandler: KoskiDataHandler,
  config: Config,
  pageSize: Int = 200
)(implicit val system: ActorSystem)
    extends IKoskiService {
  implicit val ec: ExecutionContextExecutorService =
    ExecutorUtil.createExecutor(8, "koski-sure-executor-pool")
  private val koskiKoulusivistyskieliCache: Cache[String, Seq[String]] =
    Scaffeine()
      .recordStats()
      .expireAfterWrite(12.hour)
      .maximumSize(50000)
      .build[String, Seq[String]]()

  private val HelsinkiTimeZone = TimeZone.getTimeZone("Europe/Helsinki")
  val logger: Logger = LoggerFactory.getLogger(getClass)

  private var startTimestamp: Long = 0L
  private var oneJobAtATime = Future.successful((Seq[String](), Seq[String]()))

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

  def refreshChangedOppijasFromKoski(
    lastQueryStart: Option[String],
    timeToWaitUntilNextBatch: FiniteDuration = 10.seconds
  )(implicit scheduler: Scheduler): Unit = {
    logger.info(s"refreshChangedOppijasFromKoski called!")
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
        logger.info(s"refreshChangedOppijasFromKoski activating!")
        //vvvv-kk-ppThh:mm:ss
        val muuttunutJalkeenStr = lastQueryStart.getOrElse(
          "2024-12-17T10:55:33"
        ) //edellisen kyselyn aloitushetki, tai aloitetaan vain jostain jos ei olemassa
        val thisQueryStart = new SimpleDateFormat(
          "yyyy-MM-dd'T'HH:mm:ss"
        ) //tästä tulee seuraavan iteraation aloitushetki
          .format(new Date(System.currentTimeMillis()))
        logger.info(
          s"refreshChangedOppijasFromKoski : nyt $thisQueryStart, haetaan muuttuneet jälkeen: $muuttunutJalkeenStr"
        )
        val koskiParams = KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
        val resultF = handleKoskiRefreshMuuttunutJalkeen(muuttunutJalkeenStr, koskiParams)

        resultF.onComplete {
          case Success(all) =>
            logger.info(
              s"refreshChangedOppijasFromKoski : saatiin käsiteltyä muutokset alkaen $muuttunutJalkeenStr. Onnistuneita ${all._2.size}, epäonnistuneita ${all._1.size}, epäonnistuneet: ${all._1}"
            )
            refreshChangedOppijasFromKoski(Some(thisQueryStart), 15.seconds)
          case Failure(f) =>
            logger.error(
              s"refreshChangedOppijasFromKoski : jotain meni vikaan muutosten käsittelyssä. Yritetään myöhemmin uudelleen samoilla parametreilla. ${f.getMessage}",
              f
            )
            refreshChangedOppijasFromKoski(Some(muuttunutJalkeenStr), 1.minutes)
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
    def handleUpdate(personOidsSet: Set[String]): Future[(Seq[String], Seq[String])] = {
      val personOidsWithAliases: PersonOidsWithAliases = Await.result(
        oppijaNumeroRekisteri.enrichWithAliases(personOidsSet),
        Duration(1, TimeUnit.MINUTES)
      )
      val aliasCount: Int =
        personOidsWithAliases.henkiloOidsWithLinkedOids.size - personOidsSet.size
      logger.info(
        s"Saatiin hakemuspalvelusta ${personOidsSet.size} oppijanumeroa ja ${aliasCount} aliasta haulle $hakuOid"
      )
      handleKoskiRefreshForOppijaOids(personOidsWithAliases.henkiloOidsWithLinkedOids, params)
      /*handleHenkiloUpdate(
        personOidsWithAliases.henkiloOidsWithLinkedOids.toSeq,
        params,
        s"hakuOid: $hakuOid"
      )*/
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
                s"HandleHenkiloUpdate ($description) Virhe päivitettäessä henkilöiden tietoja erässä $era / $totalGroups, yritetään uudelleen. Uudelleenyrityksiä jäljellä: $retriesLeft",
                e
              )
              Future { Thread.sleep(params.retryWaitMillis) }.flatMap(_ =>
                updateHenkilotWithRetries(oppijaOids, params, era, retriesLeft - 1)
              )
            } else {
              logger.error(
                s"HandleHenkiloUpdate ($description) Virhe päivitettäessä henkilöiden tietoja erässä $era / $totalGroups, ei enää uudelleenyrityksiä jäljellä.",
                e
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
        logger.error(s"HandleHenkiloUpdate ($description) Koskipäivitys epäonnistui", e)
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
        logger.error(s"Kutsu koskeen oppijanumeroille $oppijaOids epäonnistui:", e)
        Future.failed(e)
      }
  }

  def newHandlerFunction(data: Seq[KoskiHenkiloContainer], params: KoskiSuoritusHakuParams) = {
    logger.info(s"New handler, handling ${data.size} containers")
    fetchPersonAliases(data)
      .flatMap(res => {
        val (henkilot, personOidsWithAliases) = res
        logger.info(s"Saatiin Koskesta ${henkilot.size} henkilöä, aliakset haettu!")
        saveKoskiHenkilotAsSuorituksetAndArvosanat(henkilot, personOidsWithAliases, params)
      })
  }

  def createAndHandleKoskiMassaluovutusQuery(
    ccQuery: KoskiMassaluovutusQueryParams,
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])] = {
    try {
      logger.info(s"Calling Koski with query: $ccQuery")
      val resultF = virkailijaRestClient
        .postObjectWithCodes[KoskiMassaluovutusQueryParams, KoskiMassaluovutusQueryResponse](
          "koski.sure.massaluovutus.create-query",
          Seq(200),
          maxRetries = 2,
          resource = ccQuery,
          basicAuth = true
        )
        .flatMap((baseQueryResponse: KoskiMassaluovutusQueryResponse) => {
          logger.info(s"Saatiin vastaus massaluovutusrajapinnalta: ${baseQueryResponse
            .copy(files = baseQueryResponse.files.take(3))}, pollataan tilaa")

          val pRes = pollMassaluovutusAndHandleResults(
            baseQueryResponse.resultsUrl.get,
            newHandlerFunction,
            params
          )

          pRes
        })
        .map((ress) => {
          logger.info(s"Pollaus valmista, tulos: $ress")
          ress
        })

      resultF.onComplete {
        case Success(results) =>
          logger.info(
            s"Massaluovutusqueryn $ccQuery käsittely onnistui. Onnistuneita oppijoita ${results._2.size} ja epäonnistuneita ${results._1.size}."
          )
        case Failure(f) =>
          logger.error(s"Massaluovutusqueryn $ccQuery käsittely epäonnistui: ${f.getMessage}", f)
      }
      resultF

    } catch {
      case e: Throwable =>
        logger.error(s"Mitäs nyt, ${e.getStackTrace}", e)
        if (e.getCause != null) {
          logger.error(s"caused by ${e.getCause.getMessage}, ${e.getCause.getStackTrace}")
        } else {
          logger.error(s"No cause... ${e.getStackTrace.toSeq.foreach(println)}")
        }
        Future.failed(e)
    }
  }

  def pollMassaluovutusAndHandleResults(
    resultsUrl: String,
    resultFunction: (
      Seq[KoskiHenkiloContainer],
      KoskiSuoritusHakuParams
    ) => Future[(Seq[String], Seq[String])],
    params: KoskiSuoritusHakuParams
  ) = {
    var handled: Set[String] = Set[String]()

    //Yhdistetään useamman tiedoston sisältö ja käsitellään kerralla, että esimerkiksi aliasten haku on tehokkaampaa ja pollaustiheys harvempi
    //Tällä hetkellä yksi tiedosto sisältää yhden oppijan tiedot, tämä voi kuitenkin muuttua.
    val maxNumberOfFilesPerPoll: Int = 5

    def pollAndHandleUntilReady(
      succeededAndFailed: (Seq[String], Seq[String]),
      previousPollResult: Option[KoskiMassaluovutusQueryResponse]
    ): Future[(Seq[String], Seq[String])] = {
      val pollResultF =
        if (previousPollResult.exists(_.isFinished())) {
          logger.info(
            s"Kaikki tulokset ovat valmistuneet Kosken päässä, ei lisää. Käsitellään kuitenkin loput tiedostot jos tarpeen."
          )
          Future.successful(previousPollResult.get)
        } else if (previousPollResult.exists(pp => pp.files.size > handled.size)) {
          logger.info(s"Käsittelemättömiä tiedostoja on vielä, ei pollata.")
          Future.successful(previousPollResult.get)
        } else {
          logger.info(
            s"Pollataan lisää piakkoin! Koskessa valmiina ${previousPollResult.map(_.files).getOrElse(Seq.empty).size}"
          )
          Thread.sleep(5000)
          pollMassaluovutus(resultsUrl)
        }
      val handleResult = pollResultF.flatMap(massaluovutusQueryResponse => {
        logger.info(
          s"MassaluovutusQueryResponse files ready: ${massaluovutusQueryResponse.files.size} progress: ${massaluovutusQueryResponse.progress}"
        )
        logger.info(
          s"Koskessa valmiina: ${massaluovutusQueryResponse.files.size}, käsitelty aiemmin ${handled.size}"
        )
        if (massaluovutusQueryResponse.isFailed())
          throw new RuntimeException(s"Virhe Kosken päässä kyselyssä $resultsUrl")

        val filesReadyInKoski: Set[String] = massaluovutusQueryResponse.files.toSet
        //val unhandled: Set[String] = filesReadyInKoski - handledResults.keySet()
        val unhandled: Set[String] = (filesReadyInKoski -- handled).take(maxNumberOfFilesPerPoll)
        handled = handled ++ unhandled
        //log.info(s"$massaluovutusQueryResponse Koskessa valmiina ${massaluovutusQueryResponse.files.size}, käsittely aloitettu ${handledResults.entrySet()}")
        //val uGroups = unhandled.grouped(maxNumOfFilesToGroup).toList
        if (unhandled.nonEmpty) {
          logger.info(s"Käsitellään ${unhandled.size} tiedostoa")
          val resultFileFutures = unhandled.toSeq.map(resultUrl => {
            fetchKoskiResultFile(resultUrl)
          })
          val allFileResults: Future[(Seq[String], Seq[String])] = Future
            .sequence(resultFileFutures)
            .map(_.flatten)
            .map(foo => Await.result(resultFunction(foo, params), 5.minutes))
          allFileResults.flatMap(fr => {
            val combinedResults = (succeededAndFailed._1 ++ fr._1, succeededAndFailed._2 ++ fr._2)
            if (massaluovutusQueryResponse.isFinished()) {
              if ((massaluovutusQueryResponse.files.toSet -- handled).isEmpty) {
                logger.info(
                  s"Query on valmistunut ja kaikki ${massaluovutusQueryResponse.files.size} on käsitelty: $combinedResults"
                )
                Future.successful(combinedResults)
              } else {
                //Todo, tässä vois lopettaa pollauksen...
                logger.info(
                  s"Query on valmistunut mutta kaikkia tiedostoja ei ole vielä käsitelty. Jatketaan!"
                )
                pollAndHandleUntilReady(combinedResults, Some(massaluovutusQueryResponse))
              }
            } else {
              logger.info(
                s"Erä on käsitelty mutta query ei vielä valmistunut, onnistuneita ${combinedResults._2.size} ja epäonnistuneita ${combinedResults._1.size}"
              )
              pollAndHandleUntilReady(combinedResults, Some(massaluovutusQueryResponse))
            }
          })
        } else {
          logger.info(s"Ei käsiteltävää, pollataan kohta uudestaan.")
          pollAndHandleUntilReady(succeededAndFailed, Some(massaluovutusQueryResponse))
        }
      })
      logger.info(s"returning handleResult")
      handleResult
    }

    val fullResultF = pollAndHandleUntilReady((Seq[String](), Seq[String]()), None)
    fullResultF.onComplete {
      case Success(all) =>
        logger.info(s"Onnistui? $all")
      case Failure(f) =>
        logger.error(s"Mikä meni vikaan? ${f.getMessage}", f)
    }
    fullResultF

  }

  def pollMassaluovutus(url: String) = {
    virkailijaRestClient
      .readObjectFromUrl[KoskiMassaluovutusQueryResponse](
        url,
        Seq(200),
        2,
        basicAuth = true
      )
  }

  def fetchKoskiResultFile(fileUrl: String): Future[Seq[KoskiHenkiloContainer]] = {
    try {
      //log.info(s"Haetaan tiedosto $fileUrl")
      val koskiTiedotF = virkailijaRestClient
        .readKoskiMassaluovutusResultFromUrl[Seq[KoskiMassaluovutusResultLite]](
          fileUrl,
          Seq(200),
          maxRetries = 2
        )
        .map(koskiTiedotResult => {
          val henkiloContainers: Seq[KoskiHenkiloContainer] = koskiTiedotResult
            .groupBy(_.oppijaOid)
            .map(kv =>
              KoskiHenkiloContainer(
                KoskiHenkilo(Some(kv._1), None, None, None, None, None),
                kv._2.map(_.opiskeluoikeus)
              )
            )
            .toList
          if (henkiloContainers.isEmpty)
            logger.warn(s"Jostain syystä tiedosta $fileUrl ei löytynyt oppijoita...")
          //log.info(s"Saatiin Koskesta tiedot ${henkiloContainers.size} oppijalle")
          henkiloContainers
        })
      koskiTiedotF
    } catch {
      case e: Exception =>
        logger.error(s"Virhe käsiteltäessä tuloksia osoitteesta $fileUrl", e)
        Future.failed(e)
    }
  }

  def handleKoskiRefreshForOppijaOids(oppijaOids: Set[String], params: KoskiSuoritusHakuParams) = {
    createAndHandleKoskiMassaluovutusQuery(
      KoskiMassaluovutusQueryParams(
        "sure-oppijat",
        "application/json",
        Some(oppijaOids),
        muuttuneetJälkeen = None
      ),
      params
    )
  }

  //Todo, joku validointi aikaleiman muodolle? vvvv-kk-ppThh:mm:ss
  def handleKoskiRefreshMuuttunutJalkeen(
    muuttunutJalkeen: String,
    params: KoskiSuoritusHakuParams
  ) = {
    createAndHandleKoskiMassaluovutusQuery(
      KoskiMassaluovutusQueryParams(
        "sure-muuttuneet",
        "application/json",
        None,
        muuttuneetJälkeen = Some(muuttunutJalkeen)
      ),
      params
    )
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

  override def updateHenkilotNewMuuttunutJalkeen(
    muuttunutJalkeen: String,
    params: KoskiSuoritusHakuParams
  ) = {
    logger.info(s"Update henkilot new muuttunut jalkeen!")
    handleKoskiRefreshMuuttunutJalkeen(muuttunutJalkeen, params)
  }

  override def updateHenkilotNew(
    oppijaOids: Set[String],
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])] = {
    logger.info(s"Update henkilot new!")
    val foo = handleKoskiRefreshForOppijaOids(oppijaOids, params)
    val result = Await.result(foo, 5.minutes)
    logger.info(s"Tiedostoja: $result")
    Future.successful((Seq.empty, Seq.empty))
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
                  s"Koskitietojen tallennus henkilölle ${henkilo.henkilö.oid.get} epäonnistui",
                  e
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
