package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.function.UnaryOperator
import java.util.zip.ZipInputStream
import java.util.{Date, UUID}
import javax.mail.Message.RecipientType
import javax.mail.Session
import javax.mail.internet.{InternetAddress, MimeMessage}

import akka.actor.ActorRef
import fi.vm.sade.auditlog.hakurekisteri.LogMessage
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.web.AuditLogger.audit
import fi.vm.sade.properties.OphProperties
import org.apache.commons.io.IOUtils
import org.slf4j.LoggerFactory

import scala.collection.Set
import scala.concurrent._
import scala.util.{Failure, Success, Try}

case class LastFetchStatus(uuid: String, start: Date, end: Option[Date], succeeded: Option[Boolean]) {
  def inProgress = end.isEmpty
}

class YtlIntegration(properties: OphProperties,
                     ytlHttpClient: YtlHttpFetch,
                     hakemusService: IHakemusService,
                     ytlActor: ActorRef,
                     config: Config) {
  private val logger = LoggerFactory.getLogger(getClass)
  val activeKKHakuOids = new AtomicReference[Set[String]](Set.empty)
  private val lastFetchStatus = new AtomicReference[LastFetchStatus]()
  private def newFetchStatus = LastFetchStatus(UUID.randomUUID().toString, new Date(), None, None)
  implicit val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5))

  def setAktiivisetKKHaut(hakuOids: Set[String]): Unit = activeKKHakuOids.set(hakuOids)

  def sync(hakemus: HakijaHakemus): Try[Kokelas] = {
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
                    Failure(new RuntimeException(noData))
                  case Some((json, student)) =>
                    val kokelas = StudentToKokelas.convert(personOid, student)
                    persistKokelas(kokelas)
                    Success(kokelas)
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
      .map(_.collect {
        case h: FullHakemus if h.stateValid && h.personOid.isDefined => h
      }).flatMap {
      hakemukset =>
        if(hakemukset.isEmpty) {
          logger.error(s"failed to fetch one hakemus from hakemus service with person OID $personOid")
          Future.failed(new RuntimeException(s"Hakemus not found with person OID $personOid!"))
        } else {
          Future.successful(hakemukset.map(sync))
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

  private def fetchInChunks(hakuOids: Set[String]): Future[Set[HetuPersonOid]] = {
    def fetchChunk(chunk: Set[String]): Future[Set[HetuPersonOid]] = {
      Future.sequence(chunk.map(hakuOid => hakemusService.hetuAndPersonOidForHaku(hakuOid))).map(_.flatten)
    }

    hakuOids.grouped(10).foldLeft(Future.successful(Set.empty[HetuPersonOid])) {
      case (result, chunk) => result.flatMap(rs => fetchChunk(chunk).map(rs ++ _))
    }
  }
  private def fetchActiveKKPersons(uuid: String, operation: Set[HetuPersonOid] => Unit): Unit = {
    fetchInChunks(activeKKHakuOids.get()).onComplete {
      case Success(persons) =>
        logger.info(s"(Group UUID: ${uuid} ) success fetching personOids, total found: ${persons.size}.")
        operation.apply(persons)

      case Failure(e: Throwable) =>
        logger.error(s"failed to fetch 'henkilotunnukset' from hakemus service: ${e.getMessage}")
        sendFailureEmail(s"Ytl sync failed to fetch 'henkilotunnukset' from hakemus service: ${e.getMessage}")
        audit.log(message(s"Ytl sync failed to fetch 'henkilotunnukset': ${e.getMessage}"))
        atomicUpdateFetchStatus(l => l.copy(succeeded=Some(false), end = Some(new Date())))
        throw e
    }
  }
  /**
    * Begins async synchronization. Throws an exception if an error occurs during it.
    */
  def syncAll(): Unit = {
    val fetchStatus = newFetchStatus
    val currentStatus = atomicUpdateFetchStatus(currentStatus => {
      Option(currentStatus) match {
        case Some(status) if status.inProgress => currentStatus
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
      logger.info(s"Fetching in chunks, activeKKHakuOids: ${activeKKHakuOids.get()}")
      fetchActiveKKPersons(currentStatus.uuid, persons => handleHakemukset(currentStatus.uuid, persons))
    }
  }

  def syncWithGroupUuid(groupUuid: String): Unit = {
    fetchActiveKKPersons(groupUuid, persons => {
      val hetuToPersonOid: Map[String, String] = persons.map(person => person.hetu -> person.personOid).toMap
      ytlHttpClient.fetchWithGroupUuid(groupUuid).zipWithIndex.foreach {
        case ((zip, students), index) => {
          logger.info(s"Syncing with group uuid $groupUuid batch $index containing ${students.size} students!")
          try {
            handleStudents(hetuToPersonOid, students)
          } finally {
            IOUtils.closeQuietly(zip)
          }
        }
      }
    })
  }

  private def handleHakemukset(groupUuid: String, persons: Set[HetuPersonOid]): Unit = {
    val hetuToPersonOid: Map[String, String] = persons.map(person => person.hetu -> person.personOid).toMap
    val allSucceeded = new AtomicBoolean(true)
    try {
      val count: Int = Math.ceil(hetuToPersonOid.keys.toList.size.toDouble / ytlHttpClient.chunkSize.toDouble).toInt
      ytlHttpClient.fetch(groupUuid, hetuToPersonOid.keys.toList).zipWithIndex.foreach {
        case (Left(e: Throwable), index) =>
          logger.error(s"failed to fetch YTL data (patch ${index + 1}/$count): ${e.getMessage}", e)
          audit.log(message(s"Ytl sync failed to fetch YTL data (patch ${index + 1}/$count): ${e.getMessage}"))
          allSucceeded.set(false)
        case (Right((zip, students)), index) =>
          try {
          logger.info(s"Fetch succeeded on YTL data patch ${index+1}/$count! total students received: ${students.size}")
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
          }
          ).foreach(persistKokelas)
          } finally {
            IOUtils.closeQuietly(zip)
          }
      }
    } catch {
      case e: Throwable =>
        allSucceeded.set(false)
        logger.error(s"YTL sync all failed!", e)
        audit.log(message(s"Ytl sync failed: ${e.getMessage}"))
    } finally {
      logger.info(s"Finished sync all! All patches succeeded = ${allSucceeded.get()}!")
      val msg = Option(allSucceeded.get()).filter(_ == true).map(_ => "successfully").getOrElse("with failing patches!")
      if (!allSucceeded.get()) {
        sendFailureEmail(msg)
      }
      audit.log(message(s"Ytl sync ended $msg!"))
      atomicUpdateFetchStatus(l => l.copy(succeeded = Some(allSucceeded.get()), end = Some(new Date())))
    }
  }

  private def handleStudents(hetuToPersonOid: Map[String, String], students: Iterator[Student]) = {
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
    }).foreach(persistKokelas)
  }

  private def persistKokelas(kokelas: Kokelas): Unit = {
    ytlActor ! kokelas
  }
  private def message(msg:String) = LogMessage.builder().message(msg).add("operaatio","YTL_SYNC").build()

  private def sendFailureEmail(txt: String): Unit = {

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
      tr.sendMessage(msg, msg.getAllRecipients)
    } catch {
      case e: Throwable =>
        logger.error("Could not send email", e)
    } finally {
      tr.close()
    }
  }
}

