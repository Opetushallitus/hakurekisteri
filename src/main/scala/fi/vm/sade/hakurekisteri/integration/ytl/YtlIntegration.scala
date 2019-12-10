package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.concurrent.atomic.{AtomicReference}
import java.util.concurrent.{Executors, TimeUnit}
import java.util.function.UnaryOperator
import java.util.{Date, UUID}

import fi.vm.sade.hakurekisteri._
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.henkilo.{IOppijaNumeroRekisteri, PersonOidsWithAliases}
import fi.vm.sade.properties.OphProperties
import javax.mail.Message.RecipientType
import javax.mail.Session
import javax.mail.internet.{InternetAddress, MimeMessage}
import org.apache.commons.io.IOUtils
import org.slf4j.LoggerFactory

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}


case class LastFetchStatus(uuid: String, start: Date, end: Option[Date], hasFailures: Option[Boolean]) {
  def inProgress = end.isEmpty
}

class YtlIntegration(properties: OphProperties,
                     ytlHttpClient: YtlHttpFetch,
                     hakemusService: IHakemusService,
                     oppijaNumeroRekisteri: IOppijaNumeroRekisteri,
                     ytlKokelasPersister: KokelasPersister,
                     config: Config) {
  private val logger = LoggerFactory.getLogger(getClass)
  val activeKKHakuOids = new AtomicReference[Set[String]](Set.empty)
  private val lastStatus = new AtomicReference[LastFetchStatus]()
  private def createNewStatus = LastFetchStatus(UUID.randomUUID().toString, new Date(), None, None)
  implicit val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5))

  private val audit = SuoritusAuditBackend.audit

  def setAktiivisetKKHaut(hakuOids: Set[String]): Unit = activeKKHakuOids.set(hakuOids)

  def sync(hakemus: HakijaHakemus, personOidsWithAliases: PersonOidsWithAliases): Try[Kokelas] = {
    if(activeKKHakuOids.get().contains(hakemus.applicationSystemId)) {
      if(hakemus.stateValid) {
        hakemus.personOid match {
          case Some(personOid) =>
            hakemus.hetu match {
              case Some(hetu) =>
                logger.debug(s"Syncronizing hakemus ${hakemus.oid} with YTL hakemus=$hakemus")
                ytlHttpClient.fetchOne(hetu) match {
                  case None =>
                    val noData = s"No YTL data for hakemus ${hakemus.oid}"
                    logger.debug(noData)
                    Failure(new RuntimeException(noData))
                  case Some((json, student)) =>
                    val kokelas = StudentToKokelas.convert(personOid, student)
                    val persistKokelasStatus = ytlKokelasPersister.persistSingle(KokelasWithPersonAliases(kokelas, personOidsWithAliases))
                    try {
                      Await.result(persistKokelasStatus, config.ytlSyncTimeout.duration + 10.seconds)
                      Success(kokelas)
                    } catch {
                      case e: Throwable =>
                        Failure(new RuntimeException(s"Persist kokelas ${kokelas.oid} failed", e))
                    }
                }
              case None =>
                val noHetu = s"Skipping YTL update as hakemus (${hakemus.oid}) doesn't have henkilotunnus!"
                logger.debug(noHetu)
                Failure(new RuntimeException(noHetu))
            }
          case None =>
            val noOid = s"Skipping YTL update as hakemus (${hakemus.oid}) doesn't have person OID!"
            logger.error(noOid)
            Failure(new RuntimeException(noOid))
        }
      } else {
        val invalidState = s"Skipping YTL update as hakemus (${hakemus.oid}) is not in valid state!"
        logger.debug(invalidState)
        Failure(new RuntimeException(invalidState))
      }
    } else {
      val notActiveHaku =s"Skipping YTL update as hakemus (${hakemus.oid}) is not in active haku (not active ${hakemus.applicationSystemId})!"
      logger.debug(notActiveHaku)
      Failure(new RuntimeException(notActiveHaku))
    }
  }

  def sync(personOid: String): Future[Seq[Try[Kokelas]]] = {
    hakemusService.hakemuksetForPerson(personOid)
      .zip(oppijaNumeroRekisteri.enrichWithAliases(Set(personOid)))
      .map(pair => pair._1.collect {
        case h if h.stateValid && h.personOid.isDefined => pair.copy(_1 = h)
      }).flatMap {
      hakemuksetWithPersonOids =>
        if (hakemuksetWithPersonOids.isEmpty) {
          logger.error(s"failed to fetch one hakemus from hakemus service with person OID $personOid")
          Future.failed(new RuntimeException(s"Hakemus not found with person OID $personOid!"))
        } else {
          Future.successful(hakemuksetWithPersonOids.map(pair => sync(pair._1, pair._2)))
        }
    }
  }

  private def atomicUpdateStatus(updator: LastFetchStatus => LastFetchStatus): LastFetchStatus = {
    lastStatus.updateAndGet(
      new UnaryOperator[LastFetchStatus]{
        override def apply(t: LastFetchStatus): LastFetchStatus = updator.apply(t)
      }
    )
  }

  private def atomicUpdateStatusHasFailures(hasFailures: Boolean): LastFetchStatus = {
    atomicUpdateStatus(l => {
      val newHasFailures = l.hasFailures match {
        case Some(true) =>
          // one-way: don't change to false if was already true
          logger.info(s"hasFailures was already set to true (tried to set to ${hasFailures})")
          true
        case old =>
          logger.info(s"Setting hasFailures to ${hasFailures}, was ${old}")
          hasFailures
      }
      val end = Some(new Date())
      l.copy(hasFailures = Some(newHasFailures), end = end)
    })
  }

  def getLastStatus: Option[LastFetchStatus] = Option(lastStatus.get())

  def getCurrentStatusAndAlreadyRunning: (LastFetchStatus,Boolean) = {
    val newStatus = createNewStatus
    val currentStatus = atomicUpdateStatus(oldStatus => {
      Option(oldStatus) match {
        case Some(status) if status.inProgress => oldStatus
        case _ => newStatus
      }
    })
    val isAlreadyRunningAtomic = currentStatus != newStatus
    (currentStatus, isAlreadyRunningAtomic)
  }

  /**
    * Begins async synchronization. Throws an exception if an error occurs during it.
    */
  def syncAll(failureEmailSender: FailureEmailSender = new RealFailureEmailSender): Unit = {
    val (currentStatus, isAlreadyRunningAtomic) = getCurrentStatusAndAlreadyRunning
    if (isAlreadyRunningAtomic) {
      val message = s"syncAll is already running! $currentStatus"
      logger.error(message)
      throw new RuntimeException(message)
    } else {
      logger.info(s"Starting sync all!")
      def fetchInChunks(hakuOids: Set[String]): Future[Set[HetuPersonOid]] = {
        def fetchChunk(chunk: Set[String]): Future[Set[HetuPersonOid]] = {
          Future.sequence(chunk.map(hakuOid => hakemusService.hetuAndPersonOidForHaku(hakuOid))).map(_.flatten)
        }
        hakuOids.grouped(10).foldLeft(Future.successful(Set.empty[HetuPersonOid])) {
          case (result, chunk) => result.flatMap(rs => fetchChunk(chunk).map(rs ++ _))
        }
      }
      logger.info(s"Fetching in chunks, activeKKHakuOids: ${activeKKHakuOids.get()}")
      fetchInChunks(activeKKHakuOids.get()).onComplete {
        case Success(persons) =>
          logger.info(s"(Group UUID: ${currentStatus.uuid} ) success fetching personOids, total found: ${persons.size}.")
          handleHakemukset(currentStatus.uuid, persons, failureEmailSender)

        case Failure(e: Throwable) =>
          logger.error(s"failed to fetch 'henkilotunnukset' from hakemus service", e)
          failureEmailSender.sendFailureEmail(s"Ytl sync failed to fetch 'henkilotunnukset' from hakemus service: ${e.getMessage}")
          atomicUpdateStatusHasFailures(hasFailures = true)
          throw e
      }
    }
  }

  private def handleHakemukset(groupUuid: String, persons: Set[HetuPersonOid],
                               failureEmailSender: FailureEmailSender): Unit = {
    val hetuToPersonOid: Map[String, String] = persons.map(person => person.hetu -> person.personOid).toMap
    val personOidsWithAliases: PersonOidsWithAliases = Await.result(oppijaNumeroRekisteri.enrichWithAliases(persons.map(_.personOid)),
      Duration(1, TimeUnit.MINUTES))
    try {
      logger.info(s"Begin fetching YTL data for group UUID $groupUuid")
      val count: Int = Math.ceil(hetuToPersonOid.keys.toList.size.toDouble / ytlHttpClient.chunkSize.toDouble).toInt
      val futures: Iterator[Future[Unit]] = ytlHttpClient.fetch(groupUuid, hetuToPersonOid.keys.toList).zipWithIndex.map {
        case (Left(e: Throwable), index) =>
          logger.error(s"failed to fetch YTL data (batch ${index + 1}/$count): ${e.getMessage}", e)
          atomicUpdateStatusHasFailures(hasFailures = true)
          Future.failed(e)
        case (Right((zip, students)), index) =>
          try {
            logger.info(s"Fetch succeeded on YTL data batch ${index + 1}/$count!")

            val kokelaksetToPersist: Iterator[Kokelas] =
              students.flatMap(student => hetuToPersonOid.get(student.ssn) match {
                case Some(personOid) =>
                  Try(StudentToKokelas.convert(personOid, student)) match {
                    case Success(candidate) => Some(candidate)
                    case Failure(exception) =>
                      logger.error(s"Skipping student with SSN = ${student.ssn} because ${exception.getMessage}", exception)
                      None
                  }
                case None =>
                  logger.error(s"Skipping student as SSN (${student.ssn}) didnt match any person OID")
                  None
              })

            val futureForAllKokelasesToPersist: Future[Unit] = SequentialBatchExecutor.runInBatches(
                kokelaksetToPersist, config.ytlSyncParallelism)(kokelas => {
                    ytlKokelasPersister.persistSingle(KokelasWithPersonAliases(kokelas, personOidsWithAliases.intersect(Set(kokelas.oid))))
                })

            futureForAllKokelasesToPersist.andThen {
              case Success(_) =>
                logger.info(s"Finished persisting YTL data batch ${index + 1}/$count! All kokelakset succeeded!")
                val latestStatus = atomicUpdateStatusHasFailures(hasFailures = false)
                logger.info(s"Latest status after update: ${latestStatus}")
              case Failure(e) =>
                logger.error(s"Failed to persist all kokelas on YTL data batch ${index + 1}/$count", e)
                atomicUpdateStatusHasFailures(hasFailures = true)
                failureEmailSender.sendFailureEmail(s"Finished sync all with failing batches!")
            }
          } finally {
            logger.info(s"Closing zip file on YTL data batch ${index + 1}/$count")
            IOUtils.closeQuietly(zip)
          }
      }

      Future.sequence(futures.toSeq).onComplete { _ =>
          val hasFailuresOpt: Option[Boolean] = getLastStatus.flatMap(_.hasFailures)
          logger.info(s"Completed YTL syncAll with hasFailures=${hasFailuresOpt}")
      }
    } catch {
      case e: Throwable =>
        atomicUpdateStatusHasFailures(hasFailures = true)
        logger.error(s"YTL syncAll failed!", e)
    } finally {
      logger.info(s"Finished YTL syncAll")
    }
  }

  private class RealFailureEmailSender extends FailureEmailSender {
    override def sendFailureEmail(txt: String): Unit = {
      val session = Session.getInstance(config.email.getAsJavaProperties())
      var msg = new MimeMessage(session)
      msg.setText(txt)
      msg.setSubject("YTL sync all failed")
      msg.setFrom(new InternetAddress(config.email.smtpSender))
      var tr = session.getTransport("smtp")
      try {
        val recipients: Array[javax.mail.Address] = config.properties.getOrElse("suoritusrekisteri.ytl.error.report.recipients","")
          .split(",").map(address => {
          new InternetAddress(address)
        })
        msg.setRecipients(RecipientType.TO, recipients)
        tr.connect(config.email.smtpHost, config.email.smtpUsername, config.email.smtpPassword)
        msg.saveChanges()
        logger.debug(s"Sending failure email to $recipients (text=$msg)")
        tr.sendMessage(msg, msg.getAllRecipients)
      } catch {
        case e: Throwable =>
          logger.error("Could not send email", e)
      } finally {
        tr.close()
      }
    }
  }
}

abstract class FailureEmailSender {
  def sendFailureEmail(txt: String): Unit
}
