package fi.vm.sade.hakurekisteri.integration.koodisto

import java.net.URLEncoder

import akka.actor.Actor
import akka.event.Logging
import akka.pattern.pipe
import com.stackmob.newman.response.HttpResponseCode
import fi.vm.sade.hakurekisteri.integration.{PreconditionFailedException, VirkailijaRestClient}

import scala.compat.Platform
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._

case class GetRinnasteinenKoodiArvoQuery(koodiUri: String, rinnasteinenKoodistoUri: String)
case class Koodisto(koodistoUri: String)
case class KoodiMetadata(nimi: String, kieli: String)
case class Koodi(koodiArvo: String, koodiUri: String, koodisto: Koodisto, metadata: Seq[KoodiMetadata])
case class RinnasteinenKoodiNotFoundException(message: String) extends Exception(message)
case class CachedRelaatio(inserted: Long, arvo: Future[String])

case class GetKoodi(koodistoUri: String, koodiUri: String)

case class CachedKoodi(inserted: Long, koodi: Future[Option[Koodi]])

class KoodistoActor(restClient: VirkailijaRestClient)(implicit val ec: ExecutionContext) extends Actor {
  val log = Logging(context.system, this)

  var koodiCache: Map[String, CachedKoodi] = Map()
  var relaatioCache: Map[GetRinnasteinenKoodiArvoQuery, CachedRelaatio] = Map()
  val expirationDurationMillis = 60.minutes.toMillis

  override def receive: Receive = {
    case q: GetRinnasteinenKoodiArvoQuery =>
      getRinnasteinenKoodiArvo(q) pipeTo sender

    case q: GetKoodi =>
      getKoodi(q.koodistoUri, q.koodiUri) pipeTo sender
  }

  def addToKoodiCache(koodiUri: String, koodi: Future[Option[Koodi]]) = {
    log.debug(s"adding koodi $koodiUri to cache")
    koodiCache = koodiCache + (koodiUri -> CachedKoodi(Platform.currentTime, koodi))
  }

  def getKoodi(koodistoUri: String, koodiUri: String): Future[Option[Koodi]] = {
    if (koodiCache.contains(koodiUri) && koodiCache(koodiUri).inserted + expirationDurationMillis > Platform.currentTime) {
      koodiCache(koodiUri).koodi
    } else try {
      val koodi = restClient.readObject[Koodi](s"/rest/json/${URLEncoder.encode(koodistoUri, "UTF-8")}/koodi/${URLEncoder.encode(koodiUri, "UTF-8")}", HttpResponseCode.Ok).map(Some(_))
      addToKoodiCache(koodiUri, koodi)
      koodi
    } catch {
      case t: PreconditionFailedException =>
        log.warning(s"koodi not found with koodiUri $koodiUri: $t")
        val koodi = Future.successful(None)
        addToKoodiCache(koodiUri, koodi)
        koodi
    }
  }
  
  def addToRelaatioCache(q: GetRinnasteinenKoodiArvoQuery, f: Future[String]): Unit = {
    log.debug(s"adding relaatio $q to cache")
    relaatioCache = relaatioCache + (q -> CachedRelaatio(Platform.currentTime, f))
  }

  def getRinnasteinenKoodiArvo(q: GetRinnasteinenKoodiArvoQuery): Future[String] = {
    if (relaatioCache.contains(q) && relaatioCache(q).inserted + expirationDurationMillis > Platform.currentTime) {
      relaatioCache(q).arvo
    } else {
      val f: Future[Seq[Koodi]] = restClient.readObject[Seq[Koodi]](s"/rest/json/relaatio/rinnasteinen/${URLEncoder.encode(q.koodiUri, "UTF-8")}", HttpResponseCode.Ok)
      val fs = f.map(_.find(_.koodisto.koodistoUri == q.rinnasteinenKoodistoUri) match {
        case None => throw RinnasteinenKoodiNotFoundException(s"rinnasteisia koodeja ei löytynyt koodiurilla ${q.koodiUri}")
        case Some(k) => k.koodiArvo
      })
      
      addToRelaatioCache(q, fs)
      
      fs
    }
  }
}
