package fi.vm.sade.hakurekisteri.integration.ytl

import java.io.{FileOutputStream, File}
import java.text.SimpleDateFormat
import java.util.function.UnaryOperator
import java.util.zip.ZipInputStream
import java.util.{UUID, Date}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import akka.actor.ActorRef
import fi.vm.sade.auditlog.hakurekisteri.HakuRekisteriOperation.RESOURCE_UPDATE
import fi.vm.sade.auditlog.hakurekisteri.{HakuRekisteriOperation, LogMessage}
import fi.vm.sade.hakurekisteri.integration.hakemus.{IHakemusService, FullHakemus, HakemusService, HetuPersonOid}
import fi.vm.sade.hakurekisteri.web.AuditLogger
import fi.vm.sade.hakurekisteri.web.AuditLogger.audit
import fi.vm.sade.properties.OphProperties
import org.apache.commons.io.IOUtils
import org.apache.commons.lang.time.DateUtils
import org.slf4j.LoggerFactory

import scala.collection.Set
import scala.concurrent._
import scala.util.{Try, Failure, Success}

case class LastFetchStatus(uuid: String, start: Date, end: Option[Date], succeeded: Option[Boolean]) {
  def inProgress = end.isEmpty
}

class YtlIntegration(config: OphProperties,
                     ytlHttpClient: YtlHttpFetch,
                     hakemusService: IHakemusService,
                     ytlActor: ActorRef) {
  private val logger = LoggerFactory.getLogger(getClass)
  val activeKKHakuOids = new AtomicReference[Set[String]](Set.empty)
  private val lastFetchStatus = new AtomicReference[LastFetchStatus]();
  private def newFetchStatus = LastFetchStatus(UUID.randomUUID().toString, new Date(), None, None)
  implicit val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5))

  def setAktiivisetKKHaut(hakuOids: Set[String]) = activeKKHakuOids.set(hakuOids)

  def sync(hakemus: FullHakemus): Either[Throwable, Kokelas] = {
    if(activeKKHakuOids.get().contains(hakemus.applicationSystemId)) {
      if(hakemus.stateValid) {
        hakemus.personOid match {
          case Some(personOid) =>
            hakemus.hetu match {
              case Some(hetu) =>
                logger.debug(s"Syncronizing hakemus ${hakemus.oid} with YTL")
                ytlHttpClient.fetchOne(hetu) match {
                  case None =>
                    val noData = s"No YTL data for hakemus ${hakemus.oid}"
                    logger.debug(noData)
                    Left(new RuntimeException(noData))
                  case Some((json, student)) =>
                    val kokelas = StudentToKokelas.convert(personOid, student)
                    persistKokelas(kokelas)
                    Right(kokelas)
                }
              case None =>
                val noHetu = s"Skipping YTL update as hakemus (${hakemus.oid}) doesn't have henkilotunnus!"
                logger.debug(noHetu)
                Left(new RuntimeException(noHetu))
            }
          case None =>
            val noOid = s"Skipping YTL update as hakemus (${hakemus.oid}) doesn't have person OID!"
            logger.error(noOid)
            Left(new RuntimeException(noOid))
        }
      } else {
        val invalidState = s"Skipping YTL update as hakemus (${hakemus.oid}) is not in valid state!"
        logger.debug(invalidState)
        Left(new RuntimeException(invalidState))
      }
    } else {
      val notActiveHaku =s"Skipping YTL update as hakemus (${hakemus.oid}) is not in active haku (not active ${hakemus.applicationSystemId})!"
      logger.debug(notActiveHaku)
      Left(new RuntimeException(notActiveHaku))
    }
  }

  def sync(personOid: String): Future[Seq[Either[Throwable, Kokelas]]] = {
    val hakemusForPerson: Future[Seq[FullHakemus]] = hakemusService.hakemuksetForPerson(personOid)
    hakemusForPerson.flatMap {
      hakemukset =>
        if(hakemukset.isEmpty) {
          logger.error(s"failed to fetch one hakemus from hakemus service with person OID $personOid")
          throw new RuntimeException(s"Hakemus not found with person OID $personOid!")
        }
        val hakemuksetInCorrectStateAndWithPersonOid = hakemukset.filter {
          _.state match {
          case Some("ACTIVE") => true
          case Some("INCOMPLETE") => true
          case _ => false
          }
        }.filter(_.personOid.isDefined)
        if(hakemuksetInCorrectStateAndWithPersonOid.isEmpty) {
          logger.error(s"Hakemukset with person OID $personOid in wrong state!")
          Future.failed(new RuntimeException(s"Hakemukset with person OID $personOid in wrong state!"))
        } else {
          Future.successful(hakemuksetInCorrectStateAndWithPersonOid.map(sync))
        }
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
      audit.log(message("Ytl sync started!"))
      def fetchInChunks(hakuOids: Set[String]): Future[Set[HetuPersonOid]] = {
        def fetchChunk(chunk: Set[String]): Future[Set[HetuPersonOid]] = {
          Future.sequence(chunk.map(hakuOid => hakemusService.hetuAndPersonOidForHaku(hakuOid))).map(_.flatten)
        }
        hakuOids.grouped(10).foldLeft(Future.successful(Set.empty[HetuPersonOid])) {
          case (result, chunk) => result.flatMap(rs => fetchChunk(chunk).map(rs ++ _))
        }
      }
      fetchInChunks(activeKKHakuOids.get()).onComplete {
        case Success(persons) =>
          handleHakemukset(currentStatus.uuid, persons)

        case Failure(e: Throwable) =>
          logger.error(s"failed to fetch 'henkilotunnukset' from hakemus service: ${e.getMessage}")
          audit.log(message(s"Ytl sync failed to fetch 'henkilotunnukset': ${e.getMessage}"))
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
          audit.log(message(s"Ytl sync failed to fetch YTL data (patch ${index+1}/$count): ${e.getMessage}"))
          allSucceeded.set(false)
        case (Right((zip, students)), index) =>
          logger.info(s"Fetch succeeded on YTL data patch ${index+1}/$count!")
          students.flatMap(student => hetuToPersonOid.get(student.ssn) match {
            case Some(personOid) =>
              Try(StudentToKokelas.convert(personOid, student)) match {
                case Success(student) => Some(student)
                case Failure(exception) =>
                  logger.error(s"Skipping student with SSN = ${student.ssn} because ${exception.getMessage}")
                  None
              }
            case None =>
              logger.error(s"Skipping student as SSN (${student.ssn}) didnt match any person OID")
              None
          }
          ).foreach(persistKokelas)
          IOUtils.closeQuietly(zip)
      }
    } catch {
      case e: Throwable =>
        allSucceeded.set(false)
        logger.error(s"YTL sync all failed!", e)
        audit.log(message(s"Ytl sync failed: ${e.getMessage}"))
    } finally {
      logger.info(s"Finished sync all! All patches succeeded = ${allSucceeded.get()}!")
      val msg = Option(allSucceeded.get()).filter(_ == true).map(_ => "successfully").getOrElse("with failing patches!")
      audit.log(message(s"Ytl sync ended ${msg}!"))
      atomicUpdateFetchStatus(l => l.copy(succeeded=Some(allSucceeded.get()), end = Some(new Date())))
    }
  }

  private def persistKokelas(kokelas: Kokelas): Unit = {
    ytlActor ! kokelas
  }
  private def message(msg:String) = LogMessage.builder().message(msg).add("operaatio","YTL_SYNC").build()

}
