package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.concurrent.Executors

import akka.actor.ActorRef
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusService, HetuPersonOid}
import org.apache.commons.io.IOUtils
import org.slf4j.LoggerFactory

import scala.collection.Set
import scala.concurrent._
import scala.util.{Failure, Success}

class YtlIntegration(ytlHttpClient: YtlHttpFetch,
                     hakemusService: HakemusService,
                     suoritusRekisteri: ActorRef) {
  private val logger = LoggerFactory.getLogger(getClass)

  var activeHakuOids = Set[String]()
  implicit val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5))

  def setAktiivisetHaut(hakuOids: Set[String]) = activeHakuOids = hakuOids

  def sync(personOid: String) = {
    hakemusService.hakemuksetForPerson(personOid).onComplete {
      case Success(hakemukset) =>
        if(hakemukset.isEmpty) {
        logger.error(s"failed to fetch one hakemus from hakemus service with person OID ${personOid}")
        throw new RuntimeException(s"Hakemus not found with person OID ${personOid}!")
        }
        val hakemus = hakemukset.head
        val student: Student = ytlHttpClient.fetchOne(hakemus.hetu.get)
        val kokelas = StudentToKokelas.convert(hakemus.personOid.get, student)
        YtlDiff.writeKokelaatAsJson(List(kokelas).iterator, "ytl-v2-single-kokelas.json")
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
        }, "ytl-v2-kokelaat.json")
        IOUtils.closeQuietly(zip)
    }
  }
}
