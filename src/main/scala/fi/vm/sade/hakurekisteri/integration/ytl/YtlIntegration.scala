package fi.vm.sade.hakurekisteri.integration.ytl

import java.io.{FileOutputStream, File}
import java.text.SimpleDateFormat
import java.util.function.UnaryOperator
import java.util.zip.ZipInputStream
import java.util.{UUID, Date}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import akka.actor.ActorRef
import fi.vm.sade.hakurekisteri.integration.hakemus.{IHakemusService, FullHakemus, HakemusService, HetuPersonOid}
import fi.vm.sade.properties.OphProperties
import org.apache.commons.io.IOUtils
import org.apache.commons.lang.time.DateUtils
import org.slf4j.LoggerFactory

import scala.collection.Set
import scala.concurrent._
import scala.util.{Failure, Success}

case class LastFetchStatus(uuid: String, start: Date, end: Option[Date], succeeded: Option[Boolean]) {
  def inProgress = end.isEmpty
}

class YtlIntegration(config: OphProperties,
                     ytlHttpClient: YtlHttpFetch,
                     ytlFileSystem: YtlFileSystem,
                     hakemusService: IHakemusService,
                     ytlActor: ActorRef) {
  private val logger = LoggerFactory.getLogger(getClass)
  val kokelaatDownloadDirectory = ytlFileSystem.directoryPath
  val kokelaatDownloadPath = new File(kokelaatDownloadDirectory, "ytl-v2-kokelaat.json").getAbsolutePath
  val activeKKHakuOids = new AtomicReference[Set[String]](Set.empty)
  private val lastFetchStatus = new AtomicReference[LastFetchStatus]();
  private def newFetchStatus = LastFetchStatus(UUID.randomUUID().toString, new Date(), None, None)
  implicit val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5))

  def setAktiivisetKKHaut(hakuOids: Set[String]) = activeKKHakuOids.set(hakuOids)

  private def writeToFile(prefix: String, postfix: String, bytes: Array[Byte]) {
    val timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new java.util.Date())
    val file = s"${prefix}_${timestamp}_$postfix";
    val output= new FileOutputStream(new File(kokelaatDownloadDirectory,file))
    IOUtils.write(bytes, output)
    IOUtils.closeQuietly(output)
  }

  def sync(hakemus: FullHakemus): Unit = {
    if(activeKKHakuOids.get().contains(hakemus.applicationSystemId)) {
      hakemus.hetu match {
        case Some(hetu) =>
          logger.info(s"Syncronizing hakemus ${hakemus.oid} with YTL")
          ytlHttpClient.fetchOne(hetu) match {
            case None =>
              logger.info(s"No YTL data for hakemus ${hakemus.oid}")
            case Some((json, student)) =>
              writeToFile(hetu,".json", json.getBytes)
          }
          // TODO persist students
        case None =>
          logger.debug(s"Skipping YTL update as hakemus (${hakemus.oid}) doesn't have henkilotunnus!")
      }
    } else {
      logger.debug(s"Skipping YTL update as hakemus (${hakemus.oid}) is not in active haku (not active ${hakemus.applicationSystemId})!")
    }
  }

  def sync(personOid: String): Unit = {
    hakemusService.hakemuksetForPerson(personOid).onComplete {
      case Success(hakemukset) =>
        if(hakemukset.isEmpty) {
          logger.error(s"failed to fetch one hakemus from hakemus service with person OID $personOid")
          throw new RuntimeException(s"Hakemus not found with person OID $personOid!")
        }
        val hakemus = hakemukset.foreach(sync)
      case Failure(e) =>
        logger.error(s"failed to fetch one hakemus from hakemus service: ${e.getMessage}")
        throw e
    }
  }

  private def atomicUpdateFetchStatus(updator: LastFetchStatus => LastFetchStatus): LastFetchStatus = {
    lastFetchStatus.updateAndGet(
      new UnaryOperator[LastFetchStatus]{
        override def apply(t: LastFetchStatus): LastFetchStatus = updator.apply(t)
      }
    )
  }

  def getLastFetchStatus: Option[LastFetchStatus] = Option(lastFetchStatus.get())

  def syncAll() = {
    val fetchStatus = newFetchStatus
    val currentStatus = atomicUpdateFetchStatus(currentStatus => {
      Option(currentStatus) match {
        case Some(status) => status.inProgress match {
          case true => currentStatus
          case false => fetchStatus
        }
        case _ => fetchStatus
      }
    })
    val isAlreadyRunningAtomic = currentStatus != fetchStatus
    if(isAlreadyRunningAtomic) {
      val message = s"syncAll is already running! $currentStatus"
      logger.error(message)
      throw new RuntimeException(message)
    } else {
      logger.info(s"Starting sync all!")
      val hakemusFutures: Set[Future[Seq[HetuPersonOid]]] = activeKKHakuOids.get()
        .map(hakuOid => hakemusService.hetuAndPersonOidForHaku(hakuOid))

      Future.sequence(hakemusFutures).onComplete {
        case Success(persons) =>
          handleHakemukset(currentStatus.uuid, persons.flatten)

        case Failure(e: Throwable) =>
          logger.error(s"failed to fetch 'henkilotunnukset' from hakemus service: ${e.getMessage}")
          atomicUpdateFetchStatus(l => l.copy(succeeded=Some(false), end = Some(new Date())))
          throw e
      }
    }


  }

  private def handleHakemukset(groupUuid: String, persons: Set[HetuPersonOid]): Unit = {
    val hetuToPersonOid: Map[String, String] = persons.map(person => person.hetu -> person.personOid).toMap
    val allSucceeded = new AtomicBoolean(true)
    try {

      val count: Int = Math.ceil(hetuToPersonOid.keys.toList.size.toDouble / ytlHttpClient.chunkSize.toDouble).toInt
      ytlHttpClient.fetch(groupUuid, hetuToPersonOid.keys.toList).zipWithIndex.foreach {
        case (Left(e: Throwable), index) =>
          logger.error(s"failed to fetch YTL data (patch ${index+1}/$count): ${e.getMessage}")
          allSucceeded.set(false)
        case (Right((zip, students)), index) =>
          logger.info(s"Fetch succeeded on YTL data patch ${index+1}/$count!")
          // TODO persist students
          IOUtils.closeQuietly(zip)
      }
    } catch {
      case e: Throwable =>
        allSucceeded.set(false)
        logger.error(s"")
    } finally {
      logger.info(s"Finished sync all! All patches succeeded = ${allSucceeded.get()}!")
      atomicUpdateFetchStatus(l => l.copy(succeeded=Some(allSucceeded.get()), end = Some(new Date())))
    }
  }

  private def persistKokelas(kokelas: Kokelas): Unit = {
    ytlActor ! kokelas
  }
}
