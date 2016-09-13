package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.concurrent.Executors

import akka.actor.ActorRef
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusService}
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
      case Success(hakemukset) => {
        handleHakemukset(hakemukset.toSet)
      }
      case Failure(e) => throw e
    }
  }

  def syncAll = {
    val hakemusFutures: Set[Future[Seq[FullHakemus]]] = activeHakuOids
      .map(hakuOid => hakemusService.hetuAndPersonOidForHaku(hakuOid))

    Future.sequence(hakemusFutures).onComplete {
      case Success(hakemukset) =>
        handleHakemukset(hakemukset.flatten)

      case Failure(e: Throwable) =>
        logger.error(s"failed to fetch 'henkilotunnukset' from hakemus service: ${e.getMessage}")
        throw e
    }

  }

  private def handleHakemukset(hakemukset: Set[FullHakemus]): Unit = {
    val hetuToPersonOid: Map[String, String] = hakemukset.map(hakemus => hakemus.hetu.get -> hakemus.personOid.get).toMap

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
