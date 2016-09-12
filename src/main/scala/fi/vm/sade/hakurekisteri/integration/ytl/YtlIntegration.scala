package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.concurrent.Executors

import akka.actor.ActorRef
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusService}

import scala.collection.Set
import scala.concurrent._
import scala.util.{Failure, Success}

class YtlIntegration(ytlHttpClient: YtlHttpFetch,
                     hakemusService: HakemusService,
                     suoritusRekisteri: ActorRef) {

  var activeHakuOids = Set[String]()
  implicit val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5))

  def setAktiivisetHaut(hakuOids: Set[String]) = activeHakuOids = hakuOids

  def syncAll = {
    val hakemusFutures: Set[Future[Seq[FullHakemus]]] = activeHakuOids
      .map(hakuOid => hakemusService.hetuAndPersonOidForHaku(hakuOid))

    Future.sequence(hakemusFutures).onComplete {
      case Success(hakemukset) =>
        val hetuToPersonOid: Map[String, String] = hakemukset.flatten.map(hakemus => hakemus.hetu.get -> hakemus.personOid.get).toMap

        ytlHttpClient.fetch(hetuToPersonOid.keys.toList) match {
          case Left(e: Throwable) =>
            // TODO
          case Right(students) =>
            val kokelaat: Iterator[Some[Kokelas]] = students.map { student =>
              Some(StudentToKokelas.convert(hetuToPersonOid.get(student.ssn).get, student))
            }
            YtlDiff.writeKokelaatAsJson(kokelaat.toSeq, "ytl-v2-kokelaat.json")
        }

      case Failure(e: Throwable) =>
        // TODO
    }

  }

}
