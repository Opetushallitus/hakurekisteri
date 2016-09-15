package fi.vm.sade.hakurekisteri.integration.ytl

import java.io.File
import java.util.concurrent.Executors

import akka.actor.ActorRef
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusService, HetuPersonOid}
import fi.vm.sade.properties.OphProperties
import org.apache.commons.io.IOUtils
import org.slf4j.LoggerFactory

import scala.collection.Set
import scala.concurrent._
import scala.util.{Failure, Success}

class YtlIntegration(config: OphProperties,
                     ytlHttpClient: YtlHttpFetch,
                     hakemusService: HakemusService,
                     suoritusRekisteri: ActorRef) {
  private val logger = LoggerFactory.getLogger(getClass)
  val kokelaatDownloadDirectory = config.getOrElse("ytl.kokelaat.download.directory", Option(System.getProperty("user.home"))
    .getOrElse(throw new RuntimeException("Either set 'ytl.kokelaat.download.directory' variable or 'user.home' env.var.")))
  val kokelaatDownloadPath = new File(kokelaatDownloadDirectory, "ytl-v2-kokelaat.json)").getAbsolutePath
  var activeHakuOids = Set[String]()
  implicit val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5))

  def setAktiivisetHaut(hakuOids: Set[String]) = activeHakuOids = hakuOids

  def sync(hakemus: FullHakemus) = {
    logger.info(s"Syncronizing hakemus ${hakemus.oid} with YTL")
    val student: Student = ytlHttpClient.fetchOne(hakemus.hetu.get)
    val kokelas = StudentToKokelas.convert(hakemus.personOid.get, student)
    YtlDiff.writeKokelaatAsJson(List(kokelas).iterator, kokelaatDownloadPath)
  }

  def sync(personOid: String) = {
    hakemusService.hakemuksetForPerson(personOid).onComplete {
      case Success(hakemukset) =>
        if(hakemukset.isEmpty) {
          logger.error(s"failed to fetch one hakemus from hakemus service with person OID $personOid")
          throw new RuntimeException(s"Hakemus not found with person OID $personOid!")
        }
        val hakemus = hakemukset.head
        sync(hakemus)
      case Failure(e) =>
        logger.error(s"failed to fetch one hakemus from hakemus service: ${e.getMessage}")
        throw e
    }
  }

  def syncAll() = {
    val hakemusFutures: Set[Future[Seq[HetuPersonOid]]] = activeHakuOids
      .map(hakuOid => hakemusService.hetuAndPersonOidForHaku(hakuOid))

    Future.sequence(hakemusFutures).onComplete {
      case Success(persons) =>
        handleHakemukset(persons.flatten)

      case Failure(e: Throwable) =>
        logger.error(s"failed to fetch 'henkilotunnukset' from hakemus service: ${e.getMessage}")
        throw e
    }

  }

  private def handleHakemukset(persons: Set[HetuPersonOid]): Unit = {
    val hetuToPersonOid: Map[String, String] = persons.map(person => person.hetu -> person.personOid).toMap

    ytlHttpClient.fetch(hetuToPersonOid.keys.toList) match {
      case Left(e: Throwable) =>
        logger.error(s"failed to fetch YTL data: ${e.getMessage}")
        throw e
      case Right((zip, students)) =>
        YtlDiff.writeKokelaatAsJson(students.map { student =>
          StudentToKokelas.convert(hetuToPersonOid.get(student.ssn).get, student)
        }, kokelaatDownloadPath)
        IOUtils.closeQuietly(zip)
    }
  }
}
